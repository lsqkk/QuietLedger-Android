package dev.quietledger.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quietledger.app.core.Duty
import dev.quietledger.app.core.ConversationClass
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.core.MarkedEvent
import dev.quietledger.app.core.NotifEvent
import dev.quietledger.app.core.Tariff
import dev.quietledger.app.core.balanceParts

/** 账簿纸底：横向浅格线 + 左侧红色装订线，替代一切渐变与卡片。 */
@Composable
fun LedgerPaper(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(QL.Paper)
            .drawBehind {
                val step = 26.dp.toPx()
                var y = step
                while (y < size.height) {
                    drawLine(QL.Ink.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    y += step
                }
                val x = 15.dp.toPx()
                drawLine(QL.Seal.copy(alpha = 0.13f), Offset(x, 0f), Offset(x, size.height), 1f)
            },
        content = content,
    )
}

@Composable
fun DashedHLine(
    modifier: Modifier = Modifier,
    color: Color = QL.Rule,
    dashOn: Dp = 5.dp,
    dashOff: Dp = 4.dp,
    stroke: Dp = 1.dp,
) {
    Canvas(modifier.fillMaxWidth().height(stroke)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = stroke.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashOn.toPx(), dashOff.toPx())),
        )
    }
}

@Composable
fun HairHLine(modifier: Modifier = Modifier, color: Color = QL.Rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun MicroLabel(text: String, color: Color = QL.InkFaint, modifier: Modifier = Modifier) {
    Text(text = text, style = QL.micro.copy(color = color), modifier = modifier)
}

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = QL.mono,
    color: Color = QL.Ink,
    align: TextAlign? = null,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        textAlign = align,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 方角按钮：默认是 1dp 墨线空框，按下反白，主按钮默认反白；按压整体缩放 0.97。 */
@Composable
fun QlButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    haptic: HapticFeedbackType? = null,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        !enabled -> Color.Transparent
        pressed -> QL.Ink
        primary -> QL.Ink
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> QL.InkFaint
        pressed -> QL.Paper
        primary -> QL.Paper
        else -> QL.Ink
    }
    Box(
        modifier
            // 缩放要包住边框和底色，否则只有文字在缩。
            .pressScale(interaction, enabled)
            .height(if (compact) 30.dp else 42.dp)
            .border(1.dp, if (enabled) QL.Ink else QL.Rule, RectangleShape)
            .background(bg, RectangleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {
                    haptic?.let { haptics.performHapticFeedback(it) }
                    onClick()
                },
            )
            .padding(horizontal = if (compact) 9.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = QL.label.copy(
                fontSize = if (compact) 9.5.sp else 11.sp,
                letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = fg,
            maxLines = 1,
        )
    }
}

/** 收据纸：比底色更亮一档，1dp 墨线外框 + 上下两条撕口虚线。 */
@Composable
fun ReceiptPaper(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(QL.PaperLight)
            .drawBehind {
                drawRect(
                    color = QL.Ink.copy(alpha = 0.30f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(9.dp))
        DashedHLine(dashOn = 3.dp, dashOff = 3.dp, color = QL.Rule)
        Spacer(Modifier.height(12.dp))
        content()
        Spacer(Modifier.height(10.dp))
        DashedHLine(dashOn = 3.dp, dashOff = 3.dp, color = QL.Rule)
        Spacer(Modifier.height(9.dp))
    }
}

/** 红色双线印章，微微压印倾斜。 */
@Composable
fun Stamp(text: String, modifier: Modifier = Modifier, color: Color = QL.Seal) {
    Box(
        modifier
            .rotate(-7f)
            .border(2.dp, color.copy(alpha = 0.85f), RectangleShape)
            .padding(3.dp)
            .border(1.dp, color.copy(alpha = 0.85f), RectangleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = QL.Sans,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.5.sp,
                color = color.copy(alpha = 0.9f),
            ),
        )
    }
}

/**
 * 比例条：只动 transform，不动 layout。
 * 内层条固定铺满轨道，用 `graphicsLayer.scaleX` 缩放到目标比例，
 * 比例变化走补间；首次组合直接到位（列表滚动出来的每一行不再重复「涨」一次）。
 */
@Composable
fun MeterBar(ratio: Float, modifier: Modifier = Modifier, color: Color = QL.Ink, height: Dp = 6.dp) {
    val safe = ratio.coerceIn(0f, 1f)
    val scale by animateFloatAsState(
        targetValue = safe,
        animationSpec = Motion.standard(Motion.Container),
        label = "qlMeterBar",
    )
    Box(modifier.fillMaxWidth().height(height).background(QL.RuleSoft)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(color),
        )
    }
}

/** 税务发票里常见的点状引导线（税目 …… 金额）。 */
@Composable
fun DottedLeader(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = QL.Rule,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f, 4f)),
        )
    }
}

/**
 * 连发折叠：同一应用同类别的连发合成一组（组头 + 后续条目），
 * 明细 / 应用下钻 / 日详情默认收起，点一下展开逐条。
 *
 * 分组只消费 [dev.quietledger.app.core.Duty.mark] 已经标好的 burst，
 * 不在 UI 里另抄一份计价逻辑；事件按 ts 升序传入，组内也按升序，
 * 展示时再倒序。
 */
data class EventGroup(val events: List<MarkedEvent>) {
    val head: MarkedEvent get() = events.first()
    val tail: MarkedEvent get() = events.last()
    val key: String get() = head.event.id
    val pkg: String get() = head.event.pkg
    val count: Int get() = events.size
    val duty: Int get() = events.sumOf { it.duty }
    val saved: Int get() = events.sumOf { it.saved }
    val hasNight: Boolean get() = events.any { it.night }
    val hasBurst: Boolean get() = events.any { it.burst }
    val hasNotice: Boolean get() = events.any { it.notice }
}

fun groupBursts(marked: List<MarkedEvent>): List<EventGroup> {
    if (marked.isEmpty()) return emptyList()
    val ascending = marked.sortedWith(compareBy<MarkedEvent> { it.event.ts }.thenBy { it.event.id })
    val open = HashMap<Pair<String, ConversationClass>, MutableList<MarkedEvent>>()
    val groups = ArrayList<MutableList<MarkedEvent>>()
    for (m in ascending) {
        val key = m.event.pkg to m.event.conversationClass
        if (!m.burst) {
            val created = mutableListOf(m)
            open[key] = created
            groups.add(created)
        } else {
            val existing = open[key]
            if (existing != null) {
                existing.add(m)
            } else {
                // 组头被筛选/截断掉了：单独成组，不要把这条件丢了。
                val created = mutableListOf(m)
                open[key] = created
                groups.add(created)
            }
        }
    }
    return groups.map { EventGroup(it.toList()) }.sortedByDescending { it.tail.event.ts }
}

/** 折叠行上的小箭头：收起时朝下，展开时朝上。 */
@Composable
fun FoldArrow(expanded: Boolean, color: Color = QL.InkFaint) {
    Canvas(Modifier.size(10.dp)) {
        val w = 1.dp.toPx()
        val edgeY = if (expanded) size.height * 0.66f else size.height * 0.34f
        val midY = if (expanded) size.height * 0.34f else size.height * 0.66f
        drawLine(color, Offset(0f, edgeY), Offset(size.width / 2f, midY), w)
        drawLine(color, Offset(size.width / 2f, midY), Offset(size.width, edgeY), w)
    }
}

/**
 * 计价示例用：与核心 Duty 一样容忍 IEEE-754 尾差
 * （25 × 0.4 = 10.000000000000002，不该显示成 11 秒），只在最后取整一次、至少 1 秒。
 * 只用于弹层/设置页的示例数字，不参与实际结算。
 */
fun exampleDuty(unit: Int, vararg factors: Double): Int {
    var value = unit.toDouble()
    for (factor in factors) value *= factor
    return kotlin.math.ceil(value - 1e-9).toInt().coerceAtLeast(1)
}

@Composable
fun SectionTitle(index: String, zh: String, en: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(index, style = QL.micro.copy(color = QL.Seal, letterSpacing = 1.sp))
        Spacer(Modifier.width(8.dp))
        Text(zh, style = QL.h2)
        Spacer(Modifier.width(8.dp))
        MicroLabel(en)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(QL.RuleSoft))
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    valueColor: Color = QL.Ink,
    mono: Boolean = true,
    note: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = if (note == null) 5.dp else 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = QL.bodySoft)
            if (note != null) {
                Spacer(Modifier.height(1.dp))
                MicroLabel(note)
            }
        }
        Text(
            text = value,
            style = if (mono) QL.mono.copy(color = valueColor) else QL.body.copy(color = valueColor),
            textAlign = TextAlign.End,
        )
    }
}

/** 自定义方角开关，避免 Material 圆角滑块带来的模板感；拇指位移用 transform 滑过去，不做硬跳。 */
@Composable
fun QlToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val trackWidth = 46.dp
    val trackHeight = 22.dp
    val inset = 3.dp
    val thumbSize = 14.dp
    // 46 - 左侧内边距 3 - 右侧内边距 3 - 拇指 14 = 26dp，拇指从最左滑到最右。
    val travel = trackWidth - inset * 2 - thumbSize
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        animationSpec = Motion.standard(Motion.Small),
        label = "qlToggleThumb",
    )
    Box(
        modifier
            .width(trackWidth)
            .height(trackHeight)
            .background(if (checked) QL.Ink else Color.Transparent, RectangleShape)
            .border(1.dp, QL.Ink, RectangleShape)
            .qlClickable(
                haptic = HapticFeedbackType.TextHandleMove,
                onClick = { onChange(!checked) },
            )
            .padding(inset),
    ) {
        Box(
            Modifier
                .size(thumbSize)
                .graphicsLayer { translationX = thumbOffset.toPx() }
                .background(if (checked) QL.Paper else QL.InkFaint, RectangleShape)
                .align(Alignment.CenterStart),
        )
    }
}

@Composable
fun QlTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    uppercase: Boolean = true,
    maxLength: Int = 20,
) {
    BasicTextField(
        value = value,
        onValueChange = { raw ->
            onValueChange(if (uppercase) raw.uppercase().take(maxLength) else raw.take(maxLength))
        },
        singleLine = true,
        textStyle = QL.mono.copy(color = QL.Ink, fontSize = 15.sp),
        cursorBrush = SolidColor(QL.Seal),
        modifier = modifier
            .height(42.dp)
            .background(QL.PaperLight)
            .border(1.dp, QL.Rule)
            .padding(horizontal = 12.dp),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = QL.monoSmall.copy(color = QL.InkFaint))
                }
                innerTextField()
            }
        },
    )
}

/**
 * 24 小时分布：柱状 + 「最吵 HH:00，N 条」结论行。
 *
 * [onPick] 不为 null 时整条可点：在 Canvas 上叠一层 24 等分的透明 Row，
 * 只加点击区、不加任何视觉元素，柱状外观与以前完全一致（v1.8.0 的 24 小时下钻入口）。
 */
@Composable
fun HourStrip(
    byHour: IntArray,
    tariff: Tariff,
    modifier: Modifier = Modifier,
    onPick: ((Int) -> Unit)? = null,
) {
    val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    val pick = onPick
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(34.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val barW = size.width / 24f
                val baseY = size.height - 1f
                drawLine(QL.Rule, Offset(0f, baseY), Offset(size.width, baseY), 1f)
                for (i in 0 until 24) {
                    val v = byHour.getOrElse(i) { 0 }
                    if (v <= 0) continue
                    val h = (v.toFloat() / max) * (size.height - 4f)
                    val night = tariff.isNight(i)
                    drawRect(
                        color = if (night) QL.Seal.copy(alpha = 0.75f) else QL.Ink.copy(alpha = 0.7f),
                        topLeft = Offset(i * barW + barW * 0.2f, baseY - h),
                        size = androidx.compose.ui.geometry.Size(barW * 0.6f, h),
                    )
                }
            }
            if (pick != null) {
                // 透明等分点击区：只负责命中，不参与绘制；按压洗染由 qlClickable 提供。
                Row(Modifier.fillMaxSize()) {
                    for (hour in 0 until 24) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .qlClickable(haptic = HapticFeedbackType.TextHandleMove) {
                                    pick(hour)
                                },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MicroLabel("00")
            MicroLabel("06")
            MicroLabel("12")
            MicroLabel("18")
            MicroLabel("23")
        }
        // 给 24 小时分布一个能直接读的结论：几点最吵、几条。
        val peak = byHour.indices.maxByOrNull { byHour[it] }
        if (peak != null && byHour[peak] > 0) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(GlyphKind.CLOCK, size = 11.dp, color = QL.InkFaint)
                Spacer(Modifier.width(4.dp))
                MicroLabel("最吵 ${"%02d".format(peak)}:00，${byHour[peak]} 条")
            }
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    latin: String,
    modifier: Modifier = Modifier,
    right: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = QL.h1)
            Spacer(Modifier.height(3.dp))
            MicroLabel(latin)
        }
        right()
    }
}

@Composable
fun NoticeBar(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tone: Color = QL.Seal,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(QL.SealWash.copy(alpha = 0.55f))
            .border(1.dp, tone.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(28.dp).background(tone))
        Spacer(Modifier.width(9.dp))
        Text(text, style = QL.bodySoft.copy(color = QL.Ink), modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                actionLabel,
                style = QL.label.copy(color = tone, letterSpacing = 1.sp),
                modifier = Modifier.qlClickable(onClick = onAction),
            )
        }
    }
}

/**
 * 分享收据（P1-4）：先让用户看到「朋友打开会看到什么」，再给唯一的主按钮。
 *
 * 层级固定为：公开页样张（ReceiptPaper 缩略）→ 链接 / 未结算说明 → 主按钮
 * （复制链接 / 复制摘要）→ 一行两个次级按钮（生成完税证明 / 更多分享方式）→
 * 官网文字链。到官网不再占一整行按钮，也不再出现「在浏览器打开」。
 */
@Composable
fun ShareSheet(state: AppState) {
    val inv = state.invoice
    val settled = state.settledByServer
    val bal = inv.balanceParts()
    val nickname = state.nickname.ifBlank { "我" }
    QlSheet(
        title = "把收据发给朋友",
        subtitle = "${inv.date}  应缴 ${inv.totalDuty} 秒  ${bal.shortText}",
        onClose = state::dismissShare,
    ) {
        // 公开页样张：日期、昵称、应缴、结余（欠税时用税红），让用户先看到朋友会看到的那张票。
        ReceiptPaper {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(nickname, style = QL.h3, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    MicroLabel(inv.date)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        MicroLabel("应缴")
                        Spacer(Modifier.height(3.dp))
                        MonoText("${inv.totalDuty} 秒", style = QL.monoStrong)
                    }
                    Column(Modifier.weight(1f)) {
                        MicroLabel(bal.label)
                        Spacer(Modifier.height(3.dp))
                        MonoText(
                            bal.valueText,
                            style = QL.monoStrong,
                            color = if (bal.owed) QL.Seal else QL.Ink,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (settled) {
            Text("持链接的人能看到应用汇总和昵称。", style = QL.bodySoft)
        } else {
            Text("联网结算后可生成公开链接。", style = QL.bodySoft)
        }
        Spacer(Modifier.height(12.dp))
        // 主按钮只有一个：结算后复制公开链接，未结算时复制本机税单摘要。
        QlButton(
            label = when {
                state.receiptLinkBusy -> "正在生成链接"
                settled -> "复制链接"
                else -> "复制摘要"
            },
            onClick = state::copyReceiptLink,
            modifier = Modifier.fillMaxWidth(),
            primary = true,
            enabled = !state.receiptLinkBusy,
        )
        Spacer(Modifier.height(8.dp))
        // 次级动作：生成完税证明图（T3 的 ReceiptCard，经 AppState 调系统分享）/ 更多分享方式。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QlButton(
                label = "生成完税证明",
                onClick = state::shareReceiptCard,
                modifier = Modifier.weight(1f),
            )
            QlButton("更多分享方式", state::shareViaSystem, Modifier.weight(1f), enabled = !state.receiptLinkBusy)
        }
        // 官网降级为一行文字链：不抢主次，也不依赖公开链接是否可用。
        Spacer(Modifier.height(10.dp))
        Text(
            "到官网看看",
            style = QL.label.copy(color = QL.InkSoft, textDecoration = TextDecoration.Underline),
            modifier = Modifier
                .qlClickable(onClick = state::openWebsite)
                .padding(vertical = 6.dp),
        )
    }
}

@Composable
fun ResetConfirmSheet(state: AppState) {
    QlSheet(title = "清空本机数据？", onClose = state::cancelResetLocalData) {
        Text("会删除本机账本和登录凭证，已同步的账单不受影响。", style = QL.bodySoft)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QlButton("取消", state::cancelResetLocalData, Modifier.weight(1f), primary = true)
            QlButton(
                "清空",
                state::resetLocalData,
                Modifier.weight(1f),
                haptic = HapticFeedbackType.LongPress,
            )
        }
    }
}

/**
 * 单条记录的动作弹层（P0-7）。
 *
 * 修复真实缺陷：连发 / 免打扰 / 群发的判定都依赖「当天更早的事件」，
 * 必须把 `state.todayEvents` 全量交给 [Duty.mark] 再按 `event.id` 取这一条；
 * 以前只传 `listOf(event)`，连发的第二条永远算不成连发，弹层金额与列表明细对不上。
 * 找不到（例如从历史日进入）才退回单条计价，保证不崩、不显示空拆解。
 *
 * 拆解三行：原价（单价 × 应用权重，不含任何规则）→ 命中（夜间 / 群发 / 免打扰 / 连发）
 * → 实际税秒，金额与列表明细一致，能看出多出来（或少掉）的秒数来自哪条规则。
 */
@Composable
fun EntryActionSheet(state: AppState, event: NotifEvent) {
    val marked = remember(event.id, state.tariff, state.todayEvents) {
        Duty.mark(state.todayEvents, state.tariff).firstOrNull { it.event.id == event.id }
            ?: Duty.mark(listOf(event), state.tariff).firstOrNull()
    }
    QlSheet(title = event.app, subtitle = Fiscal.clockText(event.ts), onClose = state::dismissEntry, scrollable = true) {
        if (marked != null) {
            // 原价 = 这个应用一条通知的起步价（单价 × 应用权重，不含夜间/群发/连发/免打扰）。
            // 加成与折扣都记在「命中」里，避免出现「原价 == 实际」却看不出差别的行。
            val pricedEvent = marked.event
            if (pricedEvent.conversationClass != ConversationClass.DEFAULT) {
                KeyValueRow("计税类别", pricedEvent.conversationClass.label)
            }
            KeyValueRow("原价", "${baseDuty(state.tariff, pricedEvent)} 秒", valueColor = QL.InkSoft)
            KeyValueRow("命中", hitRules(state.tariff, marked))
            KeyValueRow("实际", "${marked.duty} 秒")
            Spacer(Modifier.height(4.dp))
            MicroLabel("按规则 ${ruleVersionText(state.tariff.version)}")
            Spacer(Modifier.height(10.dp))
        }
        Text(
            event.title.ifBlank { "（无正文）" },
            style = QL.bodySoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        event.source?.let { source ->
            Spacer(Modifier.height(12.dp))
            val rule = state.conversationOverview?.rules?.firstOrNull { it.source.key == source.key }
            SettingRow(
                title = "设置会话分类",
                note = source.name,
                value = rule?.kind?.label,
                onClick = { state.classifyConversation(source) },
            )
        }
        // 删除 / 忽略是即时动作，不是「进入下一层」：放到虚线之后，且不画右箭头。
        Spacer(Modifier.height(14.dp))
        DashedHLine(color = QL.Rule)
        Spacer(Modifier.height(12.dp))
        SheetActionRow("删除这一条", state::deleteSelectedEntry, tone = QL.Seal)
        Spacer(Modifier.height(8.dp))
        SheetActionRow(
            "不再记录此应用",
            state::ignoreSelectedApp,
            tone = QL.Seal,
            note = "同时清除这个应用的已入账记录",
        )
    }
}

/** 单条命中的规则，只列非零项；顺序与计税顺序一致：夜间 → 群发 → 免打扰 → 连发。 */
private fun hitRules(tariff: Tariff, marked: MarkedEvent): String {
    if (marked.event.conversationClass == ConversationClass.FREE) return "不计打扰"
    val hits = buildList {
        if (marked.night) add("夜间 ×${factorText(tariff.nightMultiplier)}")
        if (marked.notice) add("群发 ×${factorText(tariff.noticeMultiplier)}")
        if (marked.dnd) add("免打扰 ×${factorText(tariff.dndMultiplier)}")
        if (marked.burst) add("连发 ×${factorText(tariff.burstMultiplier)}")
    }
    return if (hits.isEmpty()) "无" else hits.joinToString("、")
}

/** 一条通知在不命中任何规则时的起步价：ceiling(单价 × 应用权重)，与服务端同一次取整口径。 */
private fun baseDuty(tariff: Tariff, event: NotifEvent): Int {
    if (event.conversationClass == ConversationClass.FREE) return 0
    val raw = tariff.unitSeconds * (event.conversationClass.factor ?: tariff.appFactor(event.pkg))
    return kotlin.math.ceil(raw - 1e-9).toInt().coerceAtLeast(1)
}

/** 2.0 → "2"、0.4 → "0.4"：不把 ×2.0 这种小数尾巴念给用户。 */
private fun factorText(value: Double): String =
    if (value == value.toInt().toDouble()) "${value.toInt()}" else "$value"

/** 本地兜底税率没有版本号，别把 "local-default" 直接念给用户。 */
private fun ruleVersionText(version: String): String =
    if (version.isBlank() || version == "local-default") "本机默认" else version
