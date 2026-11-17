package dev.quietledger.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.quietledger.app.R
import dev.quietledger.app.core.Duty
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.core.Insights
import dev.quietledger.app.core.balanceParts
import dev.quietledger.app.data.LedgerDb
import dev.quietledger.app.data.Prefs
import dev.quietledger.app.service.Silence
import dev.quietledger.app.service.WatchState
import dev.quietledger.app.sync.SyncManager
import dev.quietledger.app.sync.SyncScheduler
import dev.quietledger.app.ui.MainActivity

/**
 * 桌面组件：常驻显示今日打扰税与免税额进度，并提供「静默 60 分钟」入口。
 * 数据完全来自本地账本，离线也有数；联网同步后由 SyncJobService 主动刷新。
 *
 * v1.8.0 文案口径（审计 P2-3）：状态行「入账 24 条，优惠 52 秒」、按钮「静默 60 分」、
 * 断开「监听断开，点开重连」；另加一行「连续 N 天没欠税」（与票据页共用 Insights.streak）。
 */
class InvoiceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                SyncScheduler.scheduleSoon(context)
                update(context)
            }
            ACTION_SILENCE -> {
                if (Silence.hasAccess(context)) {
                    if (Silence.isSilenced(context)) Silence.restore(context)
                    else Silence.silenceFor(context, 60 * 60 * 1000L)
                    update(context)
                } else {
                    val i = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("open", "dnd")
                    context.startActivity(i)
                }
            }
            ACTION_PINNED -> update(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "dev.quietledger.app.WIDGET_REFRESH"
        const val ACTION_SILENCE = "dev.quietledger.app.WIDGET_SILENCE"
        /** requestPinAppWidget 的成功回调：桌面确认添加后按当前账本重画一次。 */
        const val ACTION_PINNED = "dev.quietledger.app.WIDGET_PINNED"

        fun update(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, InvoiceWidgetProvider::class.java))
            ids.forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            try {
                renderInternal(context, mgr, widgetId)
            } catch (_: Exception) {
                // 单个组件渲染失败不应影响应用启动。
            }
        }

        private fun renderInternal(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val tariff = SyncManager.cachedTariff(context)
            val db = LedgerDb.get(context)
            val today = Fiscal.today()
            val events = db.eventsBetween(Fiscal.dayStart(today), Fiscal.dayEnd(today))
            val invoice = Duty.compute(
                events, tariff, today, today,
                Prefs.accountCode(context) ?: "未开户",
            )
            val pending = db.pendingCount()

            // v1.8.0：连续达标（近 30 天逐日按 Duty.compute 结算，与票据页小日历同一份口径）。
            // 桌面组件是「能显示就显示」的级别：账本异常/旧库字段缺失时宁可整行不显示，也不要崩。
            val streakText = runCatching {
                val firstDay = Fiscal.dayStart(today.minusDays(29L))
                val recent = db.eventsBetween(firstDay, Fiscal.dayEnd(today))
                val info = Insights.streak(recent, tariff, today, days = 30)
                when {
                    info.owedToday -> "今天欠税，明天重新开始"
                    info.current > 0 -> "连续 ${info.current} 天没欠税"
                    else -> null
                }
            }.getOrNull()

            val rv = RemoteViews(context.packageName, R.layout.widget_invoice)
            rv.setTextViewText(R.id.w_title, "打扰税 · ${Fiscal.iso(today).substring(5)}")
            rv.setTextViewText(R.id.w_total, invoice.totalDuty.toString())
            // 结余 / 欠税统一走 core.balanceParts()，只给正数，不再出现「欠 X / 余 X」。
            val balance = invoice.balanceParts()
            rv.setTextViewText(R.id.w_status, balance.shortText)
            rv.setTextColor(
                R.id.w_status,
                if (balance.owed) 0xFFB3392C.toInt() else 0xFF2F5B4B.toInt(),
            )
            // 优惠是全部优惠合计（连发/免打扰/封顶），桌面上不强调某一种；口径与票据一致。
            val savedPart = if (invoice.savedSeconds > 0) "，优惠 ${invoice.savedSeconds} 秒" else ""
            rv.setTextViewText(
                R.id.w_meta,
                "入账 ${invoice.events} 条$savedPart",
            )
            // 授权还在但绑定断了：桌面上直接提示，别让用户以为"不计了"。
            val listeningBroken = WatchState.trouble(context)
            rv.setTextViewText(
                R.id.w_sync,
                when {
                    listeningBroken -> "监听断开，点开重连"
                    pending > 0 -> "待同步 $pending"
                    else -> "已同步"
                },
            )
            rv.setTextColor(
                R.id.w_sync,
                if (listeningBroken) 0xFFB3392C.toInt() else 0xFF5C5446.toInt(),
            )
            // 连续达标：取不到就整行隐藏（RemoteViews 不支持 null 文案，用可见性表达）。
            if (streakText == null) {
                rv.setViewVisibility(R.id.w_streak, View.GONE)
            } else {
                rv.setViewVisibility(R.id.w_streak, View.VISIBLE)
                rv.setTextViewText(R.id.w_streak, streakText)
            }
            rv.setProgressBar(R.id.w_bar, invoice.exemption.coerceAtLeast(1), invoice.totalDuty.coerceAtMost(invoice.exemption), false)

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.widget_root, open)
            rv.setOnClickPendingIntent(R.id.w_title, open)
            val refresh = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, InvoiceWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.w_meta, refresh)
            val silence = PendingIntent.getBroadcast(
                context, 2,
                Intent(context, InvoiceWidgetProvider::class.java).setAction(ACTION_SILENCE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.w_silence, silence)
            rv.setTextViewText(R.id.w_silence, if (Silence.isSilenced(context)) "结束" else "静默 60 分")
            mgr.updateAppWidget(widgetId, rv)
        }
    }
}
