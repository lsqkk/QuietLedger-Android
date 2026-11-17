package dev.quietledger.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import dev.quietledger.app.data.Prefs

/**
 * 通知整流：不是所有通知都算一次「打扰」。
 *
 * 实时过滤（逐条）：
 *  - 系统/壳应用常驻状态、前台服务、分组摘要、静默通道
 *  - 标题/正文属于系统状态文案
 *  - 同一个通知 key 且内容没变（同一条被重复贴出）、同样的内容 60 秒内重复
 *  - 同一应用在 120 秒内连续贴出 ≥3 条「文本特征相同」的通知（进度类），只记一次
 *
 * 注意：同一个 key 但内容变了（聊天会话来了新消息）要计一次，不能当重复丢掉。
 */
object NotifRules {

    /** 系统/壳应用包名前缀：系统自身状态，不是用户注意力被打扰。 */
    private val SYSTEM_PACKAGE_PREFIXES = listOf(
        "com.android.systemui",
        "com.miui.systemui",
        "com.miui.screenshot",
        "com.android.shell",
        "com.android.settings",
        "com.android.providers",
        "com.android.server",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.packageinstaller",
        "com.google.android.apps.wellbeing",
        "com.miui.securitycenter",
        "com.miui.powerkeeper",
        "com.miui.analytics",
        "com.miui.home",
        "com.miui.notification",
        "com.miui.contentcatcher",
        "com.miui.cloudservice",
        "com.xiaomi.xmsf",
        "com.xiaomi.metoknlp",
        "android",
    )

    private val SYSTEM_PACKAGE_EXACT = setOf(
        "android",
        "com.android.systemui",
        "com.android.shell",
        "com.android.settings",
        "com.miui.home",
    )

    /** 语义上就是状态/进度/服务的通知，不是聊天类打扰。 */
    private val SKIP_CATEGORIES = setOf(
        "service",
        "progress",
        "status",
        "sys",
        "system",
        "transport",
        "navigation",
        "recommendation",
        "stopwatch",
        "alarm",
    )

    private val NOISE_TEXTS = listOf(
        "点按即可了解详情",
        "正在后台运行",
        "正在运行",
        "正在处理",
        "Tap for more",
        "running in the background",
        "Running in the background",
    )

    fun isSystemPackage(pkg: String): Boolean {
        if (pkg in SYSTEM_PACKAGE_EXACT) return true
        return SYSTEM_PACKAGE_PREFIXES.any { pkg == it || pkg.startsWith("$it.") }
    }

    /** 群发提醒：@所有人 / @全体成员 / @全体 / @all / @everyone（大小写不敏感，支持全角 ＠）。 */
    private val BROADCAST_NOTICE_RE =
        Regex("[@＠]\\s*(所有人|全体成员|全体|all|everyone)", RegexOption.IGNORE_CASE)

    /**
     * v2026-11-c：纯本地判定「这一条是不是群发提醒」。只返回布尔值，
     * 命中的是 @ 后紧跟关键词，不给 App 增加任何额外的文本处理负担。
     */
    fun isBroadcastNotice(title: String, text: String): Boolean {
        if (title.isBlank() && text.isBlank()) return false
        return BROADCAST_NOTICE_RE.containsMatchIn(title + " " + text)
    }

    fun isNoiseText(text: String): Boolean = NOISE_TEXTS.any { text.contains(it) }

    fun systemPackages(): List<String> = SYSTEM_PACKAGE_PREFIXES

    /** 语义上不属于「打扰」的通知类别（历史清理同样按这个特征识别）。 */
    fun junkCategories(): Set<String> = SKIP_CATEGORIES

    /** 返回 null 表示这条通知应计入「打扰税」；否则返回被跳过的人类可读原因。 */
    fun skipReason(context: Context, sbn: StatusBarNotification, n: Notification): String? {
        if (Prefs.ignoredPackages(context).contains(sbn.packageName)) return "已设为不记录"
        if (isSystemPackage(sbn.packageName)) return "系统应用"
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return "分组摘要"
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return "常驻通知"
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return "前台服务"
        if (n.flags and Notification.FLAG_NO_CLEAR != 0) return "常驻通知"
        if (sbn.isOngoing) return "常驻通知"

        val category = n.category ?: ""
        if (category in SKIP_CATEGORIES) return "状态/进度类"

        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = n.channelId?.let { nm?.getNotificationChannel(it) }
            if (channel != null && channel.importance < NotificationManager.IMPORTANCE_DEFAULT) {
                return "静默通道"
            }
        }

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (isNoiseText(title) || isNoiseText("$title $text")) return "系统状态提示"
        if (title.isBlank() && text.isBlank() && n.actions.isNullOrEmpty()) return "没有内容"
        return null
    }

    /** 返回 true 表示这条通知应计入「打扰税」。 */
    fun shouldCount(context: Context, sbn: StatusBarNotification, n: Notification): Boolean =
        skipReason(context, sbn, n) == null

    // -- 去重与「刷屏风」识别 ------------------------------------------------

    /** 把数字、标点、空白拿掉，让 "下载 45%" 与 "下载 46%" 落到同一个特征上。 */
    fun normalize(text: String): String {
        val lowered = text.lowercase()
        val noDigits = lowered.replace(Regex("[0-9０-９]+"), "#")
        val compact = noDigits.replace(Regex("[\\s\\p{Punct}，。！？、：；（）《》【】]+"), "")
        return compact.take(40)
    }

    private const val KEY_WINDOW_MS = 8_000L
    private const val CONTENT_WINDOW_MS = 60_000L
    private const val STORM_WINDOW_MS = 120_000L
    private const val STORM_THRESHOLD = 3
    private const val MAX_ENTRIES = 800

    private val lastKeyAt = LinkedHashMap<String, Long>()
    private val lastContentForKey = LinkedHashMap<String, String>()
    private val lastContentAt = LinkedHashMap<String, Long>()
    private val recentByPkg = HashMap<String, ArrayDeque<Pair<Long, String>>>()

    /**
     * true = 短时间内的重复贴出，应跳过。
     *
     * 关键是区分两种「同一个 key」：
     *  - 内容没变：应用把同一条通知重新贴出（常驻/状态类），8 秒内跳过；
     *  - 内容变了：聊天类应用用同一个通知 key 承载新消息，**必须计一次**，
     *    否则一个会话从第二条消息起就再也不进账（v1.2 的真实漏记来源）。
     */
    @Synchronized
    fun isDuplicate(key: String, content: String, now: Long): Boolean {
        val prevKeyAt = lastKeyAt[key]
        val prevContent = lastContentForKey[key]
        val prevContentAt = lastContentAt[content]

        lastKeyAt[key] = now
        lastContentForKey[key] = content
        lastContentAt[content] = now
        if (lastKeyAt.size > MAX_ENTRIES) {
            val oldest = lastKeyAt.keys.first()
            lastKeyAt.remove(oldest)
            lastContentForKey.remove(oldest)
        }
        if (lastContentAt.size > MAX_ENTRIES) lastContentAt.remove(lastContentAt.keys.first())

        val sameKeySameText = prevKeyAt != null && prevContent == content &&
            now - prevKeyAt < KEY_WINDOW_MS
        val sameTextSoon = prevContentAt != null && now - prevContentAt < CONTENT_WINDOW_MS
        return sameKeySameText || sameTextSoon
    }

    /**
     * 同一应用在短窗口内刷出多条「文本特征相同」的通知（下载进度、同步进度、
     * 正在处理状态等），从第 3 条起视为刷屏，不再单独计税。
     */
    @Synchronized
    fun isFlood(pkg: String, title: String, text: String, now: Long): Boolean {
        val norm = normalize("$title $text")
        if (norm.length < 4) return false
        val queue = recentByPkg.getOrPut(pkg) { ArrayDeque() }
        queue.addLast(now to norm)
        while (queue.isNotEmpty() && now - queue.first().first > STORM_WINDOW_MS) {
            queue.removeFirst()
        }
        val same = queue.count { it.second == norm }
        if (queue.size > 40) {
            while (queue.size > 40) queue.removeFirst()
        }
        return same > STORM_THRESHOLD
    }

    /** 历史清理：按「同一应用 + 时间窗口 + 文本特征相同」找出刷屏事件 id。 */
    fun findFloodIds(
        events: List<Triple<String, Long, String>>, // eventId, ts, normalized text
        windowMs: Long = STORM_WINDOW_MS,
        threshold: Int = STORM_THRESHOLD,
    ): List<String> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.second }
        val junk = LinkedHashSet<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1].second - sorted[i].second <= windowMs) j++
            if (j - i + 1 > threshold) {
                val window = sorted.subList(i, j + 1)
                val counts = HashMap<String, Int>()
                for (e in window) counts[e.third] = (counts[e.third] ?: 0) + 1
                val dominant = counts.maxByOrNull { it.value }?.value ?: 0
                if (dominant > threshold) {
                    // 同一特征出现超过阈值：保留第一条，其余视为重复
                    val seen = HashSet<String>()
                    for (e in window) {
                        if (counts[e.third] ?: 0 <= threshold) continue
                        if (!seen.add(e.third)) junk.add(e.first)
                    }
                }
            }
            i++
        }
        return junk.toList()
    }
}
