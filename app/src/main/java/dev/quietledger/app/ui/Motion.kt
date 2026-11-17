package dev.quietledger.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 全 App 唯一的动效 token 与跟手工具。
 *
 * 规则（详见 docs/MOTION.md）：
 *  1. 任何时长 / 曲线 / 位移 / 缩放只能引用这里，禁止在页面里写魔法数字；
 *  2. 动画只改 transform / alpha（`graphicsLayer`），不触发 layout 重排；
 *  3. 跟手、回弹用 spring（dampingRatio 0.8–0.9），进出场用 tween + 指定曲线；
 *  4. Compose 动画自动遵守系统「移除动画」设置（MotionDurationScale）；
 *     View 体系需用 [Motion.animationsEnabled] 手动判断（本项目当前无 View 页面）。
 */
object Motion {

    // ---------------------------------------------------------------- 时长（毫秒）

    /** 小元素：按压、颜色、开关拇指。规范 100–150ms。 */
    const val Small = 120

    /** 小元素退出，比进入略快。 */
    const val SmallOut = 90

    /** 内容交叉淡入（骨架 → 正式内容）。 */
    const val ContentSwap = 220

    /** 常规状态变化（筛选块、选中态）。 */
    const val Standard = 150

    /** 容器变换 / 共享元素。规范 300–400ms。 */
    const val Container = 340

    /** 整页转场进入。规范 300ms。 */
    const val Page = 300

    /** 整页转场退出，比进入快约 50ms。 */
    const val PageOut = 250

    /** 底部弹层进入。 */
    const val SheetIn = 280

    /** 底部弹层退出。 */
    const val SheetOut = 220

    /** Tab 点击后程序化滚到目标页。 */
    const val TabSettle = 320

    /** 首屏错峰间隔（规范 20–30ms）。 */
    const val StaggerStep = 25

    /** 首屏错峰最多作用的条数；长列表后面不再动。 */
    const val StaggerMax = 8

    /** 首屏错峰窗口：窗口结束后新进列表的行不再做首屏动画，只走 animateItem。 */
    const val StaggerWindow = 900

    // ---------------------------------------------------------------- 缓动

    /** 进入 / 标准强调曲线（需求指定）。 */
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** 退出曲线：加速离开，不拖泥带水。 */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Emphasized：容器变换、共享元素的主曲线。 */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1.0f)

    /** 常规曲线。 */
    val StandardEasing: Easing = FastOutSlowInEasing

    // ---------------------------------------------------------------- spring

    /** 跟手 / 回弹阻尼比（规范 0.8–0.9）。 */
    const val SpringDamping = 0.85f

    /** 跟手 / 回弹刚度：够跟手，又不至于硬收。 */
    const val SpringStiffness = 380f

    fun <T> springy(): SpringSpec<T> = spring(dampingRatio = SpringDamping, stiffness = SpringStiffness)

    // ---------------------------------------------------------------- 形变

    /** 可按压缩放（规范 0.96–0.98）。 */
    const val PressScale = 0.97f

    /** 按压洗染的墨色透明度（0 圆角/0 阴影体系里替代 ripple）。 */
    const val PressWashAlpha = 0.06f

    /** 首屏出现时向上位移。 */
    val StaggerOffsetY: Dp = 8.dp

    /** 整页左右转场位移：屏宽的 8%。 */
    const val PageShiftFraction = 0.08f

    /** 预测性返回：页面缩到的比例。 */
    const val PredictiveScale = 0.94f

    /** 预测性返回：页面横移比例（跟手方向）。 */
    const val PredictiveShiftFraction = 0.12f

    /** 预测性返回：页面缩小后露出的背景压暗程度，让「页面被推走」看得出来。 */
    const val PredictiveScrimAlpha = 0.32f

    /**
     * 预测性返回时手指下方的临时圆角。
     *
     * DESIGN.md 的「0 圆角」是**静态界面**硬约束；这里只在系统返回手势进行中出现，
     * 松手/取消立即回 0，用于模拟系统「页面被推走」的层次感。要绝对守 0 圆角，改成 0.dp 即可。
     */
    val PredictiveCorner: Dp = 12.dp

    // ---------------------------------------------------------------- 动画 spec 工厂

    fun <T> enter(duration: Int = Standard): TweenSpec<T> =
        tween(durationMillis = duration, easing = Enter)

    fun <T> exit(duration: Int = SmallOut): TweenSpec<T> =
        tween(durationMillis = duration, easing = Exit)

    fun <T> emphasized(duration: Int = Container): TweenSpec<T> =
        tween(durationMillis = duration, easing = Emphasized)

    fun <T> standard(duration: Int = Small): TweenSpec<T> =
        tween(durationMillis = duration, easing = StandardEasing)

    /**
     * 系统「移除动画」是否还开着（`ANIMATOR_DURATION_SCALE > 0`）。
     *
     * 只给 View/传统动画用；Compose 的动画由 MotionDurationScale 自动降级，
     * 不需要调用这个函数。
     */
    fun animationsEnabled(context: Context): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    } catch (_: Exception) {
        true
    }
}

// -------------------------------------------------------------------- 按压反馈

/**
 * 按压洗染：按下时在内容下方铺一层极淡的墨色/税红色，替代 Material ripple。
 *
 * 只读 draw 阶段（alpha 用动画驱动），不触发重组和 layout；颜色默认走主题墨色，
 * 危险操作可传 [QL.Seal]。
 */
@Composable
fun Modifier.pressWash(
    interactionSource: MutableInteractionSource,
    color: Color = QL.Ink,
    maxAlpha: Float = Motion.PressWashAlpha,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) maxAlpha else 0f,
        animationSpec = Motion.enter(Motion.Small),
        label = "qlPressWash",
    )
    return drawBehind {
        if (alpha > 0.001f) drawRect(color.copy(alpha = alpha))
    }
}

/**
 * 可按压缩放：0.97 / 120ms，用于按钮、Tab、方块这类离散元素。
 * 整行列表项不要用这个（会被拉宽变形），用 [qlClickable] 的洗染。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    scale: Float = Motion.PressScale,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val value by animateFloatAsState(
        targetValue = if (pressed && enabled) scale else 1f,
        animationSpec = Motion.standard(Motion.Small),
        label = "qlPressScale",
    )
    return graphicsLayer {
        scaleX = value
        scaleY = value
    }
}

/**
 * 统一的「可点」修饰符：按压洗染 + 可选触觉，替代裸 `clickable(indication = null)`。
 *
 * 用法：`Modifier.qlClickable(haptic = HapticFeedbackType.LongPress) { ... }`
 * 视觉仍是 0 圆角、无 ripple；行用洗染，离散元素可再叠 [pressScale]。
 */
@Composable
fun Modifier.qlClickable(
    enabled: Boolean = true,
    haptic: HapticFeedbackType? = null,
    wash: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    return this
        .then(if (wash) Modifier.pressWash(source) else Modifier)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = {
                haptic?.let { haptics.performHapticFeedback(it) }
                onClick()
            },
        )
}

// -------------------------------------------------------------------- 内容出现

/**
 * 首屏错峰出现：fade + 向上 8dp，间隔 25ms，只对前 8 项生效。
 *
 * [enabled] 通常传「首屏窗口是否还开着」（见 [rememberEntranceWindow]），
 * 这样滚动后新组合出来的行不会再演一遍。只改 alpha / translationY。
 */
@Composable
fun Modifier.staggerIn(index: Int, enabled: Boolean = true): Modifier {
    val animate = enabled && index < Motion.StaggerMax
    var played by remember { mutableStateOf(!animate) }
    LaunchedEffect(animate, index) {
        if (animate) {
            delay(index.toLong() * Motion.StaggerStep)
            played = true
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = Motion.enter(Motion.Container),
        label = "qlStagger",
    )
    if (!animate) return this
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * Motion.StaggerOffsetY.toPx()
    }
}

/**
 * 首屏窗口：组合后 [windowMs] 毫秒内为 true，用来给 lstaggerIn 判断「是不是首屏」。
 * 窗口结束后不再播放首屏动画，避免长列表滚动时每一行都在动。
 */
@Composable
fun rememberEntranceWindow(windowMs: Int = Motion.StaggerWindow): Boolean {
    var open by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(windowMs.toLong())
        open = false
    }
    return open
}

/** 加载占位：纯色深纸块（不用 shimmer，避免装饰性动效）。 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 16.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(QL.PaperDeep),
    )
}
