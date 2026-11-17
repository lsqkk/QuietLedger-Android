package dev.quietledger.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quietledger.app.core.Duty
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.core.Fmt
import dev.quietledger.app.core.Invoice
import dev.quietledger.app.core.MarkedEvent
import dev.quietledger.app.core.NotifEvent
import dev.quietledger.app.core.Tariff
import dev.quietledger.app.core.balanceParts
import dev.quietledger.app.data.LedgerDb
import dev.quietledger.app.data.Prefs
import dev.quietledger.app.net.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 周报里点某一天后打开的当日明细。
 *
 * 数据两条路：本机账本当天的记录先算一遍（断网/未登录也能看），
 * 有登录凭证时再向服务器要这一天结算后的税单；两边按事件条数取多的一方展示，
 * 并在标题下标注「服务器结算 / 本机估算」，避免数字对不上时说不清来源。
 *
 * v1.8.0：
 *  - 票面合计与票据同序（审计 P0-1）：计税（原价 / 优惠 / 应缴）+ 结算（每日免税额 / 结余或欠税），
 *    结余/欠税统一走 [balanceParts]，副标题不再拼「免税额」；
 *  - 弹层内可以往前 / 后翻一天（审计 P2-7）：只改 [currentIso]，取数与交叉淡入全部跟着重置，
 *    不跨过今天；
 *  - 空态与标题不再写解释句（审计 P2-6）。
 * 页面只读，不修改任何账本状态。
 */
@Composable
fun DayDetailSheet(state: AppState, dateIso: String, onClose: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    // 弹层内前后日切换：只改这一个状态，下面的取数/加载态/标题全部由它驱动。
    var currentIso by remember(dateIso) { mutableStateOf(dateIso) }
    var localInvoice by remember(currentIso) { mutableStateOf<Invoice?>(null) }
    var localEvents by remember(currentIso) { mutableStateOf<List<NotifEvent>>(emptyList()) }
    var serverInvoice by remember(currentIso) { mutableStateOf<Invoice?>(null) }
    var serverChecking by remember(currentIso) { mutableStateOf(false) }
    var serverFailed by remember(currentIso) { mutableStateOf(false) }

    LaunchedEffect(currentIso) {
        val day = runCatching { Fiscal.parseDate(currentIso) }.getOrNull() ?: return@LaunchedEffect
        val tariff = state.tariff
        val local = withContext(Dispatchers.IO) {
            val account = Prefs.accountCode(appContext) ?: "未开户"
            val events = LedgerDb.get(appContext)
                .eventsBetween(Fiscal.dayStart(day), Fiscal.dayEnd(day))
            Duty.compute(events, tariff, day, Fiscal.today(), account) to events
        }
        localInvoice = local.first
        localEvents = local.second

        val token = Prefs.token(appContext)
        if (token == null) return@LaunchedEffect
        serverChecking = true
        val deviceId = state.deviceId
        val server = withContext(Dispatchers.IO) {
            runCatching { Api.invoice(token, deviceId, currentIso) }.getOrNull()
        }
        serverInvoice = server?.takeIf { it.date == currentIso }
        serverFailed = server == null
        serverChecking = false
    }

    val local = localInvoice
    val server = serverInvoice
    // 服务器条数不少于本机时才采信服务器，否则用本机实时账本（与票据页同一口径）。
    val shown: Invoice? = when {
        server != null && (local == null || server.events >= local.events) -> server
        local != null -> local
        else -> server
    }
    val usingServer = shown != null && shown === server
    val currentDay = runCatching { Fiscal.parseDate(currentIso) }.getOrNull()
    val weekday = currentDay?.let { Fiscal.weekdayZh(it) }.orEmpty()
    val todayIso = Fiscal.iso(Fiscal.today())
    val canNext = currentIso < todayIso
    val sourceText = when {
        shown == null -> "正在结算…"
        usingServer -> "已结算"
        else -> "本机估算"
    }
    // 审计 P0-1：副标题只留来源与条数，免税额挪进「结算」组，避免同一数字两处出现。
    val subtitle = if (shown == null) {
        sourceText
    } else {
        "$sourceText / ${shown.events} 条"
    }
    val title = listOf(currentIso, weekday).filter { it.isNotBlank() }.joinToString(" ")

    QlSheet(title = title, subtitle = subtitle, onClose = onClose, scrollable = true) {
        // 前后日：方角文字按钮，不引入图标库；「后一天」到今天为止。
        DayNavBar(
            canPrev = currentDay != null,
            canNext = canNext,
            onPrev = { currentDay?.minusDays(1L)?.let { currentIso = Fiscal.iso(it) } },
            onNext = {
                if (canNext) currentDay?.plusDays(1L)?.let { currentIso = Fiscal.iso(it) }
            },
        )
        Spacer(Modifier.height(10.dp))
        // 加载态与正式内容交叉淡入；骨架块高度贴近真实内容，避免「闪一下再跳大」。
        Crossfade(
            targetState = shown != null,
            animationSpec = NavMotion.contentSwap(),
            label = "qlDayDetail",
        ) { ready ->
            Column(Modifier.fillMaxWidth()) {
                val inv = shown
                if (!ready) {
                    DayDetailSkeleton()
                } else if (inv != null) {
                    if (serverChecking) {
                        MicroLabel("正在同步核对…")
                        Spacer(Modifier.height(6.dp))
                    } else if (serverFailed && !usingServer) {
                        MicroLabel("同步没连上，按本机算", color = QL.Amber)
                        Spacer(Modifier.height(6.dp))
                    }
                    DaySummary(inv)
                    Spacer(Modifier.height(9.dp))
                    DashedHLine(dashOn = 2.dp, dashOff = 3.dp)
                    Spacer(Modifier.height(8.dp))
                    MicroLabel("24 小时分布 / 24H")
                    Spacer(Modifier.height(5.dp))
                    HourStrip(inv.byHour, state.tariff)
                    Spacer(Modifier.height(10.dp))
                    DashedHLine(dashOn = 2.dp, dashOff = 3.dp)
                    Spacer(Modifier.height(8.dp))
                    MicroLabel("应用排行 / TOP")
                    Spacer(Modifier.height(4.dp))
                    DayTopApps(inv)
                    Spacer(Modifier.height(10.dp))
                    DashedHLine(dashOn = 2.dp, dashOff = 3.dp)
                    Spacer(Modifier.height(8.dp))
                    MicroLabel("本机通知 ${localEvents.size} 条")
                    Spacer(Modifier.height(4.dp))
                    DayEventList(localEvents, state.tariff)
                    Spacer(Modifier.height(10.dp))
                    QlButton(
                        label = "复制这天的摘要",
                        onClick = { state.copyText(daySummaryText(currentIso, weekday, inv), "当天摘要") },
                        modifier = Modifier.fillMaxWidth(),
                        primary = true,
                    )
                }
            }
        }
    }
}

/** 前后日切换：两个 1dp 方角文字按钮，禁用态只用更淡的线色，不隐藏（避免标题行跳动）。 */
@Composable
private fun DayNavBar(canPrev: Boolean, canNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayNavButton("‹ 前一天", enabled = canPrev, onClick = onPrev)
        Spacer(Modifier.width(8.dp))
        DayNavButton("后一天 ›", enabled = canNext, onClick = onNext)
    }
}

@Composable
private fun DayNavButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = QL.label.copy(color = if (enabled) QL.InkSoft else QL.Rule),
        modifier = Modifier
            .border(1.dp, if (enabled) QL.Rule else QL.RuleSoft)
            .then(if (enabled) Modifier.qlClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        maxLines = 1,
    )
}

/** 加载态骨架：计税/结算几行 + 24 小时分布 + 应用行的占位，纯色块不做 shimmer。 */
@Composable
private fun DayDetailSkeleton() {
    Column(Modifier.fillMaxWidth()) {
        SkeletonBlock(height = 10.dp)
        Spacer(Modifier.height(8.dp))
        SkeletonBlock(height = 16.dp)
        Spacer(Modifier.height(8.dp))
        SkeletonBlock(height = 16.dp)
        Spacer(Modifier.height(16.dp))
        SkeletonBlock(height = 34.dp)
        Spacer(Modifier.height(16.dp))
        SkeletonBlock(height = 20.dp)
    }
}

/**
 * 票面合计：与票据页同序（审计 P0-1）。
 * 计税：原价（总应缴 + 优惠）→ 优惠 → 应缴；结算：每日免税额 → 结余 / 欠税。
 * 优惠只在 `savedSeconds > 0` 时出现，原价跟着一起出现（否则原价 == 应缴，两行重复）。
 */
@Composable
private fun DaySummary(inv: Invoice) {
    val balance = inv.balanceParts()
    MicroLabel("计税 / DUTY")
    Spacer(Modifier.height(3.dp))
    if (inv.savedSeconds > 0) {
        // 优惠行用 InkSoft 且不带负号：票据上「减」的意思由「原价 → 应缴」的顺序表达。
        DayTotalRow("原价", "LIST", "${inv.totalDuty + inv.savedSeconds} 秒")
        DayTotalRow("优惠", "SAVED", "${inv.savedSeconds} 秒", valueColor = QL.InkSoft)
    }
    DayTotalRow("应缴", "DUTY", "${inv.totalDuty} 秒", strong = true)
    Spacer(Modifier.height(9.dp))
    HairHLine(color = QL.RuleSoft)
    Spacer(Modifier.height(9.dp))
    MicroLabel("结算 / SETTLE")
    Spacer(Modifier.height(3.dp))
    DayTotalRow("每日免税额", "EXEMPT", "${inv.exemption} 秒")
    // 欠税用 QL.Seal，结余用 QL.Green；值只给绝对值，不出现负结余。
    DayTotalRow(
        balance.label,
        balance.latin,
        balance.valueText,
        valueColor = if (balance.owed) QL.Seal else QL.Green,
        strong = true,
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlyphChip(GlyphKind.COUNT, "${inv.events}", color = QL.InkFaint)
        Spacer(Modifier.width(14.dp))
        GlyphChip(GlyphKind.MOON, Fmt.percent(inv.nightRatio), color = QL.InkFaint)
    }
}

/** 合计行：标签 + 英文微标签 + 点状引导线 + 等宽数值（数值右对齐成一条线）。 */
@Composable
private fun DayTotalRow(
    label: String,
    latin: String,
    value: String,
    valueColor: Color = QL.Ink,
    strong: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = QL.bodySoft.copy(fontSize = 13.sp), maxLines = 1)
        Spacer(Modifier.width(6.dp))
        MicroLabel(latin)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).padding(horizontal = 4.dp).height(1.dp).background(QL.RuleSoft))
        Spacer(Modifier.width(8.dp))
        MonoText(
            value,
            style = if (strong) QL.monoStrong else QL.monoSmall,
            color = valueColor,
        )
    }
}

@Composable
private fun DayTopApps(inv: Invoice) {
    if (inv.lines.isEmpty()) {
        Text("这天没有税目。", style = QL.bodySoft)
        return
    }
    val maxDuty = (inv.lines.maxOfOrNull { it.duty } ?: 1).coerceAtLeast(1)
    inv.lines.take(5).forEachIndexed { index, line ->
        Column(Modifier.fillMaxWidth().padding(top = 7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MonoText("0${index + 1}", style = QL.micro, color = QL.Seal)
                Spacer(Modifier.width(8.dp))
                // 汇总行（「其他」/无包名）留同宽占位，保证排行里应用名同一条线。
                val iconPkg = if (line.pkg.isNotBlank() && line.app != "其他") line.pkg else ""
                AppIcon(iconPkg, size = 20.dp, label = line.app)
                Spacer(Modifier.width(6.dp))
                Text(
                    line.app,
                    style = QL.h3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MonoText("${line.count} 条", style = QL.monoSmall, color = QL.InkSoft)
                Spacer(Modifier.width(10.dp))
                MonoText(
                    "${line.duty} 秒",
                    style = QL.monoStrong,
                    modifier = Modifier.width(56.dp),
                    align = TextAlign.End,
                )
            }
            Spacer(Modifier.height(4.dp))
            MeterBar(line.duty.toFloat() / maxDuty, height = 3.dp)
        }
    }
}

/**
 * 这天的通知列表：和明细同一套折叠规则（同应用 120 秒内连发默认收起），
 * 分组只消费 [Duty.mark] 的结果，不在 UI 里另算税秒。
 */
@Composable
private fun DayEventList(events: List<NotifEvent>, tariff: Tariff) {
    var expanded by remember(events) { mutableStateOf(emptySet<String>()) }
    val groups = remember(events, tariff) {
        val marked = Duty.mark(events, tariff)
        groupBursts(marked.takeLast(60))
    }
    if (groups.isEmpty()) {
        // 审计 P2-6：空态只说事实，不再解释「可能来自其它设备」。
        Text("这天本机没有记录。", style = QL.bodySoft)
        return
    }
    groups.forEach { g ->
        if (g.count == 1) {
            DayEventRow(g.head)
        } else {
            val isOpen = expanded.contains(g.key)
            LedgerRowScaffold(
                onClick = {
                    expanded = if (isOpen) expanded - g.key else expanded + g.key
                },
                horizontalPadding = 0.dp,
                verticalPadding = 6.dp,
                lead = { LedgerLead(daySpan(g)) },
                icon = { AppIcon(g.pkg, size = 20.dp, label = g.head.event.app) },
                content = {
                    Text(
                        g.head.event.app,
                        style = QL.h3.copy(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphChip(GlyphKind.COUNT, "${g.count}", color = QL.InkFaint)
                        Spacer(Modifier.width(10.dp))
                        GlyphChip(GlyphKind.SAVED, "${g.saved} 秒", color = QL.InkFaint)
                    }
                },
                trailing = {
                    Spacer(Modifier.width(8.dp))
                    MonoText(
                        "${g.duty} 秒",
                        style = QL.monoStrong,
                        modifier = Modifier.width(56.dp),
                        align = TextAlign.End,
                    )
                    Spacer(Modifier.width(4.dp))
                    FoldArrow(isOpen)
                },
            )
            HairHLine(color = QL.RuleSoft)
            if (isOpen) {
                g.events.asReversed().forEach { m -> DayEventRow(m) }
            }
        }
    }
    if (events.size > 60) {
        Spacer(Modifier.height(4.dp))
        MicroLabel("只显示最近 60 条，共 ${events.size} 条")
    }
}

@Composable
private fun DayEventRow(m: MarkedEvent) {
    LedgerRowScaffold(
        horizontalPadding = 0.dp,
        verticalPadding = 5.dp,
        lead = { LedgerLead(Fiscal.hm(m.event.ts)) },
        icon = { AppIcon(m.event.pkg, size = 20.dp, label = m.event.app) },
        content = {
            Text(
                m.event.app,
                style = QL.h3.copy(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

private fun daySpan(g: EventGroup): String {
    val start = Fiscal.hm(g.head.event.ts)
    val end = Fiscal.hm(g.tail.event.ts)
    return if (start == end) start else "$start–$end"
}

/**
 * 复制到聊天框的一句话摘要：与票面同序（原价 → 优惠 → 应缴；每日免税额 → 结余/欠税），
 * 金额一律正数，不再出现「优惠已减」「欠 X」这类内部口径（审计 P1-5 / §2.4）。
 */
private fun daySummaryText(dateIso: String, weekday: String, inv: Invoice): String {
    val balance = inv.balanceParts()
    val head = buildString {
        append("$dateIso $weekday：打扰 ${inv.events} 条")
        if (inv.savedSeconds > 0) {
            append("，原价 ${inv.totalDuty + inv.savedSeconds} 秒")
            append("，优惠 ${inv.savedSeconds} 秒")
        }
        append("，应缴 ${inv.totalDuty} 秒")
    }
    return "$head；每日免税额 ${inv.exemption} 秒，${balance.shortText}。"
}
