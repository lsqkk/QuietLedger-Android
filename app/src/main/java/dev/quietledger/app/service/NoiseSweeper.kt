package dev.quietledger.app.service

import android.content.Context
import dev.quietledger.app.data.LedgerDb
import dev.quietledger.app.data.Prefs

/**
 * 历史刷屏自动合并：不按应用名清理，而是按特征识别 ——
 * 同一应用在 120 秒窗口内重复贴出文本特征相同（去掉数字/标点后一致）的通知，
 * 超过 3 条就把多余部分合并掉，本机删除并返回事件 id 供服务器同步删除。
 *
 * 由 [dev.quietledger.app.sync.SyncManager] 在每次同步时自动调用（无需用户操作）。
 */
object NoiseSweeper {

    data class Result(val removed: Int, val eventIds: List<String>)

    private const val LOOKBACK_MS = 48L * 60 * 60 * 1000

    fun sweep(context: Context, now: Long = System.currentTimeMillis()): Result {
        val db = LedgerDb.get(context)
        val events = db.eventsBetween(now - LOOKBACK_MS, now + 60_000L)
        if (events.isEmpty()) return Result(0, emptyList())

        val junkIds = LinkedHashSet<String>()
        // 1) 语义特征：进度/服务/状态类通知本来就不该计入打扰。
        val junkCategories = NotifRules.junkCategories()
        for (e in events) {
            if (e.category in junkCategories) junkIds.add(e.id)
        }
        // 2) 时间密度 + 文本相似度：同一应用 120 秒内重复贴出同一文本特征。
        for ((_, group) in events.filter { it.source == null }.groupBy { it.pkg }) {
            if (group.size < 4) continue
            val triples = group.map { Triple(it.id, it.ts, NotifRules.normalize(it.title)) }
            junkIds.addAll(NotifRules.findFloodIds(triples))
        }
        if (junkIds.isEmpty()) return Result(0, emptyList())

        val deleted = db.deleteEvents(junkIds.toList())
        if (deleted > 0) Prefs.addAutoMerged(context, deleted)
        android.util.Log.i("QuietLedger", "sweep events=${events.size} junk=${junkIds.size} deleted=$deleted")
        return Result(deleted, junkIds.toList())
    }
}
