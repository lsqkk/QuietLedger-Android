package dev.quietledger.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 界面用细线图标：全部是 1.2dp 描边、无填充、无圆角，和纸质票据的 1dp 线同一套语言。
 * 用 Canvas 画而不是加 material-icons 依赖，避免为了几个图标把包体撑大、也避免圆角/填充
 * 的 Material 图标破坏「0 圆角、0 阴影」的视觉硬约束。
 */
enum class GlyphKind {
    MOON,       // 夜间
    BURST,      // 连发
    SILENT,     // 免打扰
    MEGAPHONE,  // 群发（@所有人）
    COUNT,      // 条数
    SAVED,      // 优惠 / 省下的秒数
    DEVICE,     // 设备
    CLOCK,      // 时间
    CALENDAR,   // 周期
    CHART,      // 统计
    LIST,       // 全部 / 列表
    TREND_UP,   // 比上期多
    TREND_DOWN, // 比上期少
    // v1.9.0：底部标签与设置分类的成体系图标（同一套细线、0 圆角、无填充）。
    INVOICE,    // 票据（带撕口的税单）
    SLIDERS,    // 设置（三条带节点的滑杆）
    USER,       // 账户
    BELL,       // 通知
    SYNC,       // 同步（两个循环箭头）
    PERCENT,    // 计税规则（税率）
    SUN,        // 日报（每天一次）
    CONTRAST,   // 外观（明暗对比）
    GRID,       // 桌面入口（小部件）
    DOWNLOAD,   // 版本与更新
    INFO,       // 关于
}

@Composable
fun Glyph(
    kind: GlyphKind,
    size: Dp = 12.dp,
    color: Color = QL.InkSoft,
    stroke: Dp = 1.2.dp,
) {
    Canvas(Modifier.size(size)) { drawGlyph(kind, color, stroke.toPx()) }
}

private fun DrawScope.drawGlyph(kind: GlyphKind, color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val cap = StrokeCap.Square
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), stroke, cap = cap)

    when (kind) {
        GlyphKind.MOON -> {
            // 弯月：一条开口朝右的弧，再补一条内弧收出月牙。
            drawArc(
                color = color,
                startAngle = 55f,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = Offset(w * 0.14f, h * 0.08f),
                size = Size(w * 0.74f, h * 0.84f),
                style = Stroke(stroke, cap = cap),
            )
            drawArc(
                color = color,
                startAngle = 120f,
                sweepAngle = -150f,
                useCenter = false,
                topLeft = Offset(w * 0.40f, h * 0.02f),
                size = Size(w * 0.62f, h * 0.96f),
                style = Stroke(stroke, cap = cap),
            )
        }

        GlyphKind.BURST -> {
            // 两个向右的尖括号：同一段里连续到达。
            line(0.10f, 0.16f, 0.46f, 0.50f)
            line(0.46f, 0.50f, 0.10f, 0.84f)
            line(0.50f, 0.16f, 0.86f, 0.50f)
            line(0.86f, 0.50f, 0.50f, 0.84f)
        }

        GlyphKind.SILENT -> {
            val bell = Path().apply {
                moveTo(w * 0.50f, h * 0.08f)
                lineTo(w * 0.76f, h * 0.30f)
                lineTo(w * 0.76f, h * 0.60f)
                lineTo(w * 0.88f, h * 0.74f)
                lineTo(w * 0.12f, h * 0.74f)
                lineTo(w * 0.24f, h * 0.60f)
                lineTo(w * 0.24f, h * 0.30f)
                close()
            }
            drawPath(bell, color, style = Stroke(stroke, cap = cap))
            line(0.12f, 0.12f, 0.88f, 0.90f)
        }

        GlyphKind.MEGAPHONE -> {
            val horn = Path().apply {
                moveTo(w * 0.10f, h * 0.36f)
                lineTo(w * 0.42f, h * 0.36f)
                lineTo(w * 0.76f, h * 0.12f)
                lineTo(w * 0.76f, h * 0.78f)
                lineTo(w * 0.42f, h * 0.54f)
                lineTo(w * 0.10f, h * 0.54f)
                close()
            }
            drawPath(horn, color, style = Stroke(stroke, cap = cap))
            line(0.30f, 0.56f, 0.34f, 0.86f)
            drawArc(
                color = color,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(w * 0.78f, h * 0.24f),
                size = Size(w * 0.20f, h * 0.48f),
                style = Stroke(stroke, cap = cap),
            )
        }

        GlyphKind.COUNT -> {
            // 井号：一眼是「条数」而不是另一个文字标签。
            line(0.34f, 0.10f, 0.26f, 0.90f)
            line(0.70f, 0.10f, 0.62f, 0.90f)
            line(0.10f, 0.36f, 0.90f, 0.36f)
            line(0.06f, 0.66f, 0.86f, 0.66f)
        }

        GlyphKind.SAVED -> {
            line(0.50f, 0.10f, 0.50f, 0.62f)
            line(0.28f, 0.42f, 0.50f, 0.64f)
            line(0.72f, 0.42f, 0.50f, 0.64f)
            line(0.14f, 0.88f, 0.86f, 0.88f)
        }

        GlyphKind.DEVICE -> {
            drawRect(
                color = color,
                topLeft = Offset(w * 0.28f, h * 0.06f),
                size = Size(w * 0.44f, h * 0.88f),
                style = Stroke(stroke),
            )
            line(0.42f, 0.80f, 0.58f, 0.80f)
        }

        GlyphKind.CLOCK -> {
            drawCircle(color, radius = w * 0.38f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(stroke))
            line(0.50f, 0.26f, 0.50f, 0.52f)
            line(0.50f, 0.52f, 0.68f, 0.62f)
        }

        GlyphKind.CALENDAR -> {
            drawRect(
                color = color,
                topLeft = Offset(w * 0.12f, h * 0.20f),
                size = Size(w * 0.76f, h * 0.68f),
                style = Stroke(stroke),
            )
            line(0.12f, 0.38f, 0.88f, 0.38f)
            line(0.34f, 0.08f, 0.34f, 0.26f)
            line(0.66f, 0.08f, 0.66f, 0.26f)
        }

        GlyphKind.CHART -> {
            line(0.14f, 0.88f, 0.90f, 0.88f)
            line(0.28f, 0.88f, 0.28f, 0.58f)
            line(0.50f, 0.88f, 0.50f, 0.34f)
            line(0.72f, 0.88f, 0.72f, 0.48f)
        }

        GlyphKind.LIST -> {
            line(0.30f, 0.24f, 0.88f, 0.24f)
            line(0.30f, 0.50f, 0.88f, 0.50f)
            line(0.30f, 0.76f, 0.88f, 0.76f)
            line(0.12f, 0.24f, 0.14f, 0.25f)
            line(0.12f, 0.50f, 0.14f, 0.51f)
            line(0.12f, 0.76f, 0.14f, 0.77f)
        }

        GlyphKind.TREND_UP -> {
            line(0.12f, 0.78f, 0.40f, 0.48f)
            line(0.40f, 0.48f, 0.58f, 0.64f)
            line(0.58f, 0.64f, 0.88f, 0.26f)
            line(0.64f, 0.24f, 0.88f, 0.24f)
            line(0.88f, 0.24f, 0.88f, 0.48f)
        }

        GlyphKind.TREND_DOWN -> {
            line(0.12f, 0.24f, 0.40f, 0.54f)
            line(0.40f, 0.54f, 0.58f, 0.38f)
            line(0.58f, 0.38f, 0.88f, 0.74f)
            line(0.64f, 0.76f, 0.88f, 0.76f)
            line(0.88f, 0.76f, 0.88f, 0.52f)
        }

        GlyphKind.INVOICE -> {
            // 税单：纸上三行字 + 底部撕口（三个小三角），和票据卡的撕口虚线同一个意思。
            line(0.18f, 0.10f, 0.82f, 0.10f)
            line(0.18f, 0.10f, 0.18f, 0.84f)
            line(0.82f, 0.10f, 0.82f, 0.84f)
            line(0.32f, 0.30f, 0.68f, 0.30f)
            line(0.32f, 0.46f, 0.68f, 0.46f)
            line(0.32f, 0.62f, 0.54f, 0.62f)
            line(0.18f, 0.84f, 0.34f, 0.92f)
            line(0.34f, 0.92f, 0.50f, 0.84f)
            line(0.50f, 0.84f, 0.66f, 0.92f)
            line(0.66f, 0.92f, 0.82f, 0.84f)
        }

        GlyphKind.SLIDERS -> {
            // 三条滑杆，节点位置错开：一眼是「设置」，不是齿轮那种圆乎乎的通用图标。
            line(0.10f, 0.24f, 0.90f, 0.24f)
            line(0.10f, 0.50f, 0.90f, 0.50f)
            line(0.10f, 0.76f, 0.90f, 0.76f)
            drawRect(color, topLeft = Offset(w * 0.28f, h * 0.16f), size = Size(w * 0.16f, h * 0.16f), style = Stroke(stroke))
            drawRect(color, topLeft = Offset(w * 0.58f, h * 0.42f), size = Size(w * 0.16f, h * 0.16f), style = Stroke(stroke))
            drawRect(color, topLeft = Offset(w * 0.20f, h * 0.68f), size = Size(w * 0.16f, h * 0.16f), style = Stroke(stroke))
        }

        GlyphKind.USER -> {
            drawCircle(color, radius = w * 0.19f, center = Offset(w * 0.50f, h * 0.28f), style = Stroke(stroke))
            val shoulders = Path().apply {
                moveTo(w * 0.16f, h * 0.88f)
                lineTo(w * 0.16f, h * 0.76f)
                lineTo(w * 0.28f, h * 0.62f)
                lineTo(w * 0.72f, h * 0.62f)
                lineTo(w * 0.84f, h * 0.76f)
                lineTo(w * 0.84f, h * 0.88f)
            }
            drawPath(shoulders, color, style = Stroke(stroke, cap = cap))
        }

        GlyphKind.BELL -> {
            val bell = Path().apply {
                moveTo(w * 0.50f, h * 0.10f)
                lineTo(w * 0.74f, h * 0.30f)
                lineTo(w * 0.74f, h * 0.60f)
                lineTo(w * 0.86f, h * 0.74f)
                lineTo(w * 0.14f, h * 0.74f)
                lineTo(w * 0.26f, h * 0.60f)
                lineTo(w * 0.26f, h * 0.30f)
                close()
            }
            drawPath(bell, color, style = Stroke(stroke, cap = cap))
            line(0.42f, 0.86f, 0.58f, 0.86f)
        }

        GlyphKind.SYNC -> {
            // 上下两段半圆 + 箭头：一眼是「循环」，不做云朵（AGENTS §4 不写云端营销感）。
            drawArc(
                color = color,
                startAngle = 190f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(w * 0.12f, h * 0.12f),
                size = Size(w * 0.76f, h * 0.76f),
                style = Stroke(stroke, cap = cap),
            )
            drawArc(
                color = color,
                startAngle = 10f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(w * 0.12f, h * 0.12f),
                size = Size(w * 0.76f, h * 0.76f),
                style = Stroke(stroke, cap = cap),
            )
            // 上段箭头（右上）+ 下段箭头（左下）
            line(0.62f, 0.14f, 0.88f, 0.14f)
            line(0.88f, 0.14f, 0.88f, 0.38f)
            line(0.12f, 0.86f, 0.38f, 0.86f)
            line(0.12f, 0.86f, 0.12f, 0.62f)
        }

        GlyphKind.PERCENT -> {
            drawCircle(color, radius = w * 0.13f, center = Offset(w * 0.26f, h * 0.26f), style = Stroke(stroke))
            drawCircle(color, radius = w * 0.13f, center = Offset(w * 0.74f, h * 0.74f), style = Stroke(stroke))
            line(0.16f, 0.84f, 0.84f, 0.16f)
        }

        GlyphKind.SUN -> {
            // 圆盘 + 八道外射线（射线从盘外开始，不穿进圆里）。
            drawCircle(color, radius = w * 0.20f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(stroke))
            line(0.50f, 0.06f, 0.50f, 0.20f)
            line(0.50f, 0.80f, 0.50f, 0.94f)
            line(0.06f, 0.50f, 0.20f, 0.50f)
            line(0.80f, 0.50f, 0.94f, 0.50f)
            line(0.62f, 0.38f, 0.76f, 0.24f)
            line(0.62f, 0.62f, 0.76f, 0.76f)
            line(0.38f, 0.62f, 0.24f, 0.76f)
            line(0.38f, 0.38f, 0.24f, 0.24f)
        }

        GlyphKind.CONTRAST -> {
            // 圆 + 中线 + 右半边三条短横线：左空右实的「半调」，表示换配色。
            // 不要画斜线：斜线 + 圆会被读成「禁止」。
            drawCircle(color, radius = w * 0.36f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(stroke))
            line(0.50f, 0.14f, 0.50f, 0.86f)
            line(0.60f, 0.34f, 0.76f, 0.34f)
            line(0.60f, 0.50f, 0.76f, 0.50f)
            line(0.60f, 0.66f, 0.76f, 0.66f)
        }

        GlyphKind.GRID -> {
            // 2×2 方块：桌面上的小部件格子。
            drawRect(color, topLeft = Offset(w * 0.12f, h * 0.12f), size = Size(w * 0.32f, h * 0.32f), style = Stroke(stroke))
            drawRect(color, topLeft = Offset(w * 0.56f, h * 0.12f), size = Size(w * 0.32f, h * 0.32f), style = Stroke(stroke))
            drawRect(color, topLeft = Offset(w * 0.12f, h * 0.56f), size = Size(w * 0.32f, h * 0.32f), style = Stroke(stroke))
            drawRect(color, topLeft = Offset(w * 0.56f, h * 0.56f), size = Size(w * 0.32f, h * 0.32f), style = Stroke(stroke))
        }

        GlyphKind.DOWNLOAD -> {
            // 向下箭头落进托盘：装新版本。
            line(0.50f, 0.08f, 0.50f, 0.60f)
            line(0.30f, 0.40f, 0.50f, 0.62f)
            line(0.70f, 0.40f, 0.50f, 0.62f)
            line(0.14f, 0.68f, 0.14f, 0.86f)
            line(0.14f, 0.86f, 0.86f, 0.86f)
            line(0.86f, 0.86f, 0.86f, 0.68f)
        }

        GlyphKind.INFO -> {
            drawCircle(color, radius = w * 0.38f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(stroke))
            line(0.50f, 0.44f, 0.50f, 0.72f)
            line(0.47f, 0.30f, 0.53f, 0.30f)
        }
    }
}

/** 图标 + 数字的小胶囊，替代「连发 2 条 · 群发 1 条」这种点分隔长文案。 */
@Composable
fun GlyphChip(
    kind: GlyphKind,
    text: String,
    color: Color = QL.InkFaint,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Glyph(kind, size = 11.dp, color = color, stroke = 1.1.dp)
        Spacer(Modifier.width(3.dp))
        MonoText(
            text,
            style = QL.micro.copy(fontSize = 9.sp, letterSpacing = 0.3.sp, color = color),
            align = align,
        )
    }
}

/**
 * 一张票/一行里命中的规则：只显示非零项，顺序固定为 夜间 → 连发 → 免打扰 → 群发。
 * 比「夜间 2 条 · 连发 1 条 · 群发 1 条」短一半，也去掉了点分隔的模板感。
 */
@Composable
fun EffectChips(
    night: Int,
    burst: Int,
    dnd: Int,
    notice: Int,
    modifier: Modifier = Modifier,
    color: Color = QL.InkFaint,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val effects = buildList {
            if (night > 0) add(GlyphKind.MOON to night)
            if (burst > 0) add(GlyphKind.BURST to burst)
            if (dnd > 0) add(GlyphKind.SILENT to dnd)
            if (notice > 0) add(GlyphKind.MEGAPHONE to notice)
        }
        effects.forEachIndexed { index, (kind, count) ->
            if (index > 0) Spacer(Modifier.width(9.dp))
            GlyphChip(kind, "$count", color)
        }
    }
}

/**
 * 行尾的规则标记（18dp 方块）：图标代替「夜/连/静/群」，附无障碍描述。
 *
 * 颜色纪律（AGENTS §4 / 审计 §7.5）：红色实底只留给「钱、失败、夜间」；规则标记只是解释，
 * 用 [QL.SealWash] 浅底 + [QL.Seal] 细线图标（图标型对比度按 3:1 验收，夜墨实测 4.4:1 达标），
 * 避免一屏出现四五个红色块、把列表变成告警墙。
 */
@Composable
fun GlyphTag(kind: GlyphKind, label: String) {
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(18.dp)
            .background(QL.SealWash)
            .semantics { contentDescription = label }
            .padding(3.dp),
    ) {
        Glyph(kind, size = 12.dp, color = QL.Seal, stroke = 1.1.dp)
    }
}
