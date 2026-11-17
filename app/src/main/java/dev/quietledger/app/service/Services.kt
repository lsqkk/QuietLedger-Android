package dev.quietledger.app.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.quietledger.app.R
import dev.quietledger.app.core.Duty
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.core.Invoice
import dev.quietledger.app.core.NotifEvent
import dev.quietledger.app.core.Skipped
import dev.quietledger.app.core.balanceParts
import dev.quietledger.app.data.AppNames
import dev.quietledger.app.data.LedgerDb
import dev.quietledger.app.data.Prefs
import dev.quietledger.app.sync.SyncManager
import dev.quietledger.app.sync.SyncScheduler
import dev.quietledger.app.ui.MainActivity
import dev.quietledger.app.widget.InvoiceWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** 通知使用权在系统里登记后，回调里就能看到全系统通知（这是网页无法等价获得的数据源）。 */
class NotificationWatchService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** pkg -> 上次记录被跳过样本的时间，避免进度类通知把核对表刷满。 */
    private val skipLoggedAt = HashMap<String, Long>()
    private var skippedInserts = 0

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName == packageName) return
        val n = s.notification ?: return

        // 系统状态、静默通道、常驻/前台服务通知不计税；同类通知短时间内重复贴出只记一次。
        val reason = NotifRules.skipReason(this, s, n)
        if (reason != null) {
            noteSkipped(s, n, reason)
            return
        }
        val ts = if (s.postTime > 0) s.postTime else System.currentTimeMillis()
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val rank = NotificationListenerService.Ranking()
        val app = AppNames.label(this, s.packageName)
        val source = ConversationIdentity.read(s, n, rank.takeIf {
            currentRanking?.getRanking(s.key, it) == true
        }, app, title)
        val content = s.packageName + "|" + source?.key.orEmpty() + "|" + title + "|" + text
        if (NotifRules.isDuplicate(s.key, content, System.currentTimeMillis())) {
            noteSkipped(s, n, "重复贴出")
            return
        }
        if (source == null && NotifRules.isFlood(s.packageName, title, text, System.currentTimeMillis())) {
            noteSkipped(s, n, "短时刷屏")
            return
        }

        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val clearable = n.flags and Notification.FLAG_NO_CLEAR == 0
        // v2026-11-c：投递时打两个布尔标记，只上送「是/否」，不上送原文。
        // 免打扰 = 系统 DND（先确认有通知策略访问权限，避免 SecurityException）或本 App 静默。
        val systemDnd = Silence.hasAccess(this) && Silence.isSilenced(this)
        val dnd = systemDnd || Prefs.dndUntil(this) > ts
        val notice = NotifRules.isBroadcastNotice(title, text)
        val event = NotifEvent(
            // 同一个 key 可能承载多条不同内容的消息：id 里带上内容摘要，
            // 否则后来的消息会被主键冲突静默丢掉（表现为「怎么不统计了」）。
            id = ConversationIdentity.digest(s.key + "|" + ts + "|" + title + "|" + text),
            pkg = s.packageName,
            app = app,
            title = (title + " " + text).trim().take(200),
            ts = ts,
            priority = n.priority,
            category = n.category ?: "msg",
            ongoing = ongoing,
            clearable = clearable,
            dnd = dnd,
            notice = notice,
            source = source,
        )

        scope.launch {
            val inserted = LedgerDb.get(applicationContext).insertEvent(event)
            if (inserted) {
                WatchState.lastEventAt = ts
                Prefs.setLastEventAt(applicationContext, System.currentTimeMillis())
                InvoiceWidgetProvider.update(applicationContext)
                SyncScheduler.scheduleSoon(applicationContext)
                // 入账之后再看免税额有没有刚用完：当天只提醒一次，不挡记账、不等同步。
                notifyBudgetIfNeeded(applicationContext)
            }
        }
    }

    /**
     * 记一条「被跳过」的样本，供用户在应用内核对。
     * 只是诊断样本：同一应用 5 秒内只记一条，避免刷屏类进度通知写爆数据库。
     */
    private fun noteSkipped(s: StatusBarNotification, n: Notification, reason: String) {
        Prefs.addFiltered(applicationContext)
        val now = System.currentTimeMillis()
        val last = skipLoggedAt[s.packageName]
        if (last != null && now - last < 5_000L) return
        skipLoggedAt[s.packageName] = now
        if (skipLoggedAt.size > 300) skipLoggedAt.clear()
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val ts = if (s.postTime > 0) s.postTime else now
        scope.launch {
            try {
                val db = LedgerDb.get(applicationContext)
                db.insertSkipped(
                    Skipped(
                        id = s.key + "|" + ts + "|" + reason,
                        pkg = s.packageName,
                        app = AppNames.label(applicationContext, s.packageName),
                        title = (title + " " + text).trim().take(120),
                        ts = ts,
                        reason = reason,
                    ),
                )
                skippedInserts += 1
                if (skippedInserts % 40 == 0) {
                    db.pruneSkipped(now - 3L * 24 * 60 * 60 * 1000)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        WatchState.status = WatchState.CONNECTED
        LedgerDb.get(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        WatchState.markDisconnected()
    }
}

/**
 * 通知监听的运行态（同一进程，界面可直接读）。
 *
 * 国内 ROM 会在「清理后台」时把应用 force-stop，通知使用权还在，但绑定没了：
 * 账单看起来就"停了"，而且不给任何提示。这里记录连接状态并提供主动重连。
 *
 * 三态很重要：冷启动/重连后系统回调通常 1–3 秒到，这段时间算「正在连接」；
 * 超过 [GRACE_MS] 还没连上就判定为断开，界面提示并把重连交给前台/后台任务。
 */
object WatchState {
    const val UNKNOWN = 0
    const val CONNECTED = 1
    const val DISCONNECTED = 2

    /** 系统回连的宽限期：短了会误报，长了用户盯着"正在连接"干等。 */
    private const val GRACE_MS = 20_000L

    @Volatile
    private var graceStartedAt = android.os.SystemClock.elapsedRealtime()

    @Volatile
    var status: Int = UNKNOWN

    @Volatile
    var lastEventAt: Long = 0L

    /** 严格意义：系统已经确认连上。 */
    val connected: Boolean get() = status == CONNECTED

    /** 刚启动或刚发起重连，系统还在回连：显示"正在连接"，不报警。 */
    fun connecting(): Boolean =
        status == UNKNOWN && android.os.SystemClock.elapsedRealtime() - graceStartedAt < GRACE_MS

    /** 授权还在、但确实没连上：这才是需要提示和自救的状态。 */
    fun trouble(context: Context): Boolean = isEnabled(context) && !connected && !connecting()

    /** 系统回调断开：先按"正在重连"处理，别立刻闪一个"已断开"。 */
    fun markDisconnected() {
        status = UNKNOWN
        graceStartedAt = android.os.SystemClock.elapsedRealtime()
    }

    /** 向系统请求重新绑定监听服务；授权还在时通常能直接救回来。 */
    fun reconnect(context: Context) {
        // 重置宽限，让界面显示"正在连接"而不是立刻报"已断开"。
        status = UNKNOWN
        graceStartedAt = android.os.SystemClock.elapsedRealtime()
        try {
            NotificationListenerService.requestRebind(
                ComponentName(context, NotificationWatchService::class.java),
            )
            Prefs.setLastListenerRebindAt(context, System.currentTimeMillis())
        } catch (_: Exception) {
        }
    }

    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}

/** 每日 21:00 税单日报 + 免打扰自动恢复，两条闹钟共用一个接收器。 */
class DigestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DIGEST -> postDigestNow(context, force = false)
            ACTION_RESTORE_DND -> Silence.restore(context)
        }
    }

    companion object {
        const val ACTION_DIGEST = "dev.quietledger.app.DIGEST"
        const val ACTION_RESTORE_DND = "dev.quietledger.app.RESTORE_DND"
    }
}

object Digest {
    private const val REQ_DIGEST = 21

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_DIGEST,
            Intent(context, DigestReceiver::class.java).setAction(DigestReceiver.ACTION_DIGEST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 用户改过时间后重新排期：先取消旧闹钟再按新时间设置。
        am.cancel(pi)
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextTrigger(context),
            AlarmManager.INTERVAL_DAY,
            pi,
        )
    }

    fun nextTrigger(context: Context): Long {
        val hour = Prefs.digestHour(context)
        val minute = Prefs.digestMinute(context)
        val now = ZonedDateTime.now(Fiscal.ZONE)
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }
}

/**
 * 发一条日报（手动测试 force=true 时不受「日报开关」限制）。
 *
 * v1.8.0：日报与票据同一份数据源——同一天且服务器条数不落后、确实已结算时才用服务器票面，
 * 否则用本机账本并标「本机」。两处数字因此不会互相打脸（审计 P1-8）。
 */
fun postDigestNow(context: Context, force: Boolean): Boolean {
    if (!force && !Prefs.digestEnabled(context)) return false
    if (!Notifications.canPost(context)) return false
    val db = LedgerDb.get(context)
    val today = Fiscal.today()
    val iso = Fiscal.iso(today)
    val events = db.eventsBetween(Fiscal.dayStart(today), Fiscal.dayEnd(today))
    val tariff = SyncManager.cachedTariff(context)
    val local = Duty.compute(
        events, tariff, today, today,
        Prefs.accountCode(context) ?: "未开户",
    )
    // 与 AppState.settledByServer 同一口径：同步关闭 / 有本机未上送 / 上次出错 / 税则没提交都不用服务器。
    val cached = if (Prefs.syncEnabled(context)) SyncManager.cachedInvoice(context) else null
    val useServer = cached != null &&
        cached.date == iso &&
        cached.events >= local.events &&
        db.pendingCount() == 0 &&
        Prefs.lastError(context) == null &&
        !Prefs.taxPending(context)
    val invoice = if (useServer) cached ?: local else local
    return try {
        Notifications.postDigest(context, invoice, if (useServer) "已结算" else "本机")
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * 5.4 免税额用尽提醒：当天第一次从「还有结余」跨到「已经欠税」时提醒一次。
 *
 * 只读本机账本，不联网、不改任何状态之外的逻辑；用 `budget_notified_day` 做当天去重，
 * 所以入账再多也只响一次，跨天自然重新计数。
 */
fun notifyBudgetIfNeeded(context: Context) {
    try {
        if (!Prefs.budgetAlertOn(context)) return
        val today = Fiscal.today()
        val iso = Fiscal.iso(today)
        // 当天已经提醒过（或提醒被关掉）就不再算账：这是「不每 4 秒重复发」的唯一闸门。
        if (Prefs.budgetNotifiedDay(context) == iso) return
        val db = LedgerDb.get(context)
        val events = db.eventsBetween(Fiscal.dayStart(today), Fiscal.dayEnd(today))
        val invoice = Duty.compute(
            events, SyncManager.cachedTariff(context), today, today,
            Prefs.accountCode(context) ?: "未开户",
        )
        if (!invoice.isOwed) return
        // 没给通知权限就不算「今天提醒过」：授权后再入账还能提醒一次。
        if (!Notifications.canPost(context)) return
        // 先记日期再发：发完当天不再重算，跨天自然重新计数。
        Prefs.setBudgetNotifiedDay(context, iso)
        Notifications.postBudgetAlert(context, -invoice.balance)
    } catch (_: Exception) {
    }
}

object Notifications {
    const val CH_DIGEST = "digest"
    const val ID_DIGEST = 1001

    /** 免税额提醒单独一个 id：不能覆盖掉当天的日报。渠道复用日报渠道，不新建。 */
    const val ID_BUDGET = 1002

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_DIGEST, "每日税单", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    // 时间由 App 页面负责：用户改过日报时间后，这里不能写死 21:00。
                    description = "每天推送当日税单"
                    enableVibration(false)
                },
            )
        }
    }

    /** 33+ 通知权限没给就不发，也不提示「已发送」，避免假成功。 */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 日报文案唯一出口：通知与设置页预览共用，避免两处数字/来源漂移。
     * 返回 (标题, 正文一句话)；来源标在标题尾（`已结算` / `本机`），不用点分隔。
     */
    fun buildDigest(invoice: Invoice, source: String): Pair<String, String> {
        val day = if (invoice.date.length >= 10) invoice.date.substring(5) else invoice.date
        val title = "打扰税日报 $day  $source".trim()
        val body = "今天应缴 ${invoice.totalDuty} 秒，${invoice.balanceParts().shortText}。"
        return title to body
    }

    /** 日报展开后的应用行：`Clock 1 条，12 秒`；没有记录时给一句话。 */
    fun digestLines(invoice: Invoice): List<String> {
        val lines = invoice.lines.take(3).map { "${it.app} ${it.count} 条，${it.duty} 秒" }
        return lines.ifEmpty { listOf("今天还没有打扰记录。") }
    }

    fun postDigest(context: Context, invoice: Invoice, source: String = "本机") {
        ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val open = openAppIntent(context, requestCode = 0)
        val (title, body) = buildDigest(invoice, source)
        val big = (listOf(body) + digestLines(invoice)).joinToString("\n")
        val n = builder(context)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(ID_DIGEST, n)
    }

    /** 免税额用完：当天一次，点开进 App 看票据。 */
    fun postBudgetAlert(context: Context, owedSeconds: Int) {
        ensureChannels(context)
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = builder(context)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("今天的免税额用完了")
            .setContentText("已欠 ${owedSeconds.coerceAtLeast(1)} 秒。")
            .setContentIntent(openAppIntent(context, requestCode = 1))
            .setAutoCancel(true)
            .build()
        nm.notify(ID_BUDGET, n)
    }

    private fun builder(context: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CH_DIGEST)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** 免打扰控制：需要用户在系统设置里授予「通知策略访问权限」。 */
object Silence {
    private const val REQ_RESTORE = 22
    private const val ACTION_RESTORE = DigestReceiver.ACTION_RESTORE_DND

    fun hasAccess(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    fun isSilenced(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    fun silenceFor(context: Context, durationMs: Long): Boolean {
        if (!hasAccess(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val until = System.currentTimeMillis() + durationMs
        Prefs.setDndUntil(context, until)
        scheduleRestore(context, until)
        return true
    }

    fun restore(context: Context) {
        Prefs.setDndUntil(context, 0L)
        if (!hasAccess(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    private fun scheduleRestore(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, REQ_RESTORE,
            Intent(context, DigestReceiver::class.java).setAction(ACTION_RESTORE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.set(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class SilenceTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        // 磁贴刚被拖进快捷设置：主动要求系统回调一次 onStartListening，
        // 否则要等用户下一次下拉才显示当前状态。
        try {
            TileService.requestListeningState(this, ComponentName(this, SilenceTileService::class.java))
        } catch (_: Exception) {
        }
        refresh()
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (!Silence.hasAccess(this)) {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("open", "dnd")
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(
                        PendingIntent.getActivity(
                            this, 0, i,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(i)
                }
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
            return
        }
        if (Silence.isSilenced(this)) Silence.restore(this) else Silence.silenceFor(this, 60 * 60 * 1000L)
        refresh()
    }

    /**
     * 磁贴面板只显示很短的标题，label 就用「静默」两个字；
     * 完整说明放 subtitle（API 29+）与 stateDescription（API 30+）。
     */
    private fun refresh() {
        val tile = qsTile ?: return
        val silenced = Silence.isSilenced(this)
        // 快捷设置里的磁贴必须有图标，否则显示空白格/首字母。
        // ic_tile 是单色白收据（内部横线用 evenOdd 镂空），系统会按状态染色。
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.state = if (silenced) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "静默"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (silenced) "静默中 · 点击恢复" else "点击静默 1 小时"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            tile.stateDescription = if (silenced) "静默中，点击恢复" else "未静默，点击静默 1 小时"
        }
        tile.updateTile()
    }
}

class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val ctx = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SyncManager.sync(ctx)
            } catch (_: Exception) {
            }
            // 监听授权还在但绑定已经断了（ROM 省电清理的常见后果）：顺手重连一次。
            try {
                if (WatchState.trouble(ctx)) WatchState.reconnect(ctx)
            } catch (_: Exception) {
            }
            InvoiceWidgetProvider.update(ctx)
            withContext(Dispatchers.Main) { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Digest.schedule(context)
        SyncScheduler.ensurePeriodic(context)
        InvoiceWidgetProvider.update(context)
        val until = Prefs.dndUntil(context)
        if (until > 0 && until <= System.currentTimeMillis()) Silence.restore(context)
    }
}
