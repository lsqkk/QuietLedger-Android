package dev.quietledger.app.service

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.quietledger.app.core.ConversationSource
import dev.quietledger.app.core.ConversationKeys

/** Never use notification IDs, group keys or a prefix of the body as a conversation ID. */
internal object ConversationIdentity {
    private val chatApps = setOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.wework",
        "com.alibaba.android.rimet", "com.ss.android.lark", "com.larksuite.suite",
    )
    private val aggregate = Regex(
        """^(?:(?:你有|收到|有)?\s*[\[【]?\s*\d+\s*条.*|新消息|新通知|你有新消息|收到新消息|新消息通知|new messages?|\d+ new messages?)$""",
        RegexOption.IGNORE_CASE,
    )

    fun read(
        sbn: StatusBarNotification,
        notification: Notification,
        ranking: NotificationListenerService.Ranking?,
        app: String,
        title: String,
    ): ConversationSource? {
        val extras = notification.extras
        val messaging = extras.containsKey(Notification.EXTRA_MESSAGES)
        val shortcut = if (Build.VERSION.SDK_INT >= 30) ranking?.conversationShortcutInfo else null
        val shortcutId = if (Build.VERSION.SDK_INT >= 30) {
            shortcut?.id ?: notification.shortcutId?.takeIf { messaging }
        } else null
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        val name = sequenceOf(conversationTitle, shortcut?.shortLabel?.toString(), title)
            .mapNotNull { it?.let { raw -> ConversationKeys.displayName(sbn.packageName, raw) } }
            .filter { it.isNotEmpty() && it.length <= 160 }
            .firstOrNull { !aggregate.matches(it) && !it.equals(app, ignoreCase = true) }
            ?: return null
        if (shortcutId == null && conversationTitle.isNullOrBlank() &&
            (isGroup || (!messaging && notification.category != Notification.CATEGORY_MESSAGE &&
                sbn.packageName !in chatApps))
        ) return null
        // The fallback is exact title matching, scoped to app and Android profile.
        val identity = if (shortcutId != null) "shortcut:$shortcutId" else "name:$name"
        return ConversationSource(
            ConversationKeys.key(sbn.packageName, sbn.user.toString(), identity),
            name, sbn.packageName, app,
        )
    }

    fun digest(value: String): String = ConversationKeys.digest(value)
}
