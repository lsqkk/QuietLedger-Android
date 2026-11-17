package dev.quietledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 二级页面（设置 / 详情）共用的导航与列表组件。
 *
 * 规则与全局视觉一致：0 圆角、0 阴影、1dp 线；行高 48dp、左右 20dp、
 * 右侧数值一律等宽。返回靠左上角的「← 返回」，不用系统圆角返回键的样式。
 */

/** 二级页顶栏：细线分隔 + 返回 + 标题（可带分类图标）+ 右侧动作。 */
@Composable
fun SubPageHeader(
    title: String,
    onBack: () -> Unit,
    latin: String? = null,
    glyph: GlyphKind? = null,
    right: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .qlClickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrow()
                Spacer(Modifier.width(5.dp))
                Text("返回", style = QL.label.copy(color = QL.InkSoft, letterSpacing = 0.6.sp))
            }
            Spacer(Modifier.width(4.dp))
            if (glyph != null) {
                Glyph(glyph, size = 17.dp, color = QL.Seal, stroke = 1.3.dp)
                Spacer(Modifier.width(7.dp))
            }
            Text(title, style = QL.h2, maxLines = 1)
            Spacer(Modifier.weight(1f))
            right()
        }
        if (latin != null) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp)) {
                MicroLabel(latin)
            }
        }
        HairHLine(color = QL.RuleSoft)
    }
}

/**
 * 设置分类图标：固定 20dp 方块装 16dp 细线图标，所有设置行标题左端对齐。
 * 用浅底 + 墨线而不是彩色实底：一屏九个彩色块会抢走右侧状态值的注意力。
 */
@Composable
fun SettingIcon(kind: GlyphKind, color: Color = QL.InkSoft) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Glyph(kind, size = 16.dp, color = color, stroke = 1.25.dp)
    }
}

/** 12×12 的细线左箭头，和整套界面的 1dp 线一致。 */
@Composable
fun BackArrow(color: Color = QL.Ink) {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
        val w = 1.dp.toPx()
        val midY = size.height / 2f
        drawLine(color, Offset(size.width * 0.62f, 0f), Offset(size.width * 0.24f, midY), w)
        drawLine(color, Offset(size.width * 0.24f, midY), Offset(size.width * 0.62f, size.height), w)
        drawLine(color, Offset(size.width * 0.26f, midY), Offset(size.width, midY), w)
    }
}

/** 细线右箭头，表示「点进去还有一层」。 */
@Composable
fun Chevron(color: Color = QL.InkFaint, edge: Int = 9) {
    androidx.compose.foundation.Canvas(Modifier.size(edge.dp)) {
        val w = 1.dp.toPx()
        drawLine(color, Offset(0f, 0f), Offset(size.width, size.height / 2f), w)
        drawLine(color, Offset(size.width, size.height / 2f), Offset(0f, size.height), w)
    }
}

/** 二级页骨架：固定顶栏 + 可滚动内容。 */
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    latin: String? = null,
    glyph: GlyphKind? = null,
    right: @Composable RowScope.() -> Unit = {},
    bottomPadding: Int = 24,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SubPageHeader(title = title, onBack = onBack, latin = latin, glyph = glyph, right = right)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomPadding.dp),
        ) {
            content()
        }
    }
}

/** 设置列表分组标题：小号等宽 + 一条细线拉到右端。 */
@Composable
fun GroupLabel(text: String, latin: String = "") {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = QL.label.copy(color = QL.Seal, letterSpacing = 1.6.sp))
        if (latin.isNotEmpty()) {
            Spacer(Modifier.width(7.dp))
            MicroLabel(latin)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(QL.RuleSoft))
    }
}

/**
 * 设置行：左标题（可带一行小注），右侧当前值 + 箭头。
 * 不可点击时只做展示（如版本号）。
 */
@Composable
fun SettingRow(
    title: String,
    value: String? = null,
    note: String? = null,
    valueColor: Color = QL.InkSoft,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val base = if (onClick != null) {
        Modifier.qlClickable(onClick = onClick)
    } else {
        Modifier
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            base.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = QL.h3.copy(fontSize = 14.sp))
                if (note != null) {
                    Spacer(Modifier.height(2.dp))
                    MicroLabel(note)
                }
            }
            if (value != null) {
                Text(
                    value,
                    style = QL.monoSmall.copy(color = valueColor),
                    maxLines = 1,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (showChevron) {
                Spacer(Modifier.width(8.dp))
                Chevron()
            }
        }
        HairHLine(color = QL.RuleSoft)
    }
}

/** 单选行：右侧打勾（不用 Material RadioButton 的圆点）。 */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    note: String? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .qlClickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = QL.h3.copy(
                        fontSize = 14.sp,
                        color = if (selected) QL.Ink else QL.InkSoft,
                    ),
                )
                if (note != null) {
                    Spacer(Modifier.height(2.dp))
                    MicroLabel(note)
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .size(width = 14.dp, height = 9.dp)
                        .drawBehind {},
                ) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val w = 1.6.dp.toPx()
                        drawLine(
                            QL.Seal,
                            Offset(0f, size.height * 0.45f),
                            Offset(size.width * 0.4f, size.height),
                            w,
                        )
                        drawLine(
                            QL.Seal,
                            Offset(size.width * 0.4f, size.height),
                            Offset(size.width, 0f),
                            w,
                        )
                    }
                }
            } else {
                Box(Modifier.size(width = 14.dp, height = 9.dp))
            }
        }
        HairHLine(color = QL.RuleSoft)
    }
}

/** 一组并排的方角小按钮（如 -1h / +30min / +1h）。 */
@Composable
fun ButtonRow(spacing: Int = 8, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing.dp),
        content = content,
    )
}

/**
 * 底部弹层：主题遮罩、顶部装订线与系统安全边距。
 * 所有新的弹层都走这里，避免每个页面自己拼一遍。
 *
 * [scrollable]：默认 false，保持旧的「内容直接排版」行为；计价规则 / 通知核对这类
 * 内容可能超过一屏的弹层传 true —— 标题与「关闭」固定，只有内容区滚动。
 * 内容包裹用的是 `weight(1f, fill = false)`：内容短时弹层仍按内容高度贴底，
 * 只有真的超过可用高度时才在内容区出现滚动，不会把短弹层拉满全屏。
 * 注意：scrollable 内容里的子项不要再用 `Modifier.weight()`（滚动容器高度无界，权重不生效）。
 *
 * 进出场（token 见 [Motion] / [NavMotion]）：遮罩 alpha 0→1、面板从自身高度升到 0，
 * 只改 `graphicsLayer` 的 alpha / translationY，不动 layout。
 * 关闭统一走内部 requestClose()：遮罩、返回键、「关闭」都先播退出动画，动画结束才让外部卸载；
 * 内容里的业务动作（保存 / 复制 / 取消）仍直接调外部 `onClose()` 瞬时消失，不改业务。
 */
@Composable
fun QlSheet(
    title: String,
    onClose: () -> Unit,
    subtitle: String? = null,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val latestOnClose by rememberUpdatedState(onClose)

    val requestClose: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, NavMotion.sheetExit())
                latestOnClose()
            }
        }
    }

    // 进入：遮罩淡入 + 面板自下而上。progress 0→1 同时驱动两者。
    LaunchedEffect(Unit) {
        progress.animateTo(1f, NavMotion.sheetEnter())
    }

    // 弹层要吃掉返回键，否则按返回直接退出 App。
    BackHandler { requestClose() }

    Box(Modifier.fillMaxSize()) {
        // 遮罩：独立一层，alpha 单独动画，不与面板位移互相牵制。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background((if (QL.IsDark) QL.Paper else QL.Ink).copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = requestClose,
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    // 从自身高度升上来；size 是这一层的实际尺寸，不需要额外测量。
                    translationY = (1f - progress.value) * size.height
                }
                .background(QL.PaperLight)
                .drawBehind {
                    drawLine(QL.Ink, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = QL.h2)
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(subtitle, style = QL.bodySoft)
                    }
                }
                Text(
                    "关闭",
                    style = QL.label.copy(color = QL.InkSoft),
                    modifier = Modifier
                        .qlClickable(onClick = requestClose)
                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            if (scrollable) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}

/**
 * 弹层里的「危险 / 次要」操作行，保持 0 圆角。
 *
 * 右侧箭头只表示「点进去还有一层」；删除 / 忽略 / 清空这类即时动作默认不画箭头
 * （[showChevron] 默认 false），避免把破坏性操作画成导航入口。
 */
@Composable
fun SheetActionRow(
    label: String,
    onClick: () -> Unit,
    tone: Color = QL.Ink,
    note: String? = null,
    showChevron: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, tone.copy(alpha = 0.75f), RectangleShape)
                .qlClickable(haptic = HapticFeedbackType.LongPress, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = QL.h3.copy(color = tone), modifier = Modifier.weight(1f))
            if (showChevron) Chevron(color = tone.copy(alpha = 0.8f))
        }
        if (note != null) {
            Spacer(Modifier.height(3.dp))
            MicroLabel(note)
        }
    }
}

/** 小号状态点（已连接 / 未连接 / 待同步），避免引入图标资源。 */
@Composable
fun StatusDot(color: Color, size: Int = 7) {
    Box(Modifier.size(size.dp).background(color))
}
