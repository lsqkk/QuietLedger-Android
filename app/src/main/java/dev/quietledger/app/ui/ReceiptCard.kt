package dev.quietledger.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.core.Invoice
import dev.quietledger.app.core.balanceParts
import java.io.File
import java.io.FileOutputStream

/**
 * 完税证明分享卡（5.6）：1080×1350 PNG，纯 `android.graphics` 绘制，不依赖 Compose。
 *
 * 为什么不用 Compose 画：分享要的是「一张能发出去的图」，Bitmap + Canvas 不需要组合树、
 * 不需要 Activity，后台线程也能生成；同时 0 圆角、0 阴影、0 渐变是硬约束，直接画线最省心。
 *
 * 版式（纸质完税凭证 × 终端报表）：
 *   顶部「注意力税单 / ATTENTION DUTY INVOICE」→ 日期 / 单号 → 应缴大数字 →
 *   每日免税额 / 结余或欠税 → 最吵应用 Top1 → 双线红章「已结清 / 欠税」→
 *   底部公开链接与「本单不作报销凭证」。
 * 所有文本都经过 `fit()` 量宽截断，保证落在画布内（1080 宽、左右 96px 安全边）。
 *
 * 配色固定取「纸墨」一套，与 [Theme.kt] 的 `QL.PAPER` 逐位一致：
 * 分享卡会进入聊天窗口 / 相册，夜墨主题生成的深色图在别人的浅色聊天里几乎看不清，
 * 所以不跟随当前主题。改主题配色时这里要同步（值见 Theme.kt 的 `PAPER`）。
 */
object ReceiptCard {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    // 与 ui/Theme.kt 的 QL.PAPER 对齐（纸墨主题）。
    private val PAPER = 0xFFF1EBDE.toInt()       // QL.PAPER.paper
    private val INK = 0xFF1B1815.toInt()         // QL.PAPER.ink
    private val INK_SOFT = 0xFF5C5446.toInt()    // QL.PAPER.inkSoft
    private val INK_FAINT = 0xFF6E6455.toInt()   // QL.PAPER.inkFaint
    private val RULE = 0xFFC3B8A1.toInt()        // QL.PAPER.rule
    private val SEAL = 0xFFB3392C.toInt()        // QL.PAPER.seal
    private val GREEN = 0xFF2F5B4B.toInt()       // QL.PAPER.green

    /** 左右安全边：内容区 96..984，等于 360dp 屏上的 20dp 边距（按 3x 折算）。 */
    private const val LEFT = 96f
    private const val RIGHT = 984f


    /**
     * 生成今日（或指定日）的完税证明图片，写入 `cacheDir/receipts/receipt-<date>.png`。
     * 返回可交给 [androidx.core.content.FileProvider] 的 File；任何失败都返回 null，由调用方提示。
     */
    fun render(context: Context, inv: Invoice, nickname: String): File? {
        return try {
            val dir = File(context.cacheDir, "receipts")
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val file = File(dir, "receipt-${inv.date}.png")
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                drawCard(Canvas(bitmap), inv, nickname)
                FileOutputStream(file).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
                }
            } finally {
                bitmap.recycle()
            }
            file
        } catch (_: Exception) {
            // 生成分享卡失败不该影响业务：返回 null，由调用方 toast「生成失败，请重试」。
            null
        }
    }

    // ------------------------------------------------------------------ 版式

    private fun drawCard(canvas: Canvas, inv: Invoice, nickname: String) {
        canvas.drawColor(PAPER)

        // 1dp 墨框：0 圆角、0 阴影，只画一条矩形线。
        canvas.drawRect(
            56f, 56f, RIGHT + 40f, HEIGHT - 56f,
            strokePaint(INK, 2f, alpha = 84),
        )

        val weekday = runCatching { Fiscal.weekdayZh(Fiscal.parseDate(inv.date)) }.getOrDefault("")
        val balance = inv.balanceParts()

        var y = 150f
        canvas.drawText("注意力税单", LEFT, y, textPaint(INK, 54f, mono = false, bold = true))
        y += 46f
        canvas.drawText(
            "ATTENTION DUTY INVOICE", LEFT, y,
            textPaint(INK_SOFT, 24f).apply { letterSpacing = 0.24f },
        )
        y += 34f
        dashedRule(canvas, y)
        y += 64f

        val rowLabel = textPaint(INK_FAINT, 24f)
        val rowValue = textPaint(INK, 30f)
        drawRow(canvas, "日期", listOf(inv.date, weekday).filter { it.isNotEmpty() }.joinToString(" "), y, rowLabel, rowValue)
        y += 50f
        drawRow(canvas, "单号", inv.invoiceId.ifBlank { "—" }, y, rowLabel, rowValue)
        y += 52f
        dashedRule(canvas, y)
        y += 70f

        // 应缴：整张卡唯一的大数字，等宽字体。
        canvas.drawText(
            "应缴 / DUTY", LEFT, y,
            textPaint(INK_FAINT, 26f).apply { letterSpacing = 0.16f },
        )
        y += 40f
        val amountPaint = textPaint(INK, 168f, bold = true)
        val amountText = inv.totalDuty.toString()
        canvas.drawText(amountText, LEFT - 4f, y + 150f, amountPaint)
        canvas.drawText(
            "秒",
            LEFT - 4f + amountPaint.measureText(amountText) + 20f,
            y + 150f,
            textPaint(INK_SOFT, 50f),
        )
        // 红章落在应缴数字右侧，避开下方所有文字行（-7° 旋转后的包围盒也不压到虚线）。
        stamp(canvas, RIGHT - 170f, y + 96f, if (balance.owed) "欠税" else "已结清")
        y += 190f
        dashedRule(canvas, y)
        y += 62f

        // 结算：免税额 → 结余 / 欠税（金额只给正数，欠税用税红）。
        drawRow(canvas, "每日免税额", "${inv.exemption} 秒", y, textPaint(INK_SOFT, 30f), textPaint(INK, 32f))
        y += 54f
        drawRow(
            canvas,
            balance.label,
            balance.valueText,
            y,
            textPaint(INK_SOFT, 30f),
            textPaint(if (balance.owed) SEAL else GREEN, 34f, bold = true),
        )
        y += 58f
        dashedRule(canvas, y)
        y += 54f

        // 最吵应用 Top1：告诉朋友「被谁偷走的」。
        canvas.drawText(
            "最吵应用 / TOP1", LEFT, y,
            textPaint(INK_FAINT, 24f).apply { letterSpacing = 0.16f },
        )
        y += 42f
        val top = inv.lines.firstOrNull { it.app.isNotBlank() }
        if (top == null) {
            canvas.drawText("今天没有打扰", LEFT, y, textPaint(INK_SOFT, 34f))
        } else {
            val appPaint = textPaint(INK, 36f, mono = false, bold = true)
            canvas.drawText(fit(appPaint, top.app, RIGHT - LEFT - 270f), LEFT, y, appPaint)
            val metaPaint = textPaint(INK_SOFT, 28f)
            val meta = "${top.count} 条 / ${top.duty} 秒"
            canvas.drawText(meta, RIGHT - metaPaint.measureText(meta), y, metaPaint)
        }
        y += 56f

        val nick = nickname.trim().ifBlank { "我" }
        canvas.drawText(
            fit(textPaint(INK_SOFT, 26f), "开票给 $nick", RIGHT - LEFT - 380f),
            LEFT, y, textPaint(INK_SOFT, 26f),
        )

        val link = "ql.130923.xyz"
        dashedRule(canvas, HEIGHT - 220f)
        canvas.drawText(
            fit(textPaint(INK, 28f), link, RIGHT - LEFT),
            LEFT, HEIGHT - 166f, textPaint(INK, 28f),
        )
        canvas.drawText(
            "本单不作报销凭证", LEFT, HEIGHT - 118f,
            textPaint(INK_FAINT, 24f).apply { letterSpacing = 0.10f },
        )
    }

    /** 一行「标签 …… 值」：标签左、值右，中间 1px 点状引导线（量宽后画，不越界）。 */
    private fun drawRow(
        canvas: Canvas,
        labelText: String,
        valueText: String,
        baseline: Float,
        labelPaint: Paint,
        valuePaint: Paint,
    ) {
        canvas.drawText(labelText, LEFT, baseline, labelPaint)
        val value = fit(valuePaint, valueText, RIGHT - LEFT - labelPaint.measureText(labelText) - 32f)
        val valueWidth = valuePaint.measureText(value)
        canvas.drawText(value, RIGHT - valueWidth, baseline, valuePaint)

        val x1 = LEFT + labelPaint.measureText(labelText) + 16f
        val x2 = RIGHT - valueWidth - 16f
        if (x2 - x1 > 24f) {
            canvas.drawLine(
                x1, baseline - 10f, x2, baseline - 10f,
                strokePaint(RULE, 2f).apply { pathEffect = DashPathEffect(floatArrayOf(2f, 8f), 0f) },
            )
        }
    }

    /** 双线税红章：外层粗线 + 内层细线，整体压印式微微倾斜（-7°，与 App 内 Stamp 一致）。 */
    private fun stamp(canvas: Canvas, cx: Float, cy: Float, textValue: String) {
        val w = 340f
        val h = 150f
        canvas.save()
        canvas.rotate(-7f, cx, cy)
        canvas.drawRect(
            cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f,
            strokePaint(SEAL, 5f, alpha = 214),
        )
        val inset = 12f
        canvas.drawRect(
            cx - w / 2f + inset, cy - h / 2f + inset, cx + w / 2f - inset, cy + h / 2f - inset,
            strokePaint(SEAL, 2f, alpha = 214),
        )
        val stampPaint = textPaint(SEAL, 64f, mono = false, bold = true).apply {
            alpha = 232
            letterSpacing = 0.16f
        }
        val textWidth = stampPaint.measureText(textValue)
        val fm = stampPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(textValue, cx - textWidth / 2f, baseline, stampPaint)
        canvas.restore()
    }

    private fun dashedRule(canvas: Canvas, y: Float) {
        canvas.drawLine(
            LEFT, y, RIGHT, y,
            strokePaint(RULE, 2f).apply { pathEffect = DashPathEffect(floatArrayOf(9f, 7f), 0f) },
        )
    }

    // ------------------------------------------------------------------ Paint

    private fun textPaint(color: Int, sizePx: Float, mono: Boolean = true, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx
            typeface = if (mono) {
                Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            } else {
                Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
        }

    private fun strokePaint(color: Int, width: Float, alpha: Int = 255): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = alpha
            style = Paint.Style.STROKE
            strokeWidth = width
        }

    /** 量宽后截断：宁可加省略号，也不让文字画出画布（审计要求「文本全部落在画布内」）。 */
    private fun fit(paint: Paint, text: String, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        var end = text.length - 1
        while (end > 0) {
            val candidate = text.substring(0, end) + "…"
            if (paint.measureText(candidate) <= maxWidth) return candidate
            end -= 1
        }
        return "…"
    }
}
