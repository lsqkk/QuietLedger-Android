package dev.quietledger.app.ui

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.quietledger.app.BuildConfig
import dev.quietledger.app.core.AppTrend
import dev.quietledger.app.core.ConversationClass
import dev.quietledger.app.core.ConversationOverview
import dev.quietledger.app.core.ConversationSource
import dev.quietledger.app.core.Duty
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.core.Insights
import dev.quietledger.app.core.Invoice
import dev.quietledger.app.core.StreakInfo
import dev.quietledger.app.core.balanceParts
import dev.quietledger.app.core.NotifEvent
import dev.quietledger.app.core.Report
import dev.quietledger.app.core.ReportKind
import dev.quietledger.app.core.Skipped
import dev.quietledger.app.core.Tariff
import dev.quietledger.app.data.LedgerDb
import dev.quietledger.app.data.AppNames
import dev.quietledger.app.data.Prefs
import dev.quietledger.app.net.Api
import dev.quietledger.app.net.AppUpdate
import dev.quietledger.app.net.Json
import dev.quietledger.app.service.Digest
import dev.quietledger.app.service.Notifications
import dev.quietledger.app.service.Silence
import dev.quietledger.app.service.WatchState
import dev.quietledger.app.service.postDigestNow
import dev.quietledger.app.sync.SyncManager
import dev.quietledger.app.sync.SyncScheduler
import dev.quietledger.app.widget.InvoiceWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

enum class Tab(val zh: String, val en: String, val glyph: GlyphKind) {
    INVOICE("票据", "INVOICE", GlyphKind.INVOICE),
    LEDGER("明细", "LEDGER", GlyphKind.LIST),
    WEEKLY("报表", "REPORT", GlyphKind.CHART),
    TARIFF("设置", "SETTINGS", GlyphKind.SLIDERS),
}

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, DOWNLOADED, ERROR }

/** 报表周期参数：kind=week 时 days 生效（7/30 天），其余为自然月/季/年。 */
data class ReportMode(val kind: ReportKind, val days: Int)

/** 官网地址：分享/复制文案与「到官网看看」共用。 */
private const val WEBSITE = "https://ql.130923.xyz"

/** v1.5.0 写在偏好里的旧错误文案带「服务器」，升级后不再显示给用户。 */
private fun migrateErrorText(msg: String?): String? = msg?.replace("服务器结算失败", "同步失败")

class MainActivity : ComponentActivity() {

    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 真 edge-to-edge：状态栏/手势条透明，纸底铺到屏幕边缘，图标明暗跟随主题。
        // 内容避让由根布局的 WindowInsets.safeDrawing 统一处理（含 systemBars / displayCutout / ime）。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { QL.IsDark },
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { QL.IsDark },
        )
        QL.apply(Prefs.themeId(this))
        applyWindowTheme()
        Notifications.ensureChannels(this)
        Digest.schedule(this)
        SyncScheduler.ensurePeriodic(this)
        // 旋转 / 进程重建后恢复选中 Tab（位置由 PagerState 自己恢复）。
        state = AppState(
            applicationContext,
            lifecycleScope,
            this,
            savedInstanceState?.getInt(KEY_TAB, Tab.INVOICE.ordinal) ?: Tab.INVOICE.ordinal,
        )
        if (intent?.getStringExtra("open") == "dnd") state.openDndSettings()
        setContent { AppRoot(state, onExit = { finish() }) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_TAB, state.tab.ordinal)
        super.onSaveInstanceState(outState)
    }

    private fun applyWindowTheme() {
        window.setBackgroundDrawable(ColorDrawable(QL.Paper.toArgb()))
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !QL.IsDark
        controller.isAppearanceLightNavigationBars = !QL.IsDark
    }

    override fun onResume() {
        super.onResume()
        state.refresh()
        state.autoSync()
        state.maybeAutoCheckUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("open") == "dnd") state.openDndSettings()
    }

    companion object {
        /** 旋转 / 进程重建恢复的选中 Tab（每页滚动位置由 Pager 的 saveable 容器保存）。 */
        private const val KEY_TAB = "ql.tab"
    }
}

@Composable
fun AppRoot(state: AppState, onExit: () -> Unit) {
    LaunchedEffect(state.tab) {
        if (state.tab == Tab.LEDGER) state.refresh()
    }
    LaunchedEffect(state.tab, state.reportRange, state.reportAnchor, state.today, state.accountCode, state.reportRefresh) {
        if (state.tab == Tab.WEEKLY) {
            while (true) {
                state.loadReport()
                delay(60_000)
            }
        }
    }
    // 前台每 4 秒刷新一次本地账本；有待同步 / 服务器票面落后 / 税则待提交时自动触发结算，
    // 票据随打扰实时更新。
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(4_000)
            state.refresh()
            state.ensureListener()
            val behind = state.pending > 0 || state.serverBehind || state.taxPending
            if (!state.syncing && behind &&
                System.currentTimeMillis() - state.lastSyncAt > 15_000L
            ) {
                state.autoSync()
            }
        }
    }

    // Tab 的唯一真相是 state.tab；Pager 只负责「怎么到那一页」。
    // initialPage 用恢复出来的选中项，旋转/进程重建不会先闪一下票据页。
    val pagerState = rememberPagerState(initialPage = state.tab.ordinal) { Tab.entries.size }

    // Tab 点击或外部请求切页 → 程序化滚动；指示器由 pager 偏移连续插值，不等停稳。
    LaunchedEffect(state.tab) {
        val target = state.tab.ordinal.coerceIn(0, Tab.entries.lastIndex)
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    // 手指横滑停稳 → 把选中态回写；这是滑动与 state.tab 的唯一同步点。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val tab = Tab.entries[page.coerceIn(0, Tab.entries.lastIndex)]
            if (state.tab != tab) state.tab = tab
        }
    }

    Box(Modifier.fillMaxSize()) {
        LedgerPaper {
            // 引导 ↔ 主界面：其余页面统一走方向转场（进主界面 = 前进，回看引导 = 返回），
            // 参数只在 ui/Transitions.kt 的 NavMotion 里，页面不各写一套。
            AnimatedContent(
                targetState = state.showGuide,
                transitionSpec = {
                    if (targetState) NavMotion.backward() else NavMotion.forward()
                },
                label = "ql-guide",
            ) { guide ->
                if (guide) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        OnboardingScreen(state)
                    }
                } else {
                    // 系统手势返回：手势过程中整块页面跟手做 scale / translate / 临时圆角，
                    // 松手完成 → 退出 App；取消 → spring 回弹（见 PredictiveBackContainer）。
                    PredictiveBackContainer(
                        enabled = true,
                        onBackCompleted = onExit,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // 四个 Tab 页常驻在 Pager 里：滚动位置由各自的 saveable 状态独立保存。
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                // 保留相邻页：横滑更跟手，切回时滚动位置也不丢。
                                beyondViewportPageCount = 1,
                                key = { Tab.entries[it].name },
                            ) { page ->
                                when (Tab.entries[page]) {
                                    Tab.INVOICE -> InvoiceScreen(state)
                                    Tab.LEDGER -> LedgerScreen(state)
                                    Tab.WEEKLY -> ReportScreen(state)
                                    Tab.TARIFF -> SettingsScreen(state)
                                }
                            }
                            BottomTabs(pagerState = pagerState, current = state.tab) { state.tab = it }
                        }
                    }
                }
            }
        }
        if (state.showShare) ShareSheet(state)
        if (state.showResetConfirm) ResetConfirmSheet(state)
        if (state.showListenerFix) ListenerFixSheet(state)
        if (state.showAudit) NoticeAuditSheet(state)
        if (state.classifyingSource == null) {
            state.selectedEvent?.let { EntryActionSheet(state, it) }
        }
        state.classifyingSource?.let { ConversationClassSheet(state, it) }
    }
}

/**
 * 页面状态与动作。数据先本地记账，联网后交给服务器结算；
 * 断网、超时、服务端错误都会回落到本地估算并在界面上标出来源。
 */
class AppState(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val activity: Activity,
    initialTabOrdinal: Int = 0,
) {
    /** 当前选中 Tab；旋转/进程重建由 Activity 的 onSaveInstanceState 恢复。 */
    var tab by mutableStateOf(Tab.entries.getOrElse(initialTabOrdinal) { Tab.INVOICE })
    var today by mutableStateOf(Fiscal.today())
    var tariff by mutableStateOf(SyncManager.cachedTariff(context))
    var serverInvoice by mutableStateOf<Invoice?>(null)
    var localInvoice by mutableStateOf<Invoice?>(null)

    // -- 报表（周 / 月 / 季 / 年） -------------------------------------------
    private var reportLocalState by mutableStateOf<Report?>(null)
    private var reportServerState by mutableStateOf<Report?>(null)
    var reportRefresh by mutableStateOf(0)
        private set
    /** 上次停留的周期：d7 / d30 / month / quarter / year。 */
    var reportRange by mutableStateOf(Prefs.reportRange(context))
    /** 月报下钻的历史月份锚点（ISO 日期，空串=今天）。 */
    var reportAnchor by mutableStateOf(Prefs.reportAnchor(context))
    var reportLoading by mutableStateOf(false)
    val reportMode: ReportMode
        get() = when (reportRange) {
            "d30" -> ReportMode(ReportKind.WEEK, 30)
            "month" -> ReportMode(ReportKind.MONTH, 7)
            "quarter" -> ReportMode(ReportKind.QUARTER, 7)
            "year" -> ReportMode(ReportKind.YEAR, 7)
            else -> ReportMode(ReportKind.WEEK, 7)
        }
    /** 报表截止日：普通情况是今天；从季/年点进某个月后是那个月的最后一天。 */
    val reportEnd: LocalDate
        get() = reportAnchor.takeIf { it.isNotBlank() }
            ?.let { runCatching { Fiscal.parseDate(it) }.getOrNull() }
            ?: Fiscal.today()
    /**
     * 服务器包含账户内其它设备；只有服务器每个桶的条数都不落后于本机时才采信服务器，
     * 否则用本机账本并标「本机估算」——和票据/日详情同一口径，避免结算后数字停住。
     */
    val report: Report?
        get() {
            val local = reportLocalState ?: return reportServerState
            if (!syncEnabled) return local
            val server = reportServerState ?: return local
            if (taxPending) return local
            if (server.kind != local.kind || server.end != local.end ||
                server.buckets.size != local.buckets.size
            ) return local
            val serverAhead = server.buckets.zip(local.buckets).all { (remote, device) ->
                remote.events >= device.events
            }
            return if (serverAhead) server else local
        }
    var todayEvents by mutableStateOf<List<NotifEvent>>(emptyList())
    var pending by mutableStateOf(0)
    var syncing by mutableStateOf(false)
    var lastSyncAt by mutableStateOf(Prefs.lastSyncAt(context))
    var lastError by mutableStateOf(migrateErrorText(Prefs.lastError(context)))
    var accountCode by mutableStateOf(Prefs.accountCode(context))
    var deviceId by mutableStateOf(Prefs.deviceId(context))
    /** 云端同步开关（默认开）：关闭后完整本机记账，不产生任何网络请求。 */
    var syncEnabled by mutableStateOf(Prefs.syncEnabled(context))
    /** 是否上送通知原文（默认关）：只影响上送，本机摘要仍保留。 */
    var uploadTitles by mutableStateOf(Prefs.uploadTitles(context))
    var listenerOn by mutableStateOf(false)
    var listenerConnected by mutableStateOf(WatchState.connected)
    var listenerConnecting by mutableStateOf(WatchState.connecting())
    var listenerTrouble by mutableStateOf(false)
    var batteryWhitelisted by mutableStateOf(false)
    var lastEventAt by mutableStateOf(Prefs.lastEventAt(context))
    var notificationsOn by mutableStateOf(false)
    var dndAccess by mutableStateOf(false)
    var dndActive by mutableStateOf(false)
    var systemDnd by mutableStateOf(false)
    var dndUntil by mutableStateOf(Prefs.dndUntil(context))
    var digestOn by mutableStateOf(Prefs.digestEnabled(context))
    var healthState by mutableStateOf("未检测")
    var deviceCount by mutableStateOf(0)
    var joinBusy by mutableStateOf(false)
    var inviteBusy by mutableStateOf(false)
        private set
    var settingsNotice by mutableStateOf<String?>(null)
    var showGuide by mutableStateOf(!Prefs.onboarded(context))
    /** 今日已过滤条数（按北京时间当天重置）；明细「已过滤」和通知核对都用它。 */
    var filteredToday by mutableStateOf(Prefs.filteredToday(context))
    var autoMergedCount by mutableStateOf(Prefs.autoMergedCount(context))
    var nickname by mutableStateOf(Prefs.nickname(context))
    var showShare by mutableStateOf(false)
    var receiptLinkBusy by mutableStateOf(false)
        private set
    var showResetConfirm by mutableStateOf(false)
    var showListenerFix by mutableStateOf(false)
    var showAudit by mutableStateOf(false)
    var auditSkipped by mutableStateOf<List<Skipped>>(emptyList())
    var auditReasons by mutableStateOf<List<Pair<String, Int>>>(emptyList())
    var auditTodayCount by mutableStateOf(0)
    var scrollToUpdate by mutableStateOf(false)
    var profileBusy by mutableStateOf(false)
    var themeId by mutableStateOf(Prefs.themeId(context))
    var ignoredPackages by mutableStateOf(Prefs.ignoredPackages(context))
    var digestHour by mutableStateOf(Prefs.digestHour(context))
    var digestMinute by mutableStateOf(Prefs.digestMinute(context))
    var selectedEvent by mutableStateOf<NotifEvent?>(null)
    var classifyingSource by mutableStateOf<ConversationSource?>(null)
        private set
    internal var conversationOverview by mutableStateOf<ConversationOverview?>(null)
        private set
    var conversationLoadFailed by mutableStateOf(false)
        private set
    private var conversationLoad = 0
    var savingConversationRule by mutableStateOf(false)
        private set

    fun loadConversationRules() {
        if (savingConversationRule) return
        val request = ++conversationLoad
        conversationLoadFailed = false
        scope.launch {
            try {
                val overview = withContext(Dispatchers.IO) { LedgerDb.get(context).conversationOverview() }
                if (request == conversationLoad) conversationOverview = overview
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (request == conversationLoad) conversationLoadFailed = true
            }
        }
    }

    fun classifyConversation(source: ConversationSource) {
        loadConversationRules()
        classifyingSource = source
    }

    fun dismissConversationClass() {
        if (!savingConversationRule) classifyingSource = null
    }

    fun saveConversationClass(source: ConversationSource, kind: ConversationClass) {
        if (savingConversationRule) return
        conversationLoad++
        savingConversationRule = true
        scope.launch {
            try {
                conversationOverview = withContext(Dispatchers.IO) {
                    val db = LedgerDb.get(context)
                    db.setConversationRule(source, kind)
                    db.conversationOverview()
                }
                classifyingSource = null
                toast("已保存，下条消息生效")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                toast("保存失败，请重试")
            } finally {
                savingConversationRule = false
            }
        }
    }
    var updatePhase by mutableStateOf(UpdatePhase.IDLE)
    var latestUpdate by mutableStateOf<AppUpdate?>(null)
    var updateProgress by mutableStateOf(0)
    var updateMessage by mutableStateOf<String?>(null)
    var autoUpdateWifi by mutableStateOf(Prefs.autoUpdateOnWifi(context))

    // -- 计税规则（单价 / 免税额）相关状态 ---------------------------------
    // 有 pending 时以本机待提交值为准，否则跟随当前生效的 tariff。
    var taxUnit by mutableStateOf(
        if (Prefs.taxPending(context)) Prefs.pendingTaxUnit(context) else tariff.unitSeconds,
    )
    var taxExemption by mutableStateOf(
        if (Prefs.taxPending(context)) Prefs.pendingTaxExemption(context) else tariff.exemptionPerDay,
    )
    var taxPending by mutableStateOf(Prefs.taxPending(context))

    /** 从票据/结算页跳「计税规则」时置位，设置页消费后直接进二级页。 */
    var settingsTaxRequested by mutableStateOf(false)

    /** 「钉到桌面」的结果说明：无论成功、不支持还是没反应都展示，不再只弹一个 toast。 */
    var showPinGuide by mutableStateOf(false)
    var pinGuideText by mutableStateOf("")

    // -- v1.8.0：静默番茄钟 / 应用趋势 / 连续达标 / 免税额提醒 -----------------
    /** 静默时长选择弹层（25 / 45 / 60 分钟）。 */
    var silenceSheet by mutableStateOf(false)
    /** 免税额用尽提醒开关（默认开）。 */
    var budgetAlertOn by mutableStateOf(Prefs.budgetAlertOn(context))
    /** 近 30 天连续达标，票据页小日历与桌面组件共用一份口径。 */
    var streakInfo by mutableStateOf<StreakInfo?>(null)
    /** 应用趋势弹层：pkg=null 表示关闭。 */
    var trendPkg by mutableStateOf<String?>(null)
    /** 应用趋势窗口：1（今天）/ 7 / 30 天。 */
    var trendDays by mutableStateOf(7)
    /** 24 小时下钻弹层：null 表示关闭。 */
    var hourSheetHour by mutableStateOf<Int?>(null)

    /** 本次前台会话里主动重连了几次，用来做退避。 */
    private var rebindAttempts = 0

    /** 上一次刷新时是否处于"监听断开"，用来在恢复时给一次提示。 */
    private var wasListenerTrouble = false

    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val dndRemainingMin: Int
        get() {
            if (dndUntil <= 0) return 0
            val remain = dndUntil - System.currentTimeMillis()
            return if (remain <= 0) 0 else (remain / 60_000L).toInt() + 1
        }

    /**
     * 票据取「更新的一方」：
     * 本机账本是实时的（每收到一条通知就 +1），服务器结算包含账户内其它设备。
     *
     * v1.2 的坑：只要 pending==0 就展示服务器票据。而后台上送（SyncJobService）
     * 会在界面不知情的情况下把 pending 清零，界面于是一直显示「点结算那一刻」的
     * 旧快照 —— 用户看到的就是"结算完当天不再统计"。现在只有当服务器条数不落后于
     * 本机条数时才用服务器票面，否则用本机实时账本并在界面上标出来。
     */
    val invoice: Invoice
        get() {
            val iso = Fiscal.iso(Fiscal.today())
            val srv = serverInvoice
            val local = localInvoice
            return when {
                srv != null && settledByServer -> srv
                local != null && local.date == iso -> local
                srv != null && srv.date == iso -> srv
                else -> Invoice.empty(iso, accountCode ?: "未开户", tariff)
            }
        }

    val settledByServer: Boolean
        get() {
            // 云端同步关掉后票据一律按本机估算，不吃上一次的同步快照。
            if (!syncEnabled) return false
            val iso = Fiscal.iso(Fiscal.today())
            val srv = serverInvoice ?: return false
            return srv.date == iso && pending == 0 && !taxPending && lastError == null &&
                srv.events >= (localInvoice?.takeIf { it.date == iso }?.events ?: 0)
        }

    /** 本机今日已记账条数（含还没上送的）。 */
    val localTodayEvents: Int get() = localInvoice?.events ?: 0

    /** 服务器今日结算到的条数（跨设备合并）；没有今日票据时是 0。 */
    val serverTodayEvents: Int
        get() = if (serverInvoice?.date == Fiscal.iso(Fiscal.today())) serverInvoice?.events ?: 0 else 0

    /** 服务器票面落后于本机账本（还有本机记录没被服务器算进去）。 */
    val serverBehind: Boolean
        get() {
            val srv = serverInvoice ?: return false
            return srv.date == Fiscal.iso(Fiscal.today()) && srv.events < localTodayEvents
        }

    private data class Panel(
        val today: LocalDate,
        val tariff: Tariff,
        val invoice: Invoice,
        val serverCached: Invoice?,
        val events: List<NotifEvent>,
        val pending: Int,
        val accountCode: String?,
        val lastSyncAt: Long,
        val lastError: String?,
        val listenerOn: Boolean,
        val listenerConnected: Boolean,
        val listenerConnecting: Boolean,
        val listenerTrouble: Boolean,
        val batteryWhitelisted: Boolean,
        val lastEventAt: Long,
        val notificationsOn: Boolean,
        val dndAccess: Boolean,
        val dndActive: Boolean,
        val systemDnd: Boolean,
        val digestOn: Boolean,
        val deviceId: String,
        val filteredToday: Int,
        val autoMergedCount: Int,
        val nickname: String,
        val dndUntil: Long,
        val taxPending: Boolean,
        val streak: StreakInfo,
    )

    private fun loadLocal(): Panel {
        val db = LedgerDb.get(context)
        val day = Fiscal.today()
        val acc = Prefs.accountCode(context)
        val tariff = SyncManager.cachedTariff(context)
        val start = Fiscal.dayStart(day)
        val end = Fiscal.dayEnd(day)
        val events = db.eventsBetween(start, end)
        val local = Duty.compute(events, tariff, day, day, acc ?: "未开户")
        val notificationsOn = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val listenerOn = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val dndUntil = Prefs.dndUntil(context)
        val dndOurs = dndUntil > System.currentTimeMillis()
        val systemDnd = Silence.isSilenced(context) && !dndOurs
        // 近 30 天账本一次取出，逐日复用 Duty.compute 算连续达标（与票据/报表同口径）。
        val streakEvents = db.eventsBetween(Fiscal.dayStart(day.minusDays(29)), end)
        val streak = Insights.streak(streakEvents, tariff, day, 30)
        return Panel(
            today = day,
            tariff = tariff,
            invoice = local,
            serverCached = SyncManager.cachedInvoice(context),
            events = events,
            pending = db.pendingCount(),
            accountCode = acc,
            lastSyncAt = Prefs.lastSyncAt(context),
            lastError = migrateErrorText(Prefs.lastError(context)),
            listenerOn = listenerOn,
            listenerConnected = WatchState.connected,
            listenerConnecting = WatchState.connecting(),
            listenerTrouble = listenerOn && WatchState.trouble(context),
            batteryWhitelisted = batteryWhitelisted(context),
            lastEventAt = Prefs.lastEventAt(context),
            notificationsOn = notificationsOn,
            dndAccess = Silence.hasAccess(context),
            dndActive = dndOurs,
            systemDnd = systemDnd,
            digestOn = Prefs.digestEnabled(context),
            deviceId = Prefs.deviceId(context),
            filteredToday = Prefs.filteredToday(context),
            autoMergedCount = Prefs.autoMergedCount(context),
            nickname = Prefs.nickname(context),
            dndUntil = dndUntil,
            taxPending = Prefs.taxPending(context),
            streak = streak,
        )
    }

    private fun apply(p: Panel) {
        if (accountCode != p.accountCode || today != p.today) {
            reportLocalState = null
            reportServerState = null
        }
        today = p.today
        tariff = p.tariff
        localInvoice = p.invoice
        todayEvents = p.events
        pending = p.pending
        accountCode = p.accountCode
        lastSyncAt = p.lastSyncAt
        lastError = p.lastError
        listenerOn = p.listenerOn
        listenerConnected = p.listenerConnected
        listenerConnecting = p.listenerConnecting
        listenerTrouble = p.listenerTrouble
        if (p.listenerConnected && wasListenerTrouble && p.listenerOn) {
            toast("通知监听已恢复")
            rebindAttempts = 0
            settingsNotice = "通知监听已恢复"
        }
        wasListenerTrouble = p.listenerTrouble
        batteryWhitelisted = p.batteryWhitelisted
        lastEventAt = p.lastEventAt
        notificationsOn = p.notificationsOn
        dndAccess = p.dndAccess
        dndActive = p.dndActive
        systemDnd = p.systemDnd
        digestOn = p.digestOn
        deviceId = p.deviceId
        filteredToday = p.filteredToday
        autoMergedCount = p.autoMergedCount
        nickname = p.nickname
        dndUntil = p.dndUntil
        streakInfo = p.streak
        // 税则提交成功后（SyncManager 清 pending），编辑值回到服务器返回的生效值。
        // 刚点过 +/− 时本地 taxPending 已是 true，别被一次更早发起的刷新覆盖回去。
        val pendingTax = p.taxPending
        if (!pendingTax && !taxPending) {
            taxUnit = p.tariff.unitSeconds
            taxExemption = p.tariff.exemptionPerDay
        }
        taxPending = pendingTax
        // 后台同步也会写这份缓存：每 4 秒把它读回来，票据才不会停在上一次手动结算那一刻。
        val cached = p.serverCached
        if (cached != null && cached.date == Fiscal.iso(p.today)) serverInvoice = cached
    }

    fun refresh() {
        scope.launch {
            val p = withContext(Dispatchers.IO) { loadLocal() }
            apply(p)
        }
    }

    fun autoSync() {
        // 云端同步关闭时完全不发网络请求，手动同步另外给提示。
        if (!Prefs.syncEnabled(context)) return
        if (System.currentTimeMillis() - Prefs.lastSyncAt(context) > 20_000L) sync(manual = false)
    }

    /**
     * 同步一次。manual=true 是用户主动点的：同步关闭时给一次明确提示，不算错误；
     * 自动补送、删除/忽略后的重算传 manual=false，避免连续弹提示。
     */
    fun sync(manual: Boolean = true) {
        if (syncing) return
        if (!Prefs.syncEnabled(context)) {
            syncEnabled = false
            settingsNotice = "同步已关闭，仅本机记账"
            if (manual) toast("同步已关闭，仅本机记账")
            refresh()
            return
        }
        scope.launch {
            syncing = true
            settingsNotice = null
            val result = withContext(Dispatchers.IO) { SyncManager.sync(context) }
            syncing = false
            if (result.ok) {
                serverInvoice = result.invoice
                result.tariff?.let { tariff = it }
                lastError = null
                lastSyncAt = System.currentTimeMillis()
                val acc = Prefs.accountCode(context)
                if (acc != null) {
                    val token = Prefs.token(context)
                    if (token != null) {
                        val devs = withContext(Dispatchers.IO) {
                            runCatching { Api.devices(token, deviceId) }.getOrNull()
                        }
                        deviceCount = devs?.size ?: deviceCount
                    }
                }
                val pushedTip = if (result.pushed > 0) "已上送 ${result.pushed} 条，结算完成" else "结算完成"
                settingsNotice = pushedTip
                // 结算提示只是即时反馈，几秒后自动消失，不要一直挂在设置页上。
                scope.launch {
                    delay(5_000)
                    if (settingsNotice == pushedTip) settingsNotice = null
                }
            } else {
                lastError = result.message
                result.invoice?.let { serverInvoice = it }
            }
            val p = withContext(Dispatchers.IO) { loadLocal() }
            apply(p)
            if (tab == Tab.WEEKLY) loadReport()
            withContext(Dispatchers.IO) { InvoiceWidgetProvider.update(context) }
        }
    }

    /** 本机报表需要的原始事件：覆盖「本周期 + 等长上一周期」，换锚点/周期时按需重查。 */
    private fun reportEvents(end: LocalDate, mode: ReportMode): List<NotifEvent> {
        val start = Duty.reportStart(mode.kind, end, mode.days)
        val dayCount = (end.toEpochDay() - start.toEpochDay() + 1).toInt().coerceAtLeast(1)
        val prevStart = start.minusDays(dayCount.toLong())
        return LedgerDb.get(context).eventsBetween(
            Fiscal.dayStart(prevStart), Fiscal.dayEnd(end),
        )
    }

    private fun localReport(
        end: LocalDate,
        mode: ReportMode,
        rules: Tariff,
        account: String?,
    ): Report = Duty.reportLocal(
        reportEvents(end, mode),
        rules,
        mode.kind,
        end,
        mode.days,
        account ?: "未开户",
    )

    /**
     * 加载当前报表：本机先算（断网/关同步也能看），有账户再向服务器要跨设备汇总。
     * 两者按桶条数取多的一方，界面标「已结算 / 本机估算」。
     */
    suspend fun loadReport() {
        val mode = reportMode
        val end = reportEnd
        val account = accountCode
        val rules = tariff
        val token = Prefs.token(context)
        val syncOn = Prefs.syncEnabled(context)
        val isoEnd = Fiscal.iso(end)
        reportLoading = true
        try {
            val cached = reportLocalState
            val expectedStart = Fiscal.iso(Duty.reportStart(mode.kind, end, mode.days))
            // days 相同 kind 也要比 start：7 天和 30 天的 end 都是今天，不能复用上一份。
            if (cached == null || cached.kind != mode.kind.key ||
                cached.end != isoEnd || cached.start != expectedStart
            ) {
                reportLocalState = withContext(Dispatchers.IO) {
                    localReport(end, mode, rules, account)
                }
            }
            // 云端同步关闭时报表只按本机账本算，不发网络。
            reportServerState = if (token == null || !syncOn) null else try {
                Api.report(token, deviceId, mode.kind.key, isoEnd, mode.days)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } finally {
            reportLoading = false
        }
    }

    fun refreshReport() {
        reportRefresh++
    }

    /** 切换周期（d7/d30/month/quarter/year）；退出月报时清掉历史月份锚点。 */
    fun changeReportRange(range: String) {
        if (reportRange == range && reportAnchor.isBlank()) return
        reportRange = range
        Prefs.setReportRange(context, range)
        if (range != "month") {
            reportAnchor = ""
            Prefs.setReportAnchor(context, "")
        }
        reportLocalState = null
        reportServerState = null
        loadReportNow()
    }

    /** 从季/年柱子点进某个月：锚到该月最后一天（不超过今天）。 */
    fun openMonthReport(monthIso: String) {
        val month = runCatching { Fiscal.parseDate(monthIso) }.getOrNull() ?: return
        val last = month.withDayOfMonth(month.lengthOfMonth())
        val end = if (last.isAfter(Fiscal.today())) Fiscal.today() else last
        reportRange = "month"
        reportAnchor = Fiscal.iso(end)
        Prefs.setReportRange(context, "month")
        Prefs.setReportAnchor(context, reportAnchor)
        reportLocalState = null
        reportServerState = null
        loadReportNow()
    }

    private fun reportStartOfMonth(day: LocalDate): LocalDate = day.withDayOfMonth(1)

    /** 回到「本月/今天」：清掉历史月份锚点。 */
    fun backToCurrentReport() {
        if (reportAnchor.isBlank()) return
        reportAnchor = ""
        Prefs.setReportAnchor(context, "")
        reportLocalState = null
        reportServerState = null
        loadReportNow()
    }

    private fun loadReportNow() {
        scope.launch { loadReport() }
    }

    fun copyAccountInvite() {
        if (!syncEnabled || inviteBusy) return
        val token = Prefs.token(context) ?: return
        inviteBusy = true
        scope.launch {
            try {
                val invite = Api.issueInvite(token, deviceId)
                if (Prefs.token(context) == token) copyText(invite, "邀请码", sensitive = true)
            } catch (e: java.io.IOException) {
                toast("无法生成邀请码，请联网后重试")
            } finally {
                inviteBusy = false
            }
        }
    }

    fun joinAccount(code: String) {
        if (code.isBlank() || joinBusy) return
        if (!Prefs.syncEnabled(context)) {
            settingsNotice = "先开启云端同步"
            toast("先开启云端同步，再输入邀请码")
            return
        }
        joinBusy = true
        scope.launch {
            val r = SyncManager.join(context, code)
            joinBusy = false
            settingsNotice = r.message
            if (r.ok) {
                serverInvoice = r.invoice
                r.tariff?.let { tariff = it }
                lastSyncAt = System.currentTimeMillis()
            }
            apply(withContext(Dispatchers.IO) { loadLocal() })
            withContext(Dispatchers.IO) { InvoiceWidgetProvider.update(context) }
        }
    }

    fun checkHealth() {
        scope.launch {
            healthState = "检测中…"
            val h = withContext(Dispatchers.IO) { runCatching { Api.health() }.getOrNull() }
            healthState = if (h != null && h.ok) "在线 · ${h.version} · ${h.time}" else "不可达"
        }
    }

    /** 云端同步开关：写 Prefs 并实时生效；关闭立即取消周期任务，开启补排一次同步。 */
    fun changeSyncEnabled(on: Boolean) {
        syncEnabled = on
        Prefs.setSyncEnabled(context, on)
        // 关闭时 ensurePeriodic 内部会取消周期/单次任务，开启时补排。
        SyncScheduler.ensurePeriodic(context)
        settingsNotice = if (on) "已开启云端同步" else "同步已关闭，仅本机记账"
        if (on) sync(manual = false) else refresh()
    }

    /** 上送通知原文：默认关；关闭时只上送应用、时间、条数。 */
    fun changeUploadTitles(on: Boolean) {
        uploadTitles = on
        Prefs.setUploadTitles(context, on)
        settingsNotice = if (on) "已开启上送通知原文" else "只上送应用、时间、条数"
    }

    /** 首次引导里的昵称：只存本机（不发请求、不弹提示），联网后由下一次同步补送。 */
    fun saveNicknameQuiet(value: String) {
        val name = value.trim().take(24)
        if (name == nickname) return
        nickname = name
        Prefs.setNickname(context, name)
    }

    /**
     * 静默（v1.8.0 起是「番茄钟」入口）：点一下先选 25 / 45 / 60 分钟，必要时再结束。
     * 关键：只认「我们自己设的 dndUntil」为静默状态。系统免打扰是用户自己的设置，
     * 不能显示成"静默中"，更不能被我们的按钮关掉——那样会悄悄改掉用户的系统设置。
     */
    fun toggleSilence() {
        when {
            dndActive -> endFocus()
            systemDnd -> {
                toast("系统免打扰已开启，先在系统里关掉")
                refresh()
            }
            else -> silenceSheet = true
        }
    }

    fun dismissSilenceSheet() {
        silenceSheet = false
    }

    /** 开始静默：N 分钟只挡提醒，照常记账。 */
    fun startFocus(minutes: Int) {
        if (!Silence.hasAccess(context)) {
            openDndSettings()
            toast("需要先授予「通知策略访问权限」")
            return
        }
        if (Silence.isSilenced(context) && dndUntil <= System.currentTimeMillis()) {
            toast("系统免打扰已开启，先在系统里关掉")
            refresh()
            return
        }
        val min = minutes.coerceIn(1, 180)
        silenceSheet = false
        Silence.silenceFor(context, min * 60_000L)
        dndActive = true
        dndUntil = Prefs.dndUntil(context)
        dndAccess = Silence.hasAccess(context)
        toast("静默 $min 分钟：只挡提醒，照常记账")
        refresh()
    }

    /** 结束静默：恢复提醒，账本不受影响。 */
    fun endFocus() {
        Silence.restore(context)
        dndActive = false
        dndUntil = 0
        toast("静默结束，照常记账")
        refresh()
    }

    /** 静默剩余秒数：首页按钮每秒刷新倒计时用。 */
    val focusRemainingSec: Int
        get() {
            val remain = dndUntil - System.currentTimeMillis()
            return if (remain <= 0) 0 else (remain / 1000L).toInt()
        }

    val focusActive: Boolean
        get() = dndUntil > System.currentTimeMillis()

    fun openTrend(pkg: String) {
        trendPkg = pkg
        trendDays = 7
    }

    fun closeTrend() {
        trendPkg = null
    }

    /** 应用趋势：查近 N 天账本后按天计价（IO 线程，调用方用 LaunchedEffect）。 */
    suspend fun loadTrend(pkg: String, days: Int): AppTrend {
        val end = Fiscal.today()
        val n = days.coerceIn(1, 30)
        return withContext(Dispatchers.IO) {
            val tariffNow = SyncManager.cachedTariff(context)
            val start = Fiscal.dayStart(end.minusDays((n - 1).toLong()))
            val events = LedgerDb.get(context).eventsBetween(start, Fiscal.dayEnd(end))
            Insights.appTrend(events, tariffNow, pkg, end, n)
        }
    }

    fun openHourSheet(hour: Int) {
        hourSheetHour = hour
    }

    fun closeHourSheet() {
        hourSheetHour = null
    }

    fun setBudgetAlert(on: Boolean) {
        budgetAlertOn = on
        Prefs.setBudgetAlert(context, on)
        settingsNotice = if (on) "免税额用完会提醒一次" else "已关闭免税额提醒"
    }

    /** 日报时间快选：分钟固定 00，「税单按天」不需要半小时档。 */
    fun setDigestTime(hour: Int) {
        val h = hour.coerceIn(0, 23)
        digestHour = h
        digestMinute = 0
        Prefs.setDigestTime(context, h, 0)
        Digest.schedule(context)
        toast("日报时间改为 ${"%02d".format(h)}:00")
    }

    /** 分享收据：先在应用内展示链接，可复制或调起系统分享。 */
    fun shareReceipt() {
        showShare = true
    }

    /** 生成一张 1080×1350 的完税证明并调系统分享（图片）。 */
    fun shareReceiptCard() {
        scope.launch {
            val inv = invoice
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    ReceiptCard.render(context, inv, nickname)
                }.getOrNull()
            }
            if (file == null) {
                toast("生成失败，请重试")
                return@launch
            }
            val uri = runCatching {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            }.getOrNull()
            if (uri == null) {
                toast("生成失败，请重试")
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "我的完税证明")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                activity.startActivity(Intent.createChooser(send, "把完税证明发给朋友"))
            }.onFailure { toast("没有可用的分享应用") }
            showShare = false
        }
    }

    fun dismissShare() {
        showShare = false
    }

    /** 打开官网：系统没有浏览器时明确提示，不静默失败。 */
    fun openWebsite() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)))
        } catch (_: Exception) {
            toast("没有可用的浏览器")
        }
    }

    private fun withReceiptLink(action: (String) -> Unit) {
        val token = Prefs.token(context) ?: return
        if (receiptLinkBusy) return
        val date = invoice.date
        receiptLinkBusy = true
        scope.launch {
            try {
                val link = Api.shareReceipt(token, deviceId, date)
                if (showShare && Prefs.token(context) == token && invoice.date == date) action(link)
            } catch (e: java.io.IOException) {
                if (showShare) toast("无法生成链接，请联网后重试")
            } finally {
                receiptLinkBusy = false
            }
        }
    }

    /** 未结算时也给一段可复制的税单摘要，不画一个死按钮；官网固定另起一行。 */
    private fun shareSummaryText(link: String? = null): String {
        val inv = invoice
        val balance = inv.balanceParts()
        val who = nickname.ifBlank { "我" }
        val base = "$who 在 ${inv.date} 被手机打扰了 ${inv.totalDuty} 秒（免税额 ${inv.exemption} 秒，${balance.shortText}）。"
        return if (link != null) "$base\n收据：$link\n$WEBSITE" else "$base\n$WEBSITE"
    }

    fun copyReceiptLink() {
        if (!settledByServer) {
            copyText(shareSummaryText(), "税单摘要")
            showShare = false
            return
        }
        withReceiptLink { link ->
            copyText(link, "收据链接", sensitive = true)
            showShare = false
        }
    }

    fun shareViaSystem() {
        if (settledByServer) withReceiptLink(::sendReceiptSummary) else sendReceiptSummary(null)
    }

    private fun sendReceiptSummary(link: String?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${nickname.ifBlank { "我" }} 的打扰税收据")
            putExtra(Intent.EXTRA_TEXT, shareSummaryText(link))
        }
        activity.startActivity(Intent.createChooser(send, "把收据发给朋友"))
        showShare = false
    }

    fun requestResetLocalData() {
        showResetConfirm = true
    }

    fun cancelResetLocalData() {
        showResetConfirm = false
    }

    fun resetLocalData() {
        showResetConfirm = false
        scope.launch {
            withContext(Dispatchers.IO) {
                LedgerDb.get(context).clearAll()
                Prefs.clearEverything(context)
            }
            conversationLoad++
            conversationOverview = ConversationOverview(emptyList(), emptyList())
            conversationLoadFailed = false
            classifyingSource = null
            serverInvoice = null
            localInvoice = null
            reportLocalState = null
            reportServerState = null
            reportRange = "d7"
            reportAnchor = ""
            accountCode = null
            pending = 0
            lastSyncAt = 0
            lastError = null
            nickname = ""
            filteredToday = 0
            autoMergedCount = 0
            syncEnabled = true
            uploadTitles = false
            SyncScheduler.ensurePeriodic(context)
            apply(withContext(Dispatchers.IO) { loadLocal() })
            withContext(Dispatchers.IO) { InvoiceWidgetProvider.update(context) }
            toast("本机数据已清空，下次结算会重新开户")
        }
    }

    fun saveNickname(value: String) {
        val name = value.trim().take(24)
        nickname = name
        Prefs.setNickname(context, name)
        if (name.isBlank()) {
            toast("昵称已清空")
            return
        }
        profileBusy = true
        scope.launch {
            val token = Prefs.token(context)
            // 同步关闭时不发请求，昵称只存本机，等下一位同步时的补送。
            if (token != null && Prefs.syncEnabled(context)) {
                runCatching {
                    withContext(Dispatchers.IO) { Api.setProfile(token, deviceId, name) }
                }.onSuccess { Prefs.setSyncedNickname(context, it) }
            }
            profileBusy = false
            toast("昵称已保存")
            apply(withContext(Dispatchers.IO) { loadLocal() })
        }
    }

    fun copyText(text: String, label: String, sensitive: Boolean = false) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm?.setPrimaryClip(clip)
        toast("已复制$label")
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001,
            )
        } else {
            toast("当前系统版本无需额外通知权限")
        }
    }

    fun openListenerSettings() {
        activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** 通知授权还在但绑定断了（ROM 省电清理）时，主动向系统请求重连。 */
    fun reconnectListener() {
        rebindAttempts = 0
        WatchState.reconnect(context)
        toast("已请求重新连接，稍等几秒看状态")
        scope.launch {
            delay(3_000)
            refresh()
        }
    }

    // -- 后台常驻 / 监听修复 ------------------------------------------------

    fun listenerFixSheet() {
        showListenerFix = true
    }

    fun dismissListenerFix() {
        showListenerFix = false
    }

    /** 申请电池优化白名单：清后台/省电策略最容易掐掉通知监听的地方。 */
    fun requestBatteryWhitelist() {
        val pkg = context.packageName
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$pkg"),
        )
        try {
            activity.startActivity(direct)
            return
        } catch (_: Exception) {
            // 部分 ROM 不认这个 action，退回白名单列表页。
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            toast("在列表里找到「打扰税」，选择不限制")
        } catch (_: Exception) {
            toast("打不开电池优化设置，请到系统设置里手动添加")
        }
    }

    /** 打开各家 ROM 的自启动管理页；找不到就退回应用详情页。 */
    fun openAutoStartSettings() {
        if (tryOpenAutoStart()) return
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
            toast("在系统设置里允许「自启动」「后台运行」")
        } catch (_: Exception) {
            toast("打不开自启动设置，请到系统设置里手动允许")
        }
    }

    private fun tryOpenAutoStart(): Boolean {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
        )
        for ((pkg, cls) in candidates) {
            try {
                val intent = Intent().setComponent(android.content.ComponentName(pkg, cls))
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    activity.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun batteryWhitelisted(c: android.content.Context): Boolean {
        val pm = c.getSystemService(android.os.PowerManager::class.java) ?: return true
        return try {
            pm.isIgnoringBatteryOptimizations(c.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun openDndSettings() {
        activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    fun openAppNotificationSettings() {
        activity.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    fun setDigest(on: Boolean) {
        digestOn = on
        Prefs.setDigestEnabled(context, on)
        if (on) Digest.schedule(context)
        toast(if (on) "每日日报已开启" else "日报已关闭")
    }

    fun sendTestDigest() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { postDigestNow(context, force = true) }
            toast(
                if (ok) "已发送一条测试日报"
                else "日报没发出去：先允许通知权限",
            )
            if (!ok) refresh()
        }
    }

    /**
     * 「钉到桌面」。Android 8+ 的 requestPinAppWidget 要靠桌面自己弹确认框，
     * 部分系统桌面（澎湃/MIUI/ColorOS/OriginOS/鸿蒙/类原生）经常既不弹窗也不报错，
     * 只弹一个失败 toast 等于把用户晾在原地。所以这里无论成功、不支持还是没反应，
     * 都写入一段说明，页面把「长按桌面 → 添加小部件 → 打扰税」三步一直摆在下面。
     */
    fun requestPinWidget() {
        val mgr = context.getSystemService(AppWidgetManager::class.java)
        val provider = ComponentName(context, InvoiceWidgetProvider::class.java)
        if (mgr == null || !mgr.isRequestPinAppWidgetSupported) {
            pinGuideText = "这台桌面不支持一键钉组件，照下面三步手动添加。"
            showPinGuide = true
            toast("这台桌面不支持一键钉组件")
            return
        }
        val before = runCatching { mgr.getAppWidgetIds(provider).size }.getOrDefault(0)
        // 成功回调：桌面真的钉上去后会发这条广播，让组件立刻按当前账本渲染一次。
        // 系统/桌面发送回调时会往 Intent 里填 EXTRA_APPWIDGET_ID，11+ 必须用 mutable
        // （IMMUTABLE 会让桌面侧抛 IllegalArgumentException，正好表现为「钉到桌面没反应」）；
        // Intent 已显式指定自己的 BroadcastReceiver，mutable 是安全的。
        // API 31 之前没有 FLAG_MUTABLE，也默认就是 mutable，所以只在 31+ 加。
        val callbackFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pinnedCallback = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, InvoiceWidgetProvider::class.java)
                .setAction(InvoiceWidgetProvider.ACTION_PINNED),
            callbackFlags,
        )
        val requested = runCatching { mgr.requestPinAppWidget(provider, null, pinnedCallback) }
            .getOrDefault(false)
        if (!requested) {
            pinGuideText = "系统没受理这个请求，照下面三步手动添加。"
            showPinGuide = true
            toast("系统没受理，请手动添加")
            return
        }
        pinGuideText = "已请求桌面添加，请在系统弹窗里点「添加」。"
        showPinGuide = true
        toast("请在系统弹窗里点「添加」")
        // 部分桌面「受理了但没弹窗」：等几秒看组件数有没有变化，没变化就明确让用户手动加。
        scope.launch {
            delay(2_500)
            var after = runCatching { mgr.getAppWidgetIds(provider).size }.getOrDefault(before)
            if (after <= before) {
                // 弹窗慢一点的桌面再等一次，避免误判成「没反应」。
                delay(3_500)
                after = runCatching { mgr.getAppWidgetIds(provider).size }.getOrDefault(before)
            }
            pinGuideText = if (after > before) {
                "已钉到桌面。"
            } else {
                "桌面没弹窗也没响应，照下面三步手动添加。"
            }
        }
    }

    fun dismissPinGuide() {
        showPinGuide = false
    }

    // -- 计税规则：单价 / 免税额（按账户，离线先本机生效） -------------------

    /** 从票据「计价规则」或结算页进来：切到设置 tab 并请求打开「计税规则」页。 */
    fun openTaxSettings() {
        settingsTaxRequested = true
        tab = Tab.TARIFF
    }

    fun consumeTaxSettingsRequest(): Boolean {
        val requested = settingsTaxRequested
        settingsTaxRequested = false
        return requested
    }

    fun setUnitSeconds(value: Int) {
        val next = value.coerceIn(2, 30)
        if (next == taxUnit) return
        taxUnit = next
        applyTaxDraft()
        refresh()
    }

    fun setExemptionSeconds(value: Int) {
        // 界面上按 60 秒一档加减，这里再兜一次底，避免脏值写进偏好。
        val next = value.coerceIn(0, 1800) / 60 * 60
        if (next == taxExemption) return
        taxExemption = next
        applyTaxDraft()
        refresh()
    }

    fun resetTaxRules() {
        if (taxUnit == 8 && taxExemption == 240 && !taxPending) {
            toast("已经是默认税则")
            return
        }
        taxUnit = 8
        taxExemption = 240
        applyTaxDraft()
        refresh()
        toast("已恢复默认：8 秒/条，免税额 240 秒")
    }

    /** 应用并结算：本机先生效（离线也成立），联网后由 SyncManager 提交账户税则。 */
    fun applyTaxRules() {
        applyTaxDraft()
        refresh()
        toast(if (Prefs.token(context) != null) "已应用，正在结算" else "已应用，联网后自动提交")
        sync()
    }

    /** 改税则先落本机：更新本地 tariff 副本与缓存，并标记待同步。 */
    private fun applyTaxDraft() {
        taxUnit = taxUnit.coerceIn(2, 30)
        taxExemption = taxExemption.coerceIn(0, 1800) / 60 * 60
        val next = tariff.copy(
            unitSeconds = taxUnit,
            exemptionPerDay = taxExemption,
        )
        tariff = next
        Prefs.saveTariffJson(context, Json.tariffToJson(next))
        Prefs.setPendingTax(context, taxUnit, taxExemption)
        taxPending = true
    }

    fun dismissGuide() {
        showGuide = false
        Prefs.setOnboarded(context, true)
    }

    fun openGuide() {
        showGuide = true
    }

    fun maybeAutoCheckUpdate() {
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(context) < 6 * 60 * 60 * 1000L) return
        checkUpdate(manual = false)
    }

    /** 检查服务器 /v1/app/latest；发现新版本后可按 WiFi 自动下载（用户开关）。 */
    fun checkUpdate(manual: Boolean = true) {
        if (updatePhase == UpdatePhase.CHECKING || updatePhase == UpdatePhase.DOWNLOADING) return
        scope.launch {
            updatePhase = UpdatePhase.CHECKING
            updateMessage = "正在检查更新…"
            val info = withContext(Dispatchers.IO) { runCatching { Api.appLatest() }.getOrNull() }
            Prefs.setLastUpdateCheck(context, System.currentTimeMillis())
            if (info == null) {
                latestUpdate = null
                updatePhase = UpdatePhase.ERROR
                updateMessage = "检查失败，请联网重试"
                if (manual) toast("检查更新失败")
                return@launch
            }
            latestUpdate = info
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                val saved = withContext(Dispatchers.IO) {
                    val file = pendingUpdateFile(info)
                    file != null && validUpdateHash(info) &&
                        runCatching { sha256(file).equals(info.sha256, ignoreCase = true) }.getOrDefault(false)
                }
                updatePhase = if (saved) UpdatePhase.DOWNLOADED else UpdatePhase.AVAILABLE
                updateMessage = if (saved) "已下载，可以安装" else "发现新版本 v${info.versionName}"
                if (manual) toast("发现新版本 v${info.versionName}")
                if (!saved && Prefs.autoUpdateOnWifi(context) && isOnWifi()) downloadUpdate(manual = false)
            } else {
                updatePhase = UpdatePhase.UP_TO_DATE
                updateMessage = "已是最新版本 v$currentVersionName"
                if (manual) toast(updateMessage ?: "已是最新版本")
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun pendingUpdateFile(info: AppUpdate): File? =
        Prefs.pendingApkPath(context)?.let(::File)?.takeIf {
            it.name == "quietledger-${info.versionCode}.apk" && it.isFile
        }

    private fun validUpdateHash(info: AppUpdate): Boolean = info.sha256.matches(Regex("[a-fA-F0-9]{64}"))

    /** 手动下载不限网络。校验完成后等待用户安装。 */
    fun downloadUpdate(manual: Boolean = true) {
        val info = latestUpdate ?: return
        if (updatePhase != UpdatePhase.AVAILABLE && updatePhase != UpdatePhase.ERROR) return
        if (info.versionCode <= currentVersionCode) return
        if (!validUpdateHash(info)) {
            latestUpdate = null
            updatePhase = UpdatePhase.ERROR
            updateMessage = "安装包暂不可用，请稍后检查更新"
            return
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "quietledger-${info.versionCode}.apk")
        val part = File(dir, "quietledger-${info.versionCode}.apk.part")
        updatePhase = UpdatePhase.DOWNLOADING
        updateProgress = 0
        updateMessage = if (manual) "正在下载 v${info.versionName}（手动下载）"
        else "正在通过 WiFi 下载 v${info.versionName}"
        scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    val conn = Api.openApkConnection(info.apkUrl)
                    try {
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 30_000
                        conn.instanceFollowRedirects = false
                        conn.setRequestProperty("Accept", "*/*")
                        val total = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            part.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    if (System.currentTimeMillis() - startedAt > 300_000L) {
                                        throw java.io.IOException("下载超时")
                                    }
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    done += read
                                    if (total > 0) {
                                        val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                                        withContext(Dispatchers.Main) { updateProgress = percent }
                                    }
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                val actual = withContext(Dispatchers.IO) { sha256(part) }
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    updatePhase = UpdatePhase.ERROR
                    updateMessage = "安装包校验失败，请重新下载"
                    return@launch
                }
                if (file.exists()) file.delete()
                if (!part.renameTo(file)) {
                    part.delete()
                    updatePhase = UpdatePhase.ERROR
                    updateMessage = "无法保存安装包"
                    return@launch
                }
                Prefs.setPendingApkPath(context, file.absolutePath)
                updatePhase = UpdatePhase.DOWNLOADED
                updateProgress = 100
                updateMessage = "已下载，可以安装"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updatePhase = UpdatePhase.ERROR
                updateMessage = "下载失败，请重试"
            } finally {
                part.delete()
            }
        }
    }

    /** 通过 FileProvider 把下载的 APK 交给系统安装器（需要「安装未知应用」授权）。 */
    fun installUpdate() {
        if (updatePhase != UpdatePhase.DOWNLOADED) return
        val info = latestUpdate ?: return
        val file = pendingUpdateFile(info)
        if (file == null) {
            updatePhase = UpdatePhase.AVAILABLE
            updateMessage = "安装包已移除，请重新下载"
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            toast("请先允许「安装未知应用」")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                )
            } catch (_: Exception) {
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            toast("无法启动安装器：${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun applyAutoUpdatePref(on: Boolean) {
        autoUpdateWifi = on
        Prefs.setAutoUpdateOnWifi(context, on)
        toast(if (on) "发现新版本时将仅通过 WiFi 自动下载" else "已关闭 WiFi 自动下载")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // -- 个性化 / 忽略 / 单条删除 / 日报时间 ---------------------------------

    fun setTheme(id: String) {
        themeId = id
        QL.apply(id)
        Prefs.setThemeId(context, id)
        applyWindowTheme()
        toast("配色：${QL.current.name}")
    }

    private fun applyWindowTheme() {
        activity.window.setBackgroundDrawable(ColorDrawable(QL.Paper.toArgb()))
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !QL.IsDark
        controller.isAppearanceLightNavigationBars = !QL.IsDark
    }

    fun digestTimeText(): String = "%02d:%02d".format(digestHour, digestMinute)

    fun shiftDigestTime(deltaMinutes: Int) {
        val total = (digestHour * 60 + digestMinute + deltaMinutes).mod(24 * 60)
        digestHour = total / 60
        digestMinute = total % 60
        Prefs.setDigestTime(context, digestHour, digestMinute)
        Digest.schedule(context)
        toast("日报时间改为 ${digestTimeText()}")
    }

    fun selectEntry(event: NotifEvent) {
        selectedEvent = event
    }

    // -- 通知核对：让「为什么没记上」在应用内可查 ---------------------------

    fun openAudit() {
        showAudit = true
        refreshAudit()
    }

    fun dismissAudit() {
        showAudit = false
    }

    fun refreshAudit() {
        scope.launch {
            val dayStart = Fiscal.dayStart(Fiscal.today())
            val data = withContext(Dispatchers.IO) {
                val db = LedgerDb.get(context)
                Pair(db.skippedSince(dayStart), db.skippedReasonsSince(dayStart))
            }
            auditSkipped = data.first
            auditReasons = data.second
            // 「今日跳过」与明细「已过滤」同一口径：都取今日计数，不再数 skipped 样本
            // （样本还有按应用 5 秒节流，和实际过滤条数必然对不上）。
            auditTodayCount = Prefs.filteredToday(context)
            filteredToday = auditTodayCount
        }
    }

    /**
     * 授权还在、但系统把监听绑定掐掉时（国内 ROM 省电清理常见），
     * 前台每 4 秒检查一次，最多 5 分钟主动重连一次，避免"看起来一切正常但零入账"。
     */
    fun ensureListener() {
        if (listenerConnected) {
            rebindAttempts = 0
            return
        }
        if (!listenerTrouble) return
        val now = System.currentTimeMillis()
        // 重试节奏：立刻 → 15s → 30s → 60s → 之后 3 分钟一次。
        // 只重试不提示的话，用户会一直盯着"正在连接"；提示交给界面。
        val wait = when (rebindAttempts) {
            0 -> 0L
            1 -> 15_000L
            2 -> 30_000L
            3 -> 60_000L
            else -> 180_000L
        }
        if (now - Prefs.lastListenerRebindAt(context) < wait) return
        WatchState.reconnect(context)
        rebindAttempts += 1
    }

    fun dismissEntry() {
        selectedEvent = null
    }

    /** 删除单条通知记录：本机删除 + 服务器按 eventId 删除并重算。 */
    fun deleteSelectedEntry() {
        val event = selectedEvent ?: return
        selectedEvent = null
        scope.launch {
            withContext(Dispatchers.IO) { LedgerDb.get(context).deleteEvents(listOf(event.id)) }
            // 服务器票面里还带着这条，先丢掉旧快照，删除后立刻重算一次。
            serverInvoice = null
            val token = Prefs.token(context)
            if (token != null && Prefs.syncEnabled(context)) {
                withContext(Dispatchers.IO) {
                    runCatching { Api.purgeEvents(token, deviceId, eventIds = listOf(event.id)) }
                }
            }
            apply(withContext(Dispatchers.IO) { loadLocal() })
            withContext(Dispatchers.IO) { InvoiceWidgetProvider.update(context) }
            toast("已删除「${event.app}」这条记录")
            sync(manual = false)
        }
    }

    /** 不再记录某个应用：加入忽略名单，删除本机该应用记录，并让服务器清掉并重算。 */
    fun ignoreSelectedApp() {
        val event = selectedEvent ?: return
        selectedEvent = null
        Prefs.addIgnoredPackage(context, event.pkg)
        ignoredPackages = Prefs.ignoredPackages(context)
        scope.launch {
            val local = withContext(Dispatchers.IO) { LedgerDb.get(context).deleteByPackage(event.pkg) }
            serverInvoice = null
            val token = Prefs.token(context)
            if (token != null && Prefs.syncEnabled(context)) {
                withContext(Dispatchers.IO) {
                    runCatching { Api.purgeEvents(token, deviceId, packages = listOf(event.pkg)) }
                }
            }
            apply(withContext(Dispatchers.IO) { loadLocal() })
            sync(manual = false)
            withContext(Dispatchers.IO) { InvoiceWidgetProvider.update(context) }
            toast("已不再记录「${event.app}」，清理本机 $local 条")
        }
    }

    /** 应用趋势弹层里直接忽略某个应用（不需要先选中一条记录）。 */
    fun ignoreApp(pkg: String) {
        if (pkg.isBlank()) return
        val name = AppNames.label(context, pkg)
        Prefs.addIgnoredPackage(context, pkg)
        ignoredPackages = Prefs.ignoredPackages(context)
        scope.launch {
            val local = withContext(Dispatchers.IO) { LedgerDb.get(context).deleteByPackage(pkg) }
            serverInvoice = null
            val token = Prefs.token(context)
            if (token != null && Prefs.syncEnabled(context)) {
                withContext(Dispatchers.IO) {
                    runCatching { Api.purgeEvents(token, deviceId, packages = listOf(pkg)) }
                }
            }
            apply(withContext(Dispatchers.IO) { loadLocal() })
            sync(manual = false)
            withContext(Dispatchers.IO) { InvoiceWidgetProvider.update(context) }
            toast("已不再记录「$name」，清理本机 $local 条")
        }
    }

    fun unignoreApp(pkg: String) {
        Prefs.removeIgnoredPackage(context, pkg)
        ignoredPackages = Prefs.ignoredPackages(context)
        toast("已恢复记录「${AppNames.label(context, pkg)}」")
        refresh()
    }

    fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    val appVersion: String get() = BuildConfig.VERSION_NAME
    val apiBase: String get() = Api.baseUrl
}
