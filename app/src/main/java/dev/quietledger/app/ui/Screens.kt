package dev.quietledger.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quietledger.app.core.AppTrend
import dev.quietledger.app.core.Duty
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.core.Fmt
import dev.quietledger.app.core.Invoice
import dev.quietledger.app.core.InvoiceLine
import dev.quietledger.app.core.Insights
import dev.quietledger.app.core.MarkedEvent
import dev.quietledger.app.core.Report
import dev.quietledger.app.core.ReportApp
import dev.quietledger.app.core.ReportBucket
import dev.quietledger.app.core.ReportKind
import dev.quietledger.app.core.StreakInfo
import dev.quietledger.app.core.Tariff
import dev.quietledger.app.core.balanceParts
import dev.quietledger.app.data.AppNames

@Composable
fun SyncBadge(state: AppState) {
    // 审计 P1-1：徽标只讲「这张票现在信谁」，不再只看 pending。
    // 关同步=徽标灰、票面一律本机；有网但服务器条数落后本机时是「本机估算」，不是「已同步」。
    val settled = state.settledByServer
    val dot = when {
        !state.syncEnabled -> QL.InkFaint
        state.syncing -> QL.Amber
        state.lastError != null -> QL.Seal
        settled -> QL.Green
        else -> QL.Amber
    }
    val text = when {
        !state.syncEnabled -> "仅本机"
        state.syncing -> "结算中"
        state.lastError != null -> "离线"
        settled -> "已结算"
        else -> "本机估算"
    }
    Row(
        // 关掉同步后点击仍走 sync()：内部提示「同步已关闭」，不是报错。
        Modifier
            .qlClickable(onClick = { state.sync() })
            .padding(top = 6.dp, start = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(dot))
        Spacer(Modifier.width(5.dp))
        MicroLabel(text, color = QL.InkSoft)
    }
}

/**
 * 底部 Tab：大图标 + 小字（v1.9.0），0 圆角、1dp 竖分隔、选中墨块反白，
 * 墨块位置由 [pagerState] 连续插值——横滑时指示器跟手，点击时跟点击的滚动一起滑过去。
 *
 * 图标与文字同色同动画（[Motion.Small]），只做颜色过渡，不做位移/缩放：
 * 底部常驻元素每帧变形会显得廉价，主流 App 的底栏也基本只有选中色差。
 * 英文微标签不再显示，只留一句中文（无衬线 11sp），避免底栏出现两行小字挤在一起；
 * 英文原名进无障碍描述（TalkBack 读「票据 INVOICE」）。
 *
 * 墨块在 draw 阶段读取 `currentPage + currentPageOffsetFraction`：只失效绘制，
 * 不触发重组、不触发 layout，所以横滑时不会每帧测量。
 */
@Composable
fun BottomTabs(
    pagerState: PagerState,
    current: Tab,
    onSelect: (Tab) -> Unit,
) {
    val tabs = Tab.entries
    // 文字/图标反白跟随「墨块现在压在哪个槽位」，而不是等停稳才切：只取整，不逐帧重组。
    val nearest by remember {
        derivedStateOf {
            (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                .roundToInt()
                .coerceIn(0, tabs.lastIndex)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(QL.Paper)
            .padding(top = 1.dp)
            .drawBehind {
                // 4 等分里扣掉 3 条 1dp 竖线：position 与 Tab 槽位一一对应，且连续可插值。
                val gap = 1.dp.toPx()
                val slot = (size.width - gap * (tabs.size - 1)) / tabs.size
                val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, tabs.lastIndex.toFloat())
                drawRect(
                    color = QL.Ink,
                    topLeft = Offset(position * (slot + gap), 0f),
                    size = Size(slot, size.height),
                )
            },
    ) {
        tabs.forEachIndexed { index, t ->
            val isSelected = t == current
            val covered = index == nearest
            val foreground by animateColorAsState(
                targetValue = if (covered) QL.Paper else QL.InkSoft,
                animationSpec = Motion.standard(Motion.Small),
                label = "qlTabForeground",
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .qlClickable(
                        onClick = { onSelect(t) },
                        haptic = HapticFeedbackType.TextHandleMove,
                        wash = false,
                    )
                    .semantics {
                        selected = isSelected
                        role = Role.Tab
                        contentDescription = "${t.zh} ${t.en}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(t.glyph, size = 21.dp, color = foreground, stroke = 1.3.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.zh,
                        style = QL.label.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.6.sp,
                            color = foreground,
                        ),
                    )
                }
            }
            if (index < tabs.lastIndex) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(QL.RuleSoft))
            }
        }
    }
}

// ---------------------------------------------------------------- 票据

/** 首屏最多一条状态提示：同一屏堆四条横幅会把税单挤下去，按「功能不可用 > 出错 > 更新」取第一条。 */
private class HomeNotice(
    val text: String,
    val action: String,
    val tone: Color,
    val onAction: () -> Unit,
)

@Composable
fun InvoiceScreen(state: AppState) {
    val inv = state.invoice
    var showPricing by remember { mutableStateOf(false) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    // 首屏只留一条提示，其余进「通知核对」：四条横幅叠起来会把应缴数字挤出第一屏。
    val notices = buildList {
        if (!state.listenerOn) add(
            HomeNotice("通知使用权尚未开启，税单不会自动记账。", "去开启", QL.Seal) {
                state.openListenerSettings()
            },
        )
        if (state.listenerTrouble) add(
            HomeNotice("通知监听已断开，可能收不到打扰记账。", "修复", QL.Amber) {
                state.listenerFixSheet()
            },
        )
        if (state.lastError != null) add(
            HomeNotice(state.lastError ?: "", "重试", QL.Amber) { state.sync() },
        )
        if (state.updatePhase == UpdatePhase.AVAILABLE || state.updatePhase == UpdatePhase.DOWNLOADED) add(
            HomeNotice(
                if (state.updatePhase == UpdatePhase.DOWNLOADED) "更新已下载，可以安装"
                else "发现新版本 v${state.latestUpdate?.versionName.orEmpty()}",
                "去更新",
                QL.Green,
            ) {
                state.tab = Tab.TARIFF
                state.scrollToUpdate = true
            },
        )
    }
    val notice = notices.firstOrNull()
    val moreNotices = (notices.size - 1).coerceAtLeast(0)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 18.dp),
        ) {
            ScreenHeader(
                title = "票据",
                latin = "ATTENTION DUTY INVOICE / 北京时间结算",
                right = { SyncBadge(state) },
            )

            notice?.let {
                NoticeBar(
                    text = it.text,
                    actionLabel = it.action,
                    onAction = it.onAction,
                    tone = it.tone,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                )
                if (moreNotices > 0) {
                    MicroLabel(
                        "还有 $moreNotices 项",
                        color = QL.InkSoft,
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 2.dp)
                            .qlClickable { state.openAudit() },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            InvoiceStatusBar(state, inv)
            Spacer(Modifier.height(12.dp))
            ExemptionProgress(inv)
            state.streakInfo?.let { streak ->
                Spacer(Modifier.height(10.dp))
                StreakCalendar(streak) { selectedDay = it }
            }
            Spacer(Modifier.height(14.dp))

            ReceiptPaper(Modifier.padding(horizontal = 18.dp)) {
                InvoiceHead(inv, state)
                Spacer(Modifier.height(10.dp))
                DashedHLine(dashOn = 2.dp, dashOff = 3.dp)
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                    // 与税目行同一个图标列宽（20dp 图标 + 6dp 间距），表头「税目」和下面的应用名对齐。
                    Spacer(Modifier.width(26.dp))
                    MicroLabel("税目 / ITEM", modifier = Modifier.weight(1f))
                    // 条数/应缴两列表头必须和数字一样右对齐，否则「条数」会飘到数字左边一大截。
                    MonoText(
                        "条数",
                        style = QL.micro,
                        color = QL.InkFaint,
                        modifier = Modifier.width(46.dp),
                        align = TextAlign.End,
                    )
                    MonoText(
                        "应缴",
                        style = QL.micro,
                        color = QL.InkFaint,
                        modifier = Modifier.width(66.dp),
                        align = TextAlign.End,
                    )
                }
                if (inv.lines.isEmpty()) {
                    EmptyTicket(onEnable = { state.openListenerSettings() })
                } else {
                    inv.lines.forEach { line ->
                        InvoiceLineRow(line) {
                            if (line.pkg.isNotBlank()) state.openTrend(line.pkg)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                DashedHLine(dashOn = 2.dp, dashOff = 3.dp)
                Spacer(Modifier.height(8.dp))
                InvoiceTotals(inv)
                Spacer(Modifier.height(10.dp))
                DashedHLine(dashOn = 2.dp, dashOff = 3.dp)
                Spacer(Modifier.height(6.dp))
                MicroLabel(
                    "开票 / ISSUED  " +
                        (if (inv.source == "server") Fiscal.issuedText(inv.issuedAt)
                        else inv.issuedAt.ifBlank { "未结算" }),
                )
                Spacer(Modifier.height(2.dp))
                PricingEntryRow(state.tariff, inv) { showPricing = true }
                Spacer(Modifier.height(2.dp))
                MicroLabel("本单不作报销凭证")
            }

            Spacer(Modifier.height(14.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MicroLabel("打扰时段分布 / 24H")
                    Spacer(Modifier.weight(1f))
                    MicroLabel("夜间占比 ${String.format("%.0f%%", inv.nightRatio * 100)}")
                }
                Spacer(Modifier.height(6.dp))
                HourStrip(inv.byHour, state.tariff) { state.openHourSheet(it) }
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphChip(GlyphKind.COUNT, "${inv.events}")
                    Spacer(Modifier.width(14.dp))
                    GlyphChip(GlyphKind.SAVED, "${inv.savedSeconds} 秒")
                    Spacer(Modifier.width(14.dp))
                    GlyphChip(GlyphKind.DEVICE, "${inv.devices.size.coerceAtLeast(1)} 台")
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .qlClickable {
                            state.tab = Tab.WEEKLY
                            state.changeReportRange("month")
                        }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(GlyphKind.CALENDAR, 12.dp, QL.InkSoft)
                    Spacer(Modifier.width(6.dp))
                    MicroLabel("本月统计", color = QL.InkSoft)
                    Spacer(Modifier.weight(1f))
                    Chevron()
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QlButton(
                    label = if (state.syncing) "结算中…" else "重新结算",
                    onClick = { state.sync() },
                    modifier = Modifier.weight(1f),
                    primary = true,
                    enabled = !state.syncing,
                )
                QlButton(
                    label = "把收据发给朋友",
                    onClick = { state.shareReceipt() },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                FocusButton(state, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        if (showPricing) PricingSheet(state, inv) { showPricing = false }
        if (state.silenceSheet) SilenceSheet(state)
        state.hourSheetHour?.let { HourSheet(state, it) }
        state.trendPkg?.let { pkg -> TrendSheet(state, pkg) }
        selectedDay?.let { DayDetailSheet(state, it) { selectedDay = null } }
    }
}

/**
 * 静默按钮：进行中每秒刷一次倒计时，到点自动恢复（静默恢复由 AlarmManager 负责，
 * 这里只是把按钮状态读回来）。25/45/60 的选择在 [SilenceSheet] 里。
 */
@Composable
private fun FocusButton(state: AppState, modifier: Modifier = Modifier) {
    val active = state.dndActive
    val remain by produceState(initialValue = state.focusRemainingSec, active) {
        while (active) {
            value = state.focusRemainingSec
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val label = when {
        !active -> "静默 25 分钟"
        remain <= 0 -> "静默结束"
        else -> "静默中 %02d:%02d，点此结束".format(remain / 60, remain % 60)
    }
    QlButton(
        label = label,
        onClick = { state.toggleSilence() },
        modifier = modifier,
        compact = true,
    )
}

/**
 * 结算来源条：谁在算这张票、上一次结算是什么时候。
 * 审计 P1-1：状态只在右上角 SyncBadge 讲一次，这里只说条数与时间，避免同屏两个来源结论。
 */
@Composable
private fun InvoiceStatusBar(state: AppState, inv: Invoice) {
    val serverInv = state.serverInvoice?.takeIf { it.date == inv.date }
    val serverCount = serverInv?.events
    val localCount = state.todayEvents.size
    val catchingUp = state.pending > 0 || (serverCount != null && localCount > serverCount)
    val line = when {
        !state.syncEnabled -> "仅本机记账，${localCount} 条"
        state.lastError != null -> "本机 $localCount 条，联网后同步"
        state.taxPending -> "计税规则待同步"
        catchingUp -> "本机 $localCount 条，已同步 ${serverCount ?: 0} 条"
        state.settledByServer -> "已同步 $localCount 条"
        else -> "联网后自动结算"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MicroLabel(line, color = if (catchingUp || state.lastError != null) QL.Amber else QL.InkFaint)
        Spacer(Modifier.weight(1f))
        MicroLabel(
            when {
                state.syncing -> "结算中…"
                state.lastSyncAt > 0 -> "最近结算 ${Fiscal.clockText(state.lastSyncAt)}"
                else -> "还没结算过"
            },
        )
    }
}

/** 今日免税额进度：已用 / 当日免税额（默认 240 秒，账户可改），超出部分记欠税。 */
@Composable
private fun ExemptionProgress(inv: Invoice) {
    val limit = inv.exemption.coerceAtLeast(1)
    val ratio = inv.totalDuty.toFloat() / limit
    val over = inv.isOwed
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MicroLabel("今日免税额进度 / EXEMPTION")
            Spacer(Modifier.weight(1f))
            MicroLabel(if (over) "已超出" else "剩余", color = if (over) QL.Seal else QL.InkFaint)
            Spacer(Modifier.width(4.dp))
            MonoText(
                "${if (over) inv.totalDuty - inv.exemption else inv.balance} 秒",
                style = QL.monoStrong,
                color = if (over) QL.Seal else QL.Green,
            )
        }
        Spacer(Modifier.height(5.dp))
        MeterBar(ratio = ratio, color = if (over) QL.Seal else QL.Ink)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonoText("已用 ${inv.totalDuty} / ${inv.exemption} 秒", style = QL.monoSmall, color = QL.InkSoft)
            Spacer(Modifier.weight(1f))
            MicroLabel(if (over) "超出部分记为欠税" else "每天按北京时间重置")
        }
    }
}

@Composable
private fun InvoiceHead(inv: Invoice, state: AppState) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            // 页头已经有「ATTENTION DUTY INVOICE / 北京时间结算」，票面里不再重复第四遍英文。
            Text("注意力税单", style = QL.h2)
            Spacer(Modifier.height(7.dp))
            Row {
                MicroLabel("税期  ", color = QL.InkFaint)
                MonoText(
                    "${inv.date} ${runCatching { Fiscal.weekdayZh(Fiscal.parseDate(inv.date)) }.getOrDefault("")}",
                    style = QL.monoSmall,
                    color = QL.Ink,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row {
                MicroLabel("账户  ", color = QL.InkFaint)
                MonoText(state.accountCode ?: "未开户", style = QL.monoSmall, color = QL.Ink)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            MicroLabel("单号 / NO.")
            MonoText(inv.invoiceId, style = QL.monoSmall.copy(fontSize = 9.5.sp), color = QL.Ink)
        }
    }
}

@Composable
private fun InvoiceLineRow(line: InvoiceLine, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (line.pkg.isNotBlank()) Modifier.qlClickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 汇总行（「其他」/无包名）没有图标，留同宽占位，应用名左端不会跳。
            val iconPkg = if (line.pkg.isNotBlank() && line.app != "其他") line.pkg else ""
            AppIcon(iconPkg, size = 20.dp, label = line.app)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(line.app, style = QL.h3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line.night + line.burst + line.dnd + line.notice > 0) {
                    Spacer(Modifier.height(2.dp))
                    // 命中规则用图标 + 条数，不再拼「连发 2 条 · 群发 1 条」这种点分隔长文案。
                    EffectChips(line.night, line.burst, line.dnd, line.notice)
                }
            }
            Spacer(Modifier.width(6.dp))
            MonoText(
                "${line.count}",
                style = QL.monoSmall,
                color = QL.InkSoft,
                modifier = Modifier.width(46.dp),
                align = TextAlign.End,
            )
            MonoText(
                "${line.duty} 秒",
                style = QL.monoStrong,
                modifier = Modifier.width(66.dp),
                align = TextAlign.End,
            )
        }
    }
    HairHLine(color = QL.RuleSoft)
}

@Composable
private fun InvoiceTotals(inv: Invoice) {
    val balance = inv.balanceParts()
    // 审计 P0-1：票面是唯一要拿计算器验证的地方，拆成两组、每组一行结论，顺序可笔算：
    //   计税：原价 − 优惠 = 应缴
    //   结算：免税额 − 应缴 = 结余（或：应缴 − 免税额 = 欠税）
    MicroLabel("计税 / DUTY")
    Spacer(Modifier.height(3.dp))
    if (inv.savedSeconds > 0) {
        TotalsRow("原价", "${inv.totalDuty + inv.savedSeconds} 秒", QL.InkSoft)
        TotalsRow("优惠", "${inv.savedSeconds} 秒", QL.InkSoft)
    }
    TotalsRow("应缴", "${inv.totalDuty} 秒", QL.Ink, strong = true)
    Spacer(Modifier.height(8.dp))
    HairHLine(color = QL.RuleSoft)
    Spacer(Modifier.height(8.dp))
    MicroLabel("结算 / SETTLE")
    Spacer(Modifier.height(3.dp))
    TotalsRow("每日免税额", "${inv.exemption} 秒", QL.InkSoft)
    Spacer(Modifier.height(5.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            MicroLabel("${balance.label} / ${balance.latin}")
            Spacer(Modifier.height(2.dp))
            Text(
                text = balance.valueText,
                style = QL.amountLg.copy(color = if (balance.owed) QL.Seal else QL.Green),
            )
        }
        Stamp(
            text = if (balance.owed) "欠税" else "已结清",
            color = if (balance.owed) QL.Seal else QL.Green,
        )
    }
}

/** 票面合计的一行：左标签、右等宽金额；strong 用墨色强调结论行。 */
@Composable
private fun TotalsRow(label: String, value: String, color: Color, strong: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = if (strong) QL.body else QL.bodySoft)
        Spacer(Modifier.weight(1f))
        MonoText(
            value,
            style = if (strong) QL.monoStrong else QL.mono,
            color = if (strong) QL.Ink else color,
        )
    }
}

/** 票据里那一行可点的「计价规则」入口，细则都收进弹层。 */
@Composable
private fun PricingEntryRow(tariff: Tariff, inv: Invoice, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .qlClickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MicroLabel("计价规则", color = QL.InkSoft)
        Spacer(Modifier.weight(1f))
        MicroLabel("${tariff.unitSeconds} 秒/条  免税额 ${inv.exemption} 秒")
        Spacer(Modifier.width(6.dp))
        Chevron()
    }
}

/**
 * 计价细则（v1.6.0 税则 v2026-11-c）：单价 / 夜间 / 连发折扣 / 免打扰 / 群发 / 封顶 /
 * 聊天办公折扣表 / 免税额 / 计费示例。折扣不是加罚，示例只用中性说法，
 * 不把「连发优惠」当成唯一优惠来源。
 */
@Composable
private fun PricingSheet(state: AppState, inv: Invoice, onClose: () -> Unit) {
    val t = state.tariff
    val unit = t.unitSeconds
    val chat = t.appFactor("com.tencent.mm")
    val work = t.appFactor("com.tencent.wework")
    val plainBurst = exampleDuty(unit, t.burstMultiplier)
    val dndOnly = exampleDuty(unit, t.dndMultiplier)
    val noticeOnly = exampleDuty(unit, t.noticeMultiplier)
    val chatNight = exampleDuty(unit, chat, t.nightMultiplier)
    val capSeconds = exampleDuty(unit, t.capFactor)
    var fullRules by rememberSaveable { mutableStateOf(false) }
    // 审计 P0-3：速览常驻 7 行，完整权重与示例默认收起，小屏/大字体也能看到底部按钮。
    QlSheet(
        title = "计价规则",
        subtitle = "规则版本 ${tariffVersionText(inv.tariffVersion)}",
        onClose = onClose,
        scrollable = true,
    ) {
        Column(Modifier.fillMaxWidth()) {
            KeyValueRow("单价 / 每条打扰", "${unit} 秒")
            HairHLine(color = QL.RuleSoft)
            KeyValueRow(
                "夜间 / ${twoHours(t.nightStartHour)}:00–${twoHours(t.nightEndHour)}:00",
                "×${numText(t.nightMultiplier)}",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow(
                "连发 / 同应用同类 ${t.burstWindowSec} 秒内",
                "×${numText(t.burstMultiplier)}",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow(
                "免打扰 / 设备静默或系统免打扰",
                "×${numText(t.dndMultiplier)}",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow(
                "群发 / @所有人 这类提醒",
                "×${numText(t.noticeMultiplier)}",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow(
                "封顶 / 单条最多 ${numText(t.capFactor)} 倍单价",
                "≤ $capSeconds 秒",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow("免税额 / 每天按北京时间重置", "${inv.exemption} 秒")
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .qlClickable { fullRules = !fullRules }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MicroLabel(if (fullRules) "收起完整规则" else "完整规则与示例", color = QL.InkSoft)
                Spacer(Modifier.weight(1f))
                FoldArrow(fullRules)
            }
            if (fullRules) {
                Spacer(Modifier.height(6.dp))
                MicroLabel("应用权重 / APP FACTOR")
                Spacer(Modifier.height(2.dp))
                FactorRow("微信 / QQ / TIM", chat)
                FactorRow("企业微信 / 钉钉 / 飞书", work)
                FactorRow("其它应用", t.appFactor("dev.quietledger.other"))
                Spacer(Modifier.height(8.dp))
                MicroLabel("会话权重优先")
                for (kind in dev.quietledger.app.core.ConversationClass.entries.drop(1)) {
                    KeyValueRow(kind.label, kind.rateLabel)
                }
                Spacer(Modifier.height(8.dp))
                MicroLabel("计价示例 / EXAMPLE")
                Spacer(Modifier.height(4.dp))
                // 只给结果，不给乘式：用户要的是「这条几秒」，不是计税公式。
                Text("普通应用连发第 2 条 $plainBurst 秒", style = QL.mono.copy(fontSize = 11.5.sp))
                Text("免打扰中一条 $dndOnly 秒", style = QL.mono.copy(fontSize = 11.5.sp))
                Text("群发 @所有人 $noticeOnly 秒", style = QL.mono.copy(fontSize = 11.5.sp))
                Text("微信夜间首条 $chatNight 秒", style = QL.mono.copy(fontSize = 11.5.sp))
            }
            if (inv.savedSeconds > 0) {
                Spacer(Modifier.height(8.dp))
                MicroLabel("这张票优惠 ${inv.savedSeconds} 秒", color = QL.InkSoft)
            }
            Spacer(Modifier.height(10.dp))
            QlButton(
                label = "调整我的税则",
                onClick = {
                    onClose()
                    state.openTaxSettings()
                },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
            )
        }
    }
}

@Composable
private fun FactorRow(label: String, factor: Double) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = QL.bodySoft, modifier = Modifier.weight(1f))
        MonoText("×${numText(factor)}", style = QL.monoSmall, color = QL.InkSoft)
    }
}

/** 本地兜底税率没有版本号，别把 "local-default" 直接念给用户。 */
private fun tariffVersionText(version: String): String =
    if (version.isBlank() || version == "local-default") "本机默认" else version

private fun twoHours(hour: Int): String = "%02d".format(hour)

@Composable
private fun EmptyTicket(onEnable: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text("今日本单无税目", style = QL.h2)
        Spacer(Modifier.height(4.dp))
        Text("开启通知使用权后，每次打扰都会记一笔。", style = QL.bodySoft)
        Spacer(Modifier.height(10.dp))
        QlButton("开启通知使用权", onEnable, compact = true)
    }
}

private fun numText(v: Double): String = if (v == v.toInt().toDouble()) "${v.toInt()}" else "$v"

/**
 * 连续达标 + 30 天小日历：把「今天有没有欠税」从一个瞬时数字变成一条能看见的轨迹。
 * 达标用墨色、欠税用税红，点方块直接看那天的日详情；断签时直说今天欠了多少。
 */
@Composable
private fun StreakCalendar(streak: StreakInfo, onPickDay: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MicroLabel(
                if (streak.owedToday) "今天欠税 ${streak.owedSeconds} 秒，明天重新开始"
                else "连续 ${streak.current} 天没欠税",
                color = if (streak.owedToday) QL.Seal else QL.InkSoft,
            )
            Spacer(Modifier.weight(1f))
            MicroLabel("近 ${streak.days.size} 天")
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            streak.days.forEach { d ->
                val tone = when {
                    d.owed -> QL.Seal
                    d.events == 0 -> QL.RuleSoft
                    else -> QL.Ink
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(tone)
                        .semantics { contentDescription = "${d.date}，应缴 ${d.duty} 秒" }
                        .qlClickable { onPickDay(d.date) },
                )
            }
        }
    }
}

/** 静默时长：25 / 45 / 60 分钟三档，到点自动恢复；只挡提醒，照常记账。 */
@Composable
private fun SilenceSheet(state: AppState) {
    QlSheet(
        title = "静默多久？",
        subtitle = "只挡提醒，照常记账",
        onClose = state::dismissSilenceSheet,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(25, 45, 60).forEach { minutes ->
                QlButton(
                    label = "$minutes 分钟",
                    onClick = { state.startFocus(minutes) },
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MicroLabel("完成时间会显示在票据页按钮上", color = QL.InkFaint)
    }
}

/** 24 小时下钻：点票据底部的柱子，看那个小时具体是哪些应用在吵。 */
@Composable
private fun HourSheet(state: AppState, hour: Int) {
    val h = hour.coerceIn(0, 23)
    val marked = remember(state.todayEvents, state.tariff, h) {
        Duty.mark(state.todayEvents, state.tariff)
            .filter { Fiscal.hourOf(it.event.ts) == h }
            .sortedBy { it.event.ts }
    }
    val duty = marked.sumOf { it.duty }
    QlSheet(
        title = "%02d:00–%02d:00".format(h, (h + 1) % 24),
        subtitle = "${marked.size} 条 / $duty 秒",
        onClose = state::closeHourSheet,
        scrollable = true,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (marked.isEmpty()) {
                Text("这个小时没有记录。", style = QL.bodySoft)
            } else {
                marked.forEach { m -> AppEventRow(m) { state.selectEntry(m.event) } }
            }
        }
    }
}

// ---------------------------------------------------------------- 明细

private data class AppGroup(
    val pkg: String,
    val app: String,
    val count: Int,
    val duty: Int,
    val saved: Int,
    val events: List<MarkedEvent>,
)

private fun groupByApp(marked: List<MarkedEvent>): List<AppGroup> =
    marked.groupBy { it.event.pkg }.map { (pkg, list) ->
        AppGroup(
            pkg = pkg,
            app = list.firstOrNull()?.event?.app ?: "未知",
            count = list.size,
            duty = list.sumOf { it.duty },
            saved = list.sumOf { it.saved },
            events = list.sortedByDescending { it.event.ts },
        )
    }.sortedWith(compareByDescending<AppGroup> { it.duty }.thenBy { it.app })

/** 折叠行上的时段：同一分钟只显示一个时间，跨分钟显示起止。 */
private fun spanText(group: EventGroup): String {
    val start = Fiscal.hm(group.head.event.ts)
    val end = Fiscal.hm(group.tail.event.ts)
    return if (start == end) start else "$start–$end"
}

/**
 * 明细列表每一行的统一动效：增删 / 重排走 `animateItem`（弹簧），
 * 首屏窗口内前 8 项再叠一层错峰出现。只能在 LazyItemScope 里用（item / itemsIndexed 的内容）。
 * 参数全部来自 [Motion]，页面里不另写时长/曲线。
 */
@Composable
private fun LazyItemScope.qlItemMotion(index: Int, entranceOpen: Boolean): Modifier =
    Modifier
        .animateItem(
            fadeInSpec = Motion.enter(Motion.ContentSwap),
            placementSpec = Motion.springy(),
            fadeOutSpec = Motion.exit(Motion.SmallOut),
        )
        .staggerIn(index, entranceOpen)

// ---------------------------------------------------------------- 行骨架

/**
 * 明细类行的固定列宽：
 *  - 时间/区间列 66dp：`18:21–18:23` 也放得下（跨分钟区间用 9.5sp，见 [LedgerLead]）；
 *  - 图标列 26dp（18dp 图标 + 8dp 间距），没有图标的行也占同样宽度。
 *
 * 应用名/正文列因此永远从 `20 + 66 + 26 = 112dp` 开始，普通行、连发组头、展开子行、
 * 按应用汇总、应用下钻、日详情都是同一条竖线。
 */
internal val LedgerLeadWidth = 66.dp
internal val LedgerIconWidth = 26.dp

/**
 * 行骨架：时间列 + 图标列固定宽，正文列 weight，trailing 放金额/标记/箭头。
 * 弹层里的行传 `horizontalPadding = 0.dp`（[QlSheet] 自己已有 20dp 内边距）。
 */
@Composable
internal fun LedgerRowScaffold(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    horizontalPadding: Dp = 20.dp,
    verticalPadding: Dp = 6.dp,
    lead: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.qlClickable(onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(LedgerLeadWidth)) { lead() }
        Box(Modifier.width(LedgerIconWidth), contentAlignment = Alignment.CenterStart) {
            icon?.invoke()
        }
        Column(Modifier.weight(1f), content = content)
        trailing()
    }
}

/** 行首时间/区间：单点时间用 monoSmall；跨分钟区间字符更多，缩到 9.5sp 才不撑出 66dp。 */
@Composable
internal fun LedgerLead(text: String) {
    MonoText(
        text = text,
        style = if (text.length > 5) QL.monoSmall.copy(fontSize = 9.5.sp) else QL.monoSmall,
        color = QL.InkFaint,
    )
}

@Composable
fun LedgerScreen(state: AppState) {
    var filter by rememberSaveable { mutableStateOf(0) }
    var byApp by rememberSaveable { mutableStateOf(false) }
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    // 计价口径只有一份：Duty.mark 标好 night / burst / duty / saved，界面只负责分组展示。
    val marked = remember(state.todayEvents, state.tariff) {
        Duty.mark(state.todayEvents, state.tariff)
    }
    val allGroups = remember(marked) { groupBursts(marked) }
    // 筛选计数：让「夜间/连发/群发」先告诉用户有几个，再决定点不点。
    val filterCounts = remember(marked) {
        intArrayOf(
            marked.size,
            marked.count { it.night },
            marked.count { it.burst },
            marked.count { it.notice },
        )
    }
    // 「连发」按整组命中：组头和后续条目一起显示，不把一组拆散。
    val groups = remember(allGroups, filter) {
        when (filter) {
            1 -> allGroups.filter { it.hasNight }
            2 -> allGroups.filter { it.hasBurst }
            3 -> allGroups.filter { it.hasNotice }
            else -> allGroups
        }
    }
    val allAppGroups = remember(marked) { groupByApp(marked) }
    val appGroups = remember(allAppGroups, filter) {
        when (filter) {
            1 -> allAppGroups.filter { g -> g.events.any { it.night } }
            2 -> allAppGroups.filter { g -> g.events.any { it.burst } }
            3 -> allAppGroups.filter { g -> g.events.any { it.notice } }
            else -> allAppGroups
        }
    }
    val maxAppDuty = (appGroups.maxOfOrNull { it.duty } ?: 1).coerceAtLeast(1)
    val invNow = state.invoice
    val lastEventAt = state.lastEventAt
    // 首屏窗口：只有进页面后的前 900ms 内组合出来的行才播错峰，滚动/筛选新增的行只走 animateItem。
    val entranceOpen = rememberEntranceWindow()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "明细",
                latin = "LEDGER ${Fiscal.iso(Fiscal.today())} / 原文只在本机",
                right = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QlButton(
                            label = "核对",
                            onClick = { state.openAudit() },
                            compact = true,
                        )
                        Spacer(Modifier.width(10.dp))
                        SyncBadge(state)
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                StatCell("入账", "${state.todayEvents.size}", "条", Modifier.weight(1f))
                StatCell(
                    label = "跳过",
                    value = "${state.filteredToday}",
                    unit = "条",
                    modifier = Modifier.weight(1f),
                    onClick = { state.openAudit() },
                )
                StatCell("待同步", "${state.pending}", "条", Modifier.weight(1f))
                val balance = invNow.balanceParts()
                StatCell(
                    label = balance.label,
                    value = "${balance.absSeconds}",
                    unit = "秒",
                    modifier = Modifier.weight(1.15f),
                    valueColor = if (balance.owed) QL.Seal else QL.Green,
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                FilterCell("全部", filterCounts[0], GlyphKind.LIST, filter == 0, Modifier.weight(1f)) { filter = 0 }
                FilterCell("夜间", filterCounts[1], GlyphKind.MOON, filter == 1, Modifier.weight(1f)) { filter = 1 }
                FilterCell("连发", filterCounts[2], GlyphKind.BURST, filter == 2, Modifier.weight(1f)) { filter = 2 }
                FilterCell("群发", filterCounts[3], GlyphKind.MEGAPHONE, filter == 3, Modifier.weight(1f)) { filter = 3 }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MicroLabel("视图")
                Spacer(Modifier.width(8.dp))
                ViewCell("按时间", !byApp) { byApp = false }
                Spacer(Modifier.width(6.dp))
                ViewCell("按应用", byApp) { byApp = true }
                Spacer(Modifier.weight(1f))
                MicroLabel(
                    when {
                        lastEventAt > 0 -> "最近入账 ${Fiscal.clockText(lastEventAt)}"
                        // 本机账本有今天的事件（例如从备份恢复/导入）时，用最后一条的时间兜底。
                        state.todayEvents.isNotEmpty() ->
                            "最近入账 ${Fiscal.clockText(state.todayEvents.maxOf { it.ts })}"
                        else -> "今天还没入账"
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f)) {
                if (state.todayEvents.isNotEmpty()) {
                    val empty = if (byApp) appGroups.isEmpty() else groups.isEmpty()
                    when {
                        empty -> item {
                            EmptyLedgerState(
                                title = "这个筛选下没有记录",
                                note = "",
                                actionLabel = "看全部",
                                onAction = { filter = 0 },
                                modifier = qlItemMotion(0, entranceOpen),
                            )
                        }
                        byApp -> itemsIndexed(
                            items = appGroups,
                            key = { _, g -> g.pkg.ifBlank { g.app } },
                        ) { index, g ->
                            AppSummaryRow(
                                g = g,
                                maxDuty = maxAppDuty,
                                onClick = {
                                    // 「其他」汇总行没有包名，点开趋势会是一张空表；直接不响应。
                                    if (g.pkg.isNotBlank()) state.openTrend(g.pkg)
                                },
                                modifier = qlItemMotion(index, entranceOpen),
                            )
                        }
                        else -> itemsIndexed(
                            items = groups,
                            key = { _, g -> g.key },
                        ) { index, g ->
                            val motion = qlItemMotion(index, entranceOpen)
                            if (g.count == 1) {
                                LedgerEventRow(
                                    m = g.head,
                                    onClick = { state.selectEntry(g.head.event) },
                                    modifier = motion,
                                )
                            } else {
                                LedgerGroupRow(
                                    group = g,
                                    expanded = expandedGroups.contains(g.key),
                                    onToggle = {
                                        expandedGroups = if (expandedGroups.contains(g.key)) {
                                            expandedGroups - g.key
                                        } else {
                                            expandedGroups + g.key
                                        }
                                    },
                                    onEntry = { state.selectEntry(it.event) },
                                    modifier = motion,
                                )
                            }
                        }
                    }
                } else if (!state.listenerOn) {
                    item {
                        EmptyLedgerState(
                            title = "通知使用权没开",
                            note = "税单不会自动记账。先开启才能记录打扰。",
                            actionLabel = "去开启",
                            onAction = { state.openListenerSettings() },
                            modifier = qlItemMotion(0, entranceOpen),
                        )
                    }
                } else if (state.listenerTrouble) {
                    item {
                        EmptyLedgerState(
                            title = "监听还没连上",
                            note = "权限开着，但系统还没连上监听服务。",
                            actionLabel = "重连",
                            onAction = { state.reconnectListener() },
                            modifier = qlItemMotion(0, entranceOpen),
                        )
                    }
                } else {
                    item {
                        EmptyLedgerState(
                            title = "今天还没有打扰",
                            note = "有通知进来就会记一笔，按天结算。",
                            actionLabel = "看看报表",
                            onAction = { state.tab = Tab.WEEKLY },
                            modifier = qlItemMotion(0, entranceOpen),
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphChip(GlyphKind.COUNT, "${state.todayEvents.size}", color = QL.InkFaint)
                        Spacer(Modifier.width(14.dp))
                        GlyphChip(GlyphKind.SAVED, "${invNow.savedSeconds} 秒", color = QL.InkFaint)
                        Spacer(Modifier.width(14.dp))
                        GlyphChip(GlyphKind.CHART, "待同步 ${state.pending}", color = QL.InkFaint)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        state.trendPkg?.let { pkg -> TrendSheet(state, pkg) }
    }
}

@Composable
private fun LedgerEventRow(
    m: MarkedEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LedgerRowScaffold(
        modifier = modifier,
        onClick = onClick,
        lead = { LedgerLead(Fiscal.hm(m.event.ts)) },
        icon = { AppIcon(m.event.pkg, size = 20.dp, label = m.event.app) },
        content = {
            Text(m.event.app, style = QL.h3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                m.event.title.ifBlank { "（无正文）" },
                style = QL.bodySoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            // 单条也给出税秒，和组头/子行的金额列保持一致的阅读顺序。
            Spacer(Modifier.width(8.dp))
            MonoText(
                "${m.duty} 秒",
                style = QL.monoSmall,
                color = QL.InkSoft,
                modifier = Modifier.width(50.dp),
                align = TextAlign.End,
            )
            if (m.night) Tag("夜")
            if (m.burst) Tag("连")
            if (m.dnd) Tag("静")
            if (m.notice) Tag("群")
        },
    )
    HairHLine(color = QL.RuleSoft)
}

/** 连发折叠行：默认收起，点一下展开逐条；每条展开后仍可点开删除 / 忽略。 */
@Composable
private fun LedgerGroupRow(
    group: EventGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEntry: (MarkedEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LedgerRowScaffold(
        modifier = modifier,
        onClick = onToggle,
        verticalPadding = 7.dp,
        lead = { LedgerLead(spanText(group)) },
        icon = { AppIcon(group.pkg, size = 20.dp, label = group.head.event.app) },
        content = {
            Text(group.head.event.app, style = QL.h3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphChip(GlyphKind.COUNT, "${group.count}", color = QL.InkFaint)
                Spacer(Modifier.width(10.dp))
                GlyphChip(GlyphKind.SAVED, "${group.saved} 秒", color = QL.InkFaint)
            }
        },
        trailing = {
            MonoText(
                "${group.duty} 秒",
                style = QL.monoStrong,
                modifier = Modifier.width(62.dp),
                align = TextAlign.End,
            )
            Spacer(Modifier.width(4.dp))
            FoldArrow(expanded)
        },
    )
    HairHLine(color = QL.RuleSoft)
    if (expanded) {
        group.events.asReversed().forEach { m ->
            LedgerChildRow(m) { onEntry(m) }
        }
    }
}

/** 展开子行：时间列与父行同宽同左端，正文左端与父行应用名同一条竖线。 */
@Composable
private fun LedgerChildRow(m: MarkedEvent, onClick: () -> Unit) {
    LedgerRowScaffold(
        onClick = onClick,
        verticalPadding = 5.dp,
        lead = { LedgerLead(Fiscal.hm(m.event.ts)) },
        icon = { AppIcon(m.event.pkg, size = 20.dp, label = m.event.app) },
        content = {
            Text(
                m.event.title.ifBlank { "（无正文）" },
                style = QL.bodySoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            Spacer(Modifier.width(8.dp))
            MonoText(
                "${m.duty} 秒",
                style = QL.monoSmall,
                color = QL.InkSoft,
                modifier = Modifier.width(50.dp),
                align = TextAlign.End,
            )
            if (m.night) Tag("夜")
            if (m.burst) Tag("连")
            if (m.dnd) Tag("静")
            if (m.notice) Tag("群")
        },
    )
    HairHLine(color = QL.RuleSoft)
}

@Composable
private fun AppSummaryRow(
    g: AppGroup,
    maxDuty: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 按应用视图没有时间列：图标直接从 20dp 开始，不再为空的时间/图标列留 92dp。
    Row(
        modifier
            .fillMaxWidth()
            .qlClickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(g.pkg, size = 20.dp, label = g.app)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    g.app,
                    style = QL.h3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MonoText("${g.count} 条", style = QL.monoSmall, color = QL.InkSoft)
                Spacer(Modifier.width(10.dp))
                MonoText(
                    "${g.duty} 秒",
                    style = QL.monoStrong,
                    modifier = Modifier.width(52.dp),
                    align = TextAlign.End,
                )
            }
            Spacer(Modifier.height(4.dp))
            MeterBar(g.duty.toFloat() / maxDuty, height = 3.dp)
        }
        Spacer(Modifier.width(8.dp))
        Chevron()
    }
    HairHLine(color = QL.RuleSoft)
}

/**
 * 应用趋势：票据税目、明细「按应用」、明细行都能点进来。
 *
 * 审计 5.2 的回答不是「今天几条」，而是「这个应用是偶发，还是每天都在吵」：
 * 今天 / 近 7 天 / 近 30 天三档柱图 + 今天的记录 + 不再记录此应用的出口。
 */
@Composable
private fun TrendSheet(state: AppState, pkg: String) {
    val days = state.trendDays
    val context = LocalContext.current
    var trend by remember(pkg, days) { mutableStateOf<AppTrend?>(null) }
    var expanded by remember(pkg) { mutableStateOf(emptySet<String>()) }
    var pickedDay by remember(pkg) { mutableStateOf<String?>(null) }
    LaunchedEffect(pkg, days) {
        trend = null
        trend = runCatching { state.loadTrend(pkg, days) }.getOrNull()
    }
    val todayEvents = remember(state.todayEvents, pkg) {
        state.todayEvents.filter { it.pkg == pkg }
    }
    val bursts = remember(todayEvents, state.tariff) {
        groupBursts(Duty.mark(todayEvents, state.tariff))
    }
    val t = trend
    val title = t?.app?.takeIf { it.isNotBlank() }
        ?: state.todayEvents.firstOrNull { it.pkg == pkg }?.app
        ?: runCatching { AppNames.label(context, pkg) }.getOrDefault("应用")
    Box {
        QlSheet(
            title = title,
            subtitle = when {
                t == null -> "正在统计…"
                days == 1 -> "今天 ${t.totalEvents} 条，${t.totalDuty} 秒"
                else -> "近 $days 天 ${t.totalEvents} 条，${t.totalDuty} 秒"
            },
            onClose = state::closeTrend,
            scrollable = true,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterCell("今天", 0, GlyphKind.LIST, days == 1, Modifier.weight(1f)) {
                        state.trendDays = 1
                    }
                    FilterCell("近 7 天", 0, GlyphKind.CHART, days == 7, Modifier.weight(1f)) {
                        state.trendDays = 7
                    }
                    FilterCell("近 30 天", 0, GlyphKind.CALENDAR, days == 30, Modifier.weight(1f)) {
                        state.trendDays = 30
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (t == null) {
                    SkeletonBlock(height = 104.dp)
                } else if (t.totalEvents == 0) {
                    Text("这段时间没有这个应用的记录。", style = QL.bodySoft)
                } else {
                    // 复用报表柱图：同一条基线、同样的点柱下钻。
                    ReportBars(
                        buckets = t.points.map { p ->
                            ReportBucket(p.date, p.duty, p.events, start = p.date, end = p.date)
                        },
                        monthBuckets = false,
                        onPickDay = { pickedDay = it },
                        onPickMonth = {},
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphChip(GlyphKind.SAVED, "${t.saved} 秒", color = QL.InkFaint)
                        Spacer(Modifier.width(14.dp))
                        GlyphChip(
                            GlyphKind.CHART,
                            "日均 ${if (days <= 1) "${t.totalDuty}" else "${t.totalDuty / days}"} 秒",
                            color = QL.InkFaint,
                        )
                    }
                    if (days > 1) {
                        Spacer(Modifier.height(6.dp))
                        MicroLabel(if (t.maxDuty <= t.totalDuty / 3) "多数天都有记录，建议限制这个应用" else "集中在少数几天")
                    }
                }
                Spacer(Modifier.height(10.dp))
                HairHLine(color = QL.RuleSoft)
                Spacer(Modifier.height(8.dp))
                MicroLabel("今天的记录 / ${todayEvents.size} 条")
                Spacer(Modifier.height(2.dp))
                if (todayEvents.isEmpty()) {
                    Text("今天没有记录。", style = QL.bodySoft)
                } else {
                    bursts.forEach { g ->
                        if (g.count == 1) {
                            AppEventRow(g.head) { state.selectEntry(g.head.event) }
                        } else {
                            val isOpen = expanded.contains(g.key)
                            LedgerRowScaffold(
                                onClick = {
                                    expanded = if (isOpen) expanded - g.key else expanded + g.key
                                },
                                horizontalPadding = 0.dp,
                                verticalPadding = 7.dp,
                                lead = { LedgerLead(spanText(g)) },
                                icon = { AppIcon(pkg, size = 20.dp, label = title) },
                                content = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        GlyphChip(GlyphKind.COUNT, "${g.count}", color = QL.InkSoft)
                                        Spacer(Modifier.width(10.dp))
                                        GlyphChip(GlyphKind.SAVED, "${g.saved} 秒", color = QL.InkFaint)
                                    }
                                },
                                trailing = {
                                    Spacer(Modifier.width(8.dp))
                                    MonoText("${g.duty} 秒", style = QL.monoStrong, color = QL.Ink)
                                    Spacer(Modifier.width(4.dp))
                                    FoldArrow(isOpen)
                                },
                            )
                            HairHLine(color = QL.RuleSoft)
                            if (isOpen) {
                                g.events.asReversed().forEach { m ->
                                    AppEventRow(m) { state.selectEntry(m.event) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                QlButton(
                    label = "不再记录这个应用",
                    onClick = {
                        state.ignoreApp(pkg)
                        state.closeTrend()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    haptic = HapticFeedbackType.LongPress,
                )
                Spacer(Modifier.height(6.dp))
                MicroLabel("忽略后可到设置里恢复", color = QL.InkFaint)
            }
        }
        pickedDay?.let { day ->
            DayDetailSheet(state, day) { pickedDay = null }
        }
    }
}

/** 应用下钻里的一条：正文 + 税秒 + 夜间/连发/勿扰/群发标记（应用名已在标题里）。 */
@Composable
private fun AppEventRow(m: MarkedEvent, onClick: () -> Unit) {
    LedgerRowScaffold(
        onClick = onClick,
        horizontalPadding = 0.dp,
        verticalPadding = 5.dp,
        lead = { LedgerLead(Fiscal.hm(m.event.ts)) },
        icon = { AppIcon(m.event.pkg, size = 20.dp, label = m.event.app) },
        content = {
            Text(
                m.event.title.ifBlank { "（无正文）" },
                style = QL.bodySoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            Spacer(Modifier.width(8.dp))
            MonoText(
                "${m.duty} 秒",
                style = QL.monoSmall,
                color = QL.InkSoft,
                modifier = Modifier.width(50.dp),
                align = TextAlign.End,
            )
            if (m.night) Tag("夜")
            if (m.burst) Tag("连")
            if (m.dnd) Tag("静")
            if (m.notice) Tag("群")
        },
    )
    HairHLine(color = QL.RuleSoft)
}

@Composable
private fun EmptyLedgerState(
    title: String,
    note: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(20.dp)) {
        Text(title, style = QL.h2)
        Spacer(Modifier.height(4.dp))
        Text(note, style = QL.bodySoft)
        Spacer(Modifier.height(10.dp))
        QlButton(actionLabel, onAction, compact = true)
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueColor: Color = QL.Ink,
    onClick: (() -> Unit)? = null,
) {
    val tap = if (onClick != null) Modifier.qlClickable(onClick = onClick) else Modifier
    Column(modifier.then(tap).padding(vertical = 4.dp)) {
        MicroLabel(label)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            MonoText(value, style = QL.amountMd.copy(fontSize = 18.sp), color = valueColor)
            Spacer(Modifier.width(3.dp))
            MicroLabel(unit, color = QL.InkFaint)
        }
    }
}

@Composable
private fun FilterCell(
    label: String,
    count: Int,
    glyph: GlyphKind,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fg = if (selected) QL.Paper else QL.InkSoft
    Box(
        modifier
            .height(32.dp)
            .background(if (selected) QL.Ink else Color.Transparent)
            .qlClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyph, size = 11.dp, color = if (selected) QL.Paper.copy(alpha = 0.85f) else QL.InkFaint)
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = QL.label.copy(color = fg, fontSize = 10.sp, letterSpacing = 0.4.sp),
            )
            if (count > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    "$count",
                    style = QL.micro.copy(
                        fontSize = 8.5.sp,
                        letterSpacing = 0.sp,
                        color = if (selected) QL.Paper.copy(alpha = 0.7f) else QL.InkFaint,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ViewCell(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(22.dp)
            .background(if (selected) QL.Ink else Color.Transparent)
            .qlClickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = QL.label.copy(
                fontSize = 9.5.sp,
                color = if (selected) QL.Paper else QL.InkSoft,
                letterSpacing = 1.sp,
            ),
        )
    }
}

@Composable
internal fun Tag(text: String) {
    val kind = when (text) {
        "夜" -> GlyphKind.MOON
        "连" -> GlyphKind.BURST
        "静" -> GlyphKind.SILENT
        "群" -> GlyphKind.MEGAPHONE
        else -> GlyphKind.LIST
    }
    val label = when (text) {
        "夜" -> "夜间"
        "连" -> "连发"
        "静" -> "免打扰"
        "群" -> "群发"
        else -> text
    }
    GlyphTag(kind, label)
}

// ---------------------------------------------------------------- 报表（周 / 月 / 季 / 年）

/** 周期切换项：短标签 + 图标，选中反白；和底部 Tab 一样的方角墨块。 */
private val REPORT_RANGES = listOf(
    "d7" to "7 天",
    "d30" to "30 天",
    "month" to "月",
    "quarter" to "季",
    "year" to "年",
)

@Composable
fun ReportScreen(state: AppState) {
    val r: Report? = state.report
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var showBuckets by rememberSaveable { mutableStateOf(false) }
    val todayIso = Fiscal.iso(Fiscal.today())
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 18.dp),
        ) {
            ScreenHeader(
                title = "报表",
                latin = reportLatin(r),
                right = {
                    MicroLabel(
                        when {
                            r == null -> "正在汇总…"
                            state.reportLoading -> "正在核对…"
                            r.source == "server" -> "已结算"
                            else -> "本机估算"
                        },
                        color = if (r?.source == "server") QL.Green else QL.InkFaint,
                    )
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
                REPORT_RANGES.forEach { (key, label) ->
                    ReportRangeCell(
                        label = label,
                        selected = state.reportRange == key,
                        modifier = Modifier.weight(1f),
                    ) { state.changeReportRange(key) }
                }
            }
            // 历史月报：入口在季/年柱子上，上来后给一条明确的返回路径。
            if (state.reportAnchor.isNotBlank() && state.reportAnchor != todayIso) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(GlyphKind.CALENDAR, size = 12.dp, color = QL.InkSoft)
                    Spacer(Modifier.width(6.dp))
                    MicroLabel("${state.reportAnchor.take(7)} 月报", color = QL.InkSoft)
                    Spacer(Modifier.weight(1f))
                    QlButton("回到本月", { state.backToCurrentReport() }, compact = true)
                }
            }
            Crossfade(
                targetState = r,
                animationSpec = NavMotion.contentSwap(),
                label = "qlReportContent",
            ) { report ->
                if (report == null) {
                    ReportSkeleton()
                } else {
                    ReportLoaded(
                        state = state,
                        r = report,
                        showBuckets = showBuckets,
                        onToggleBuckets = { showBuckets = !showBuckets },
                        onPickDay = { selectedDate = it },
                        onPickMonth = { state.openMonthReport(it) },
                    )
                }
            }
        }
        selectedDate?.let { date ->
            DayDetailSheet(state, date) { selectedDate = null }
        }
        // 税目排行点进来的应用趋势：和票据/明细共用同一个弹层，不再另做一套。
        state.trendPkg?.let { pkg -> TrendSheet(state, pkg) }
    }
}

private fun reportLatin(r: Report?): String {
    if (r == null) return "REPORT"
    return when (r.kind) {
        "month" -> "MONTHLY ${r.start} — ${r.end}"
        "quarter" -> "QUARTER ${r.start} — ${r.end}"
        "year" -> "YEAR ${r.start} — ${r.end}"
        else -> "LAST ${r.dayCount} DAYS"
    }
}

/** 报表加载态：骨架高度对齐正式票据，交叉淡入不跳。 */
@Composable
private fun ReportSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 8.dp)) {
        ReceiptPaper {
            SkeletonBlock(height = 12.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonBlock(height = 30.dp)
            Spacer(Modifier.height(14.dp))
            SkeletonBlock(height = 104.dp)
        }
        Spacer(Modifier.height(12.dp))
        SkeletonBlock(height = 36.dp)
    }
}

@Composable
private fun ReportLoaded(
    state: AppState,
    r: Report,
    showBuckets: Boolean,
    onToggleBuckets: () -> Unit,
    onPickDay: (String) -> Unit,
    onPickMonth: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        ReceiptPaper(Modifier.padding(horizontal = 18.dp)) {
            // 顶部：这期一共多少秒，比上期是涨是跌。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    MicroLabel("${reportTitle(r)} 应缴合计 / TOTAL")
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${r.totalDuty}", style = QL.amountLg.copy(fontSize = 44.sp))
                        Spacer(Modifier.width(4.dp))
                        Text("秒", style = QL.h2)
                    }
                    if (r.totalDuty >= 60) {
                        MonoText("约 ${Fmt.seconds(r.totalDuty)}", style = QL.monoSmall, color = QL.InkSoft)
                    }
                    Spacer(Modifier.height(3.dp))
                    MicroLabel("${r.start} — ${r.end}   共 ${r.totalEvents} 条")
                }
            }
            Spacer(Modifier.height(9.dp))
            CompareRow(r)
            Spacer(Modifier.height(11.dp))
            DashedHLine(dashOn = 2.dp, dashOff = 3.dp)
            Spacer(Modifier.height(9.dp))
            MicroLabel(if (r.monthBuckets) "每月应缴 / BY MONTH" else "每日应缴 / DAILY")
            Spacer(Modifier.height(6.dp))
            ReportBars(
                buckets = r.buckets,
                monthBuckets = r.monthBuckets,
                onPickDay = onPickDay,
                onPickMonth = onPickMonth,
            )
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphChip(GlyphKind.COUNT, "${r.totalEvents}", color = QL.InkFaint)
                Spacer(Modifier.width(14.dp))
                GlyphChip(GlyphKind.SAVED, "${r.savedSeconds} 秒", color = QL.InkFaint)
                Spacer(Modifier.width(14.dp))
                GlyphChip(GlyphKind.MOON, "${Fmt.percent(r.nightRatio)}", color = QL.InkFaint)
                Spacer(Modifier.weight(1f))
                GlyphChip(GlyphKind.CHART, "日均 ${avgText(r)}", color = QL.InkFaint)
            }
            Spacer(Modifier.height(12.dp))
            DashedHLine(dashOn = 2.dp, dashOff = 3.dp)
            Spacer(Modifier.height(9.dp))
            MicroLabel("税目排行 / TOP ITEMS")
            Spacer(Modifier.height(4.dp))
            if (r.topApps.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("这段时间没有入账。", style = QL.bodySoft)
            } else {
                val maxDuty = (r.topApps.maxOfOrNull { it.duty } ?: 1).coerceAtLeast(1)
                r.topApps.forEachIndexed { index, app ->
                    ReportAppRow(index, app, maxDuty) {
                        // 有包名才能下钻到趋势；服务器旧版本没带 pkg 时不可点。
                        if (app.pkg.isNotBlank()) state.openTrend(app.pkg)
                    }
                }
                Spacer(Modifier.height(5.dp))
                MicroLabel(insightText(r))
            }
            Spacer(Modifier.height(12.dp))
            DashedHLine(dashOn = 2.dp, dashOff = 3.dp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MicroLabel(if (r.monthBuckets) "逐月账单 / MONTHS" else "逐日账单 / DAYS")
                Spacer(Modifier.weight(1f))
                QlButton(
                    if (showBuckets) "收起" else "展开",
                    onToggleBuckets,
                    compact = true,
                )
            }
            Spacer(Modifier.height(2.dp))
            if (showBuckets) {
                r.buckets.asReversed().forEach { b ->
                    ReportBucketRow(
                        bucket = b,
                        monthBuckets = r.monthBuckets,
                        onClick = {
                            if (r.monthBuckets) onPickMonth(b.start) else onPickDay(b.start)
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            DashedHLine(dashOn = 2.dp, dashOff = 3.dp)
            Spacer(Modifier.height(8.dp))
            QlButton(
                label = "复制本期摘要",
                onClick = { state.copyText(Insights.reportSummary(r), "本期摘要") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("北京时间结算，免税额每天重置。", style = QL.bodySoft)
            Spacer(Modifier.height(8.dp))
            QlButton(
                if (state.reportLoading) "核对中…" else "刷新报表",
                { state.refreshReport() },
                compact = true,
                enabled = !state.reportLoading,
            )
        }
    }
}

/** 上期对比：上涨用税红、下降用墨绿，没有上期数据时不硬编一个 0%。 */
@Composable
private fun CompareRow(r: Report) {
    val delta = r.delta
    val prev = r.prev
    val color = when {
        delta > 0 -> QL.Seal
        delta < 0 -> QL.Green
        else -> QL.InkFaint
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val glyph = when {
            delta > 0 -> GlyphKind.TREND_UP
            delta < 0 -> GlyphKind.TREND_DOWN
            else -> GlyphKind.LIST
        }
        Glyph(glyph, size = 12.dp, color = color)
        Spacer(Modifier.width(5.dp))
        MicroLabel(compareText(r), color = color)
        Spacer(Modifier.weight(1f))
        if (prev != null && prev.totalDuty > 0) {
            MicroLabel("上期 ${prev.totalDuty} 秒")
        }
    }
}

private fun compareText(r: Report): String {
    val prev = r.prev ?: return "没有上一周期可比"
    if (prev.totalDuty == 0 && r.totalDuty == 0) return "上期也没有入账"
    if (prev.totalDuty == 0) return "上期没有入账"
    return when {
        r.delta > 0 -> "较上期多 ${r.delta} 秒"
        r.delta < 0 -> "较上期少 ${-r.delta} 秒"
        else -> "与上期持平"
    }
}

private fun reportTitle(r: Report): String {
    val start = runCatching { Fiscal.parseDate(r.start) }.getOrNull() ?: return "本期"
    return when (r.kind) {
        "month" -> "${start.monthValue} 月"
        "quarter" -> "${start.year} Q${(start.monthValue - 1) / 3 + 1}"
        "year" -> "${start.year} 年"
        else -> "近 ${r.dayCount} 天"
    }
}

private fun avgPerDay(r: Report): Int =
    if (r.dayCount <= 0) 0 else (r.totalDuty.toDouble() / r.dayCount).roundToInt()

/** 年报表 144 秒 / 315 天会四舍五入成 0；低于 1 秒时直接说「不到 1」。 */
private fun avgText(r: Report): String {
    if (r.dayCount <= 0) return "0 秒"
    val avg = r.totalDuty.toDouble() / r.dayCount
    return if (avg < 1 && r.totalDuty > 0) "<1 秒" else "${avg.roundToInt()} 秒"
}

/** 一句话洞察：最吵的应用占多少；这比一堆数字更像给用户看的东西。 */
private fun insightText(r: Report): String {
    val top = r.topApps.firstOrNull() ?: return "这段时间没有入账"
    val share = if (r.totalDuty <= 0) 0 else (top.duty * 100.0 / r.totalDuty).roundToInt()
    return "最吵是 ${top.app}，占 ${share}% 应缴"
}

/**
 * 税目排行的一行（v1.9.0）：编号 + 应用图标 + 名称 + 条数 + 秒数 + 占比条。
 * 有包名时整行可点，和「明细 → 按应用」一样能进应用趋势；行尾给个小箭头说明可点。
 */
@Composable
private fun ReportAppRow(index: Int, app: ReportApp, maxDuty: Int, onClick: () -> Unit) {
    val clickable = app.pkg.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.qlClickable(onClick = onClick) else Modifier)
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText("0${index + 1}", style = QL.micro, color = QL.Seal)
        Spacer(Modifier.width(8.dp))
        AppIcon(if (clickable) app.pkg else "", size = 20.dp, label = app.app)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.app,
                    style = QL.h3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MonoText("${app.count} 条", style = QL.monoSmall, color = QL.InkSoft)
                Spacer(Modifier.width(10.dp))
                MonoText(
                    "${app.duty} 秒",
                    style = QL.monoStrong,
                    modifier = Modifier.width(52.dp),
                    align = TextAlign.End,
                )
            }
            Spacer(Modifier.height(4.dp))
            MeterBar(app.duty.toFloat() / maxDuty, height = 3.dp)
        }
        if (clickable) {
            Spacer(Modifier.width(6.dp))
            Chevron(color = QL.Rule)
        }
    }
}

@Composable
private fun ReportBars(
    buckets: List<ReportBucket>,
    monthBuckets: Boolean,
    onPickDay: (String) -> Unit,
    onPickMonth: (String) -> Unit,
) {
    if (buckets.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        return
    }
    val max = (buckets.maxOfOrNull { it.duty } ?: 0).coerceAtLeast(1)
    val todayIso = Fiscal.iso(Fiscal.today())
    val sparse = buckets.size > 12
    Column(Modifier.fillMaxWidth()) {
        // 审计 P0-9：低数据周也能读出量级——右上角给峰值，柱顶给数值。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            MicroLabel("最高 $max 秒", color = QL.InkSoft)
        }
        Box(Modifier.fillMaxWidth().height(104.dp)) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val n = buckets.size.coerceAtLeast(1)
                val slot = size.width / n
                val gap = slot * if (sparse) 0.22f else 0.32f
                val baseY = size.height - 1f
                val topPad = 13.dp.toPx() // 给柱顶数字留一行，最大柱也不会顶到裁切边
                drawLine(QL.Rule, Offset(0f, baseY), Offset(size.width, baseY), 1f)
                val labelPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 8.5.sp.toPx()
                    color = QL.InkFaint.toArgb()
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                buckets.forEachIndexed { i, b ->
                    val hgt = if (b.duty <= 0) 1.dp.toPx() else
                        (b.duty.toFloat() / max) * (size.height - topPad)
                    // 柱子过宽会像色块：季报只有 2 根时也保持报表的细柱比例。
                    val barW = (slot - gap).coerceAtLeast(1f).coerceAtMost(56.dp.toPx())
                    val x = i * slot + (slot - barW) / 2f
                    val active = b.start == todayIso || b.end == todayIso
                    drawRect(
                        color = if (active) QL.Seal else QL.Ink,
                        topLeft = Offset(x, baseY - hgt),
                        size = Size(barW, hgt),
                    )
                    // 稀疏视图只标最高柱，避免 30 个数字叠在一起。
                    if (b.duty > 0 && (!sparse || b.duty == max)) {
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(
                                "${b.duty}",
                                x + barW / 2f,
                                baseY - hgt - 3.dp.toPx(),
                                labelPaint,
                            )
                        }
                    }
                }
            }
            Row(Modifier.matchParentSize()) {
                buckets.forEach { b ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics { contentDescription = "${barAxisLabel(b, monthBuckets)}，应缴 ${b.duty} 秒" }
                            .qlClickable {
                                if (monthBuckets) onPickMonth(b.start) else onPickDay(b.start)
                            },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (buckets.size <= 12) {
            Row(Modifier.fillMaxWidth()) {
                buckets.forEach { b ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MonoText(
                            barAxisLabel(b, monthBuckets),
                            style = QL.micro.copy(fontSize = 8.5.sp, letterSpacing = 0.4.sp),
                            color = QL.InkFaint,
                        )
                        MonoText(
                            "${b.duty}",
                            style = QL.micro.copy(fontSize = 7.5.sp, letterSpacing = 0.sp),
                            color = QL.InkFaint,
                        )
                    }
                }
            }
        } else {
            // 30/31 根柱子只标首、中、末，避免轴标签挤成一团。
            val mid = buckets[buckets.size / 2]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MicroLabel(barAxisLabel(buckets.first(), monthBuckets))
                MicroLabel(barAxisLabel(mid, monthBuckets))
                MicroLabel(barAxisLabel(buckets.last(), monthBuckets))
            }
        }
    }
}

private fun barAxisLabel(b: ReportBucket, monthBuckets: Boolean): String =
    if (monthBuckets) b.label.takeLast(2).trimStart('0').ifBlank { "0" }
    else b.label.takeLast(2)

@Composable
private fun ReportBucketRow(
    bucket: ReportBucket,
    monthBuckets: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().qlClickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (monthBuckets) {
            Glyph(GlyphKind.CALENDAR, size = 12.dp, color = QL.InkFaint)
            Spacer(Modifier.width(7.dp))
        }
        MonoText(
            if (monthBuckets) bucket.label else bucket.start.ifBlank { bucket.label },
            style = QL.monoSmall,
            color = QL.InkSoft,
        )
        Spacer(Modifier.weight(1f))
        MonoText("${bucket.events} 条", style = QL.micro, color = QL.InkFaint)
        Spacer(Modifier.width(10.dp))
        MonoText("${bucket.duty} 秒", style = QL.monoStrong, modifier = Modifier.width(56.dp), align = TextAlign.End)
        Spacer(Modifier.width(6.dp))
        Chevron()
    }
    HairHLine(color = QL.RuleSoft)
}

@Composable
private fun ReportRangeCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 46.dp)
            .background(if (selected) QL.Ink else Color.Transparent)
            .qlClickable(onClick = onClick)
            .semantics {
                this.selected = selected
                role = Role.Tab
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = QL.label.copy(
                color = if (selected) QL.Paper else QL.InkSoft,
                letterSpacing = 1.0.sp,
            ),
        )
    }
}
