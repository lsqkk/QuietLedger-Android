package dev.quietledger.app.core

import java.security.MessageDigest

/** 会话系数替代应用权重；未分类沿用应用权重。ID 是双端协议。 */
enum class ConversationClass(val wire: String, val label: String, val factor: Double?) {
    DEFAULT("", "按应用计税", null),
    WORK("work", "工作", 1.0),
    FAMILY("family", "家人", 0.25),
    FRIENDS("friends", "朋友", 0.25),
    CASUAL("casual", "闲聊群", 0.1),
    FREE("free", "不计打扰", 0.0);

    val rateLabel: String
        get() = when (factor) {
            null -> "沿用应用权重"
            0.0 -> "0 秒"
            1.0 -> "×1"
            else -> "×$factor"
        }

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}

data class ConversationSource(val key: String, val name: String, val pkg: String, val app: String)

data class ConversationRule(val source: ConversationSource, val kind: ConversationClass)

internal data class ConversationCandidate(val source: ConversationSource, val today: Int, val week: Int)
internal data class ConversationOverview(
    val rules: List<ConversationRule>,
    val pending: List<ConversationCandidate>,
)

internal object ConversationKeys {
    private val qq = setOf("com.tencent.mobileqq", "com.tencent.tim")
    private val unreadSuffix = Regex("""\s*(?:（\s*[0-9０-９]+\s*条新消息\s*）|\(\s*[0-9０-９]+\s*条新消息\s*\))\s*$""")

    fun displayName(pkg: String, raw: String): String {
        val name = raw.trim()
        return if (pkg in qq) unreadSuffix.replace(name, "").trimEnd() else name
    }

    fun key(pkg: String, profile: String, identity: String): String = digest("$pkg|$profile|$identity")

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
