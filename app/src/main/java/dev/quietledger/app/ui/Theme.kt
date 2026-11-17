package dev.quietledger.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 视觉方向：纸质完税凭证 / 老式终端报表。
 * 规则：纸张底色 + 墨黑文字 + 税红印章；圆角一律 0，阴影一律 0，
 * 分隔用 1dp 实线或虚线；数字全部等宽。
 *
 * 四套配色（设置里可切换）：纸墨 / 夜墨 / 旧档 / 石印。
 */
data class QlPalette(
    val id: String,
    val name: String,
    val paper: Color,
    val paperLight: Color,
    val paperDeep: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val rule: Color,
    val ruleSoft: Color,
    val seal: Color,
    val sealWash: Color,
    /** 落在税红实底上的前景色（如 GlyphTag 的细线图标）：深色底上用亮纸色，亮色底上用墨纸色。 */
    val onSeal: Color,
    val green: Color,
    val amber: Color,
    val dark: Boolean,
)

object QL {
    private val PAPER = QlPalette(
        id = "paper", name = "纸墨",
        paper = Color(0xFFF1EBDE), paperLight = Color(0xFFF8F4EA), paperDeep = Color(0xFFE8DFCB),
        ink = Color(0xFF1B1815), inkSoft = Color(0xFF5C5446), inkFaint = Color(0xFF6E6455),
        rule = Color(0xFFC3B8A1), ruleSoft = Color(0xFFDBD2BE),
        seal = Color(0xFFB3392C), sealWash = Color(0xFFF0DAD3),
        onSeal = Color(0xFFF1EBDE), // = paper
        green = Color(0xFF2F5B4B), amber = Color(0xFF8A5A00),
        dark = false,
    )
    private val NIGHT = QlPalette(
        id = "night", name = "夜墨",
        paper = Color(0xFF15120F), paperLight = Color(0xFF201B16), paperDeep = Color(0xFF2A241D),
        ink = Color(0xFFEDE4D3), inkSoft = Color(0xFFB4A995), inkFaint = Color(0xFF8A7F6D),
        rule = Color(0xFF4A4236), ruleSoft = Color(0xFF332D25),
        seal = Color(0xFFE26955), sealWash = Color(0xFF3A241F),
        onSeal = Color(0xFF201B16), // = paperLight
        green = Color(0xFF7FB39A), amber = Color(0xFFD8A64A),
        dark = true,
    )
    private val SEPIA = QlPalette(
        id = "sepia", name = "旧档",
        paper = Color(0xFFEFE2CB), paperLight = Color(0xFFF7EDDA), paperDeep = Color(0xFFE2D2B6),
        ink = Color(0xFF2B2118), inkSoft = Color(0xFF6B5B45), inkFaint = Color(0xFF746048),
        rule = Color(0xFFC0AA83), ruleSoft = Color(0xFFD9C8A8),
        seal = Color(0xFF9C2F24), sealWash = Color(0xFFEDD6C7),
        onSeal = Color(0xFFEFE2CB), // = paper
        green = Color(0xFF4A5B3A), amber = Color(0xFF8A5A00),
        dark = false,
    )
    private val STONE = QlPalette(
        id = "stone", name = "石印",
        paper = Color(0xFFE9EBE4), paperLight = Color(0xFFF3F4EF), paperDeep = Color(0xFFDADDD3),
        ink = Color(0xFF1C211D), inkSoft = Color(0xFF556057), inkFaint = Color(0xFF626D63),
        rule = Color(0xFFB4BCB2), ruleSoft = Color(0xFFCFD5CC),
        seal = Color(0xFFA63A2B), sealWash = Color(0xFFE8D9D3),
        onSeal = Color(0xFFE9EBE4), // = paper
        green = Color(0xFF35594A), amber = Color(0xFF7A5B12),
        dark = false,
    )

    val palettes: List<QlPalette> = listOf(PAPER, NIGHT, SEPIA, STONE)

    var current by mutableStateOf(PAPER)
        private set

    fun paletteById(id: String?): QlPalette =
        palettes.firstOrNull { it.id == id } ?: PAPER

    fun apply(id: String?) {
        current = paletteById(id)
    }

    val Paper: Color get() = current.paper
    val PaperLight: Color get() = current.paperLight
    val PaperDeep: Color get() = current.paperDeep
    val Ink: Color get() = current.ink
    val InkSoft: Color get() = current.inkSoft
    val InkFaint: Color get() = current.inkFaint
    val Rule: Color get() = current.rule
    val RuleSoft: Color get() = current.ruleSoft
    val Seal: Color get() = current.seal
    val SealWash: Color get() = current.sealWash
    /** 税红实底上的前景色：夜里用亮纸色，亮色主题用墨纸色，保证 ≥ 4.5:1。 */
    val OnSeal: Color get() = current.onSeal
    val Green: Color get() = current.green
    val Amber: Color get() = current.amber
    val IsDark: Boolean get() = current.dark

    val Mono = FontFamily.Monospace
    val Sans = FontFamily.SansSerif

    val micro: TextStyle
        get() = TextStyle(fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.7.sp, color = InkFaint)
    val label: TextStyle
        get() = TextStyle(fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 0.8.sp, color = InkSoft)
    val mono: TextStyle
        get() = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Ink)
    val monoSmall: TextStyle
        get() = TextStyle(fontFamily = Mono, fontSize = 11.sp, color = InkSoft)
    val monoStrong: TextStyle
        get() = TextStyle(fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
    val body: TextStyle
        get() = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 21.sp, color = Ink)
    val bodySoft: TextStyle
        get() = TextStyle(fontFamily = Sans, fontSize = 12.5.sp, lineHeight = 19.sp, color = InkSoft)
    val h1: TextStyle
        get() = TextStyle(
            fontFamily = Sans, fontSize = 30.sp, lineHeight = 34.sp,
            fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp, color = Ink,
        )
    val h2: TextStyle
        get() = TextStyle(fontFamily = Sans, fontSize = 16.5.sp, fontWeight = FontWeight.Bold, color = Ink)
    val h3: TextStyle
        get() = TextStyle(fontFamily = Sans, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    val amountLg: TextStyle
        get() = TextStyle(
            fontFamily = Mono, fontSize = 38.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-1.2).sp, color = Ink,
        )
    val amountMd: TextStyle
        get() = TextStyle(fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
}
