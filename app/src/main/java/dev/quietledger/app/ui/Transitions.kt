package dev.quietledger.app.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 全 App 唯一的转场定义文件。
 *
 * 规则：
 *  - 页面 / 弹层 / 共享元素的参数只允许出现在这里，页面里禁止各写一套；
 *  - 时长、曲线、位移量全部来自 [Motion]；
 *  - 只有 transform / alpha（`graphicsLayer`）参与动画，不触发 layout。
 *
 * 导航方向约定：进入下一层 = 前进（新页从右侧小幅滑入、旧页向左让位）；
 * 返回 = 反向（当前页向右退出、下层页从左侧回位）。
 */
object NavMotion {

    /** 前进：新页面从右侧滑入，旧页面向左退一小段并淡出。 */
    fun forward(): ContentTransform = ContentTransform(
        targetContentEnter = slideInHorizontally(
            animationSpec = Motion.enter(Motion.Page),
            initialOffsetX = { (it * Motion.PageShiftFraction).toInt() },
        ) + fadeIn(animationSpec = Motion.enter(Motion.Page)),
        initialContentExit = slideOutHorizontally(
            animationSpec = Motion.exit(Motion.PageOut),
            targetOffsetX = { -(it * Motion.PageShiftFraction / 2f).toInt() },
        ) + fadeOut(animationSpec = Motion.exit(Motion.PageOut)),
    )

    /** 返回：当前页向右退出，下层页从左侧回位。 */
    fun backward(): ContentTransform = ContentTransform(
        targetContentEnter = slideInHorizontally(
            animationSpec = Motion.enter(Motion.Page),
            initialOffsetX = { -(it * Motion.PageShiftFraction / 2f).toInt() },
        ) + fadeIn(animationSpec = Motion.enter(Motion.Page)),
        initialContentExit = slideOutHorizontally(
            animationSpec = Motion.exit(Motion.PageOut),
            targetOffsetX = { (it * Motion.PageShiftFraction).toInt() },
        ) + fadeOut(animationSpec = Motion.exit(Motion.PageOut)),
    )

    /** 底部弹层进入 / 退出（由 [QlSheet] 消费）。 */
    fun sheetEnter(): TweenSpec<Float> = Motion.enter(Motion.SheetIn)

    fun sheetExit(): TweenSpec<Float> = Motion.exit(Motion.SheetOut)

    /** 内容交叉淡入（加载 → 正式内容）。 */
    fun contentSwap(): TweenSpec<Float> = Motion.standard(Motion.ContentSwap)
}

/**
 * 预测性返回容器：手势过程中页面跟随手指做 scale / translate / 临时圆角，
 * 松手完成交给 [onBackCompleted]（通常是退出 App 或回上一层），
 * 取消则 spring 回弹到原位。
 *
 * 只改 transform：所有变化都在 `graphicsLayer` 里完成。API < 33 时
 * PredictiveBackHandler 自动退化成普通返回，页面不做手势跟随。
 */
@Composable
fun PredictiveBackContainer(
    enabled: Boolean,
    onBackCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    PredictiveBackHandler(enabled = enabled) { backFlow ->
        var completed = false
        try {
            backFlow.collect { event ->
                progress.snapTo(event.progress)
            }
            completed = true
            onBackCompleted()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            // 松手取消：跟手位置用 spring 回弹，不用线性插值硬收。
            if (!completed) {
                scope.launch {
                    progress.animateTo(0f, Motion.springy())
                }
            }
        }
    }

    Box(modifier) {
        // 页面缩小后露出的底：压暗一层，手势中才能看出「页面被推走 + 有圆角」。
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = (progress.value * Motion.PredictiveScrimAlpha).coerceIn(0f, 1f) }
                .background(QL.Ink),
        )
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val p = progress.value
                    val scale = 1f - (1f - Motion.PredictiveScale) * p
                    scaleX = scale
                    scaleY = scale
                    translationX = -size.width * Motion.PredictiveShiftFraction * p
                    transformOrigin = TransformOrigin.Center
                    if (p > 0.001f) {
                        shape = RoundedCornerShape(Motion.PredictiveCorner * p)
                        clip = true
                    }
                },
        ) {
            content()
        }
    }
}
