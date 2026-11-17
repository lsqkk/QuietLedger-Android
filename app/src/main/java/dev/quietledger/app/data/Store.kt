package dev.quietledger.app.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Process
import android.os.UserManager
import dev.quietledger.app.core.ConversationCandidate
import dev.quietledger.app.core.ConversationKeys
import dev.quietledger.app.core.ConversationOverview
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.core.ConversationClass
import dev.quietledger.app.core.ConversationRule
import dev.quietledger.app.core.ConversationSource
import dev.quietledger.app.core.NotifEvent
import dev.quietledger.app.core.Skipped
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LedgerDb private constructor(private val context: Context) :
    SQLiteOpenHelper(context.applicationContext, "ledger.db", null, 5) {

    private val eventProjection =
        "id,pkg,app,title,ts,priority,category,ongoing,clearable,dnd,notice," +
            "conversation_key,conversation_name,conversation_class"

    private val skippedDdl = """
        CREATE TABLE IF NOT EXISTS skipped (
            id TEXT PRIMARY KEY,
            pkg TEXT NOT NULL,
            app TEXT NOT NULL,
            title TEXT,
            ts INTEGER NOT NULL,
            reason TEXT NOT NULL
        )
    """.trimIndent()

    /** v3：events 新增 dnd/notice 两个布尔列（0/1），只表示命中规则，不存原文。 */
    private val eventColumnsV3 = arrayOf("dnd", "notice")

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events (
                id TEXT PRIMARY KEY,
                pkg TEXT NOT NULL,
                app TEXT NOT NULL,
                title TEXT,
                ts INTEGER NOT NULL,
                priority INTEGER NOT NULL DEFAULT 0,
                category TEXT,
                ongoing INTEGER NOT NULL DEFAULT 0,
                clearable INTEGER NOT NULL DEFAULT 1,
                dnd INTEGER NOT NULL DEFAULT 0,
                notice INTEGER NOT NULL DEFAULT 0,
                conversation_key TEXT,
                conversation_name TEXT,
                conversation_class TEXT NOT NULL DEFAULT '',
                synced INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
        db.execSQL("CREATE INDEX idx_events_synced ON events(synced, ts)")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL(skippedDdl)
        db.execSQL("CREATE INDEX idx_skipped_ts ON skipped(ts)")
        ensureConversationSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2：新增 skipped 表（被规则跳过的通知样本），老库平滑补齐。
        if (oldVersion < 2) {
            db.execSQL(skippedDdl)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_skipped_ts ON skipped(ts)")
        }
        // v3：events 补 dnd/notice 列，老事件默认 0（与旧税则结果一致）。
        if (oldVersion < 3) ensureEventFlagColumns(db)
        if (oldVersion < 4) ensureConversationSchema(db)
        if (oldVersion < 5) migrateQqConversationKeys(db)
    }

    private fun migrateQqConversationKeys(db: SQLiteDatabase) {
        val profiles = (context.getSystemService(UserManager::class.java).userProfiles +
            Process.myUserHandle()).distinct()

        fun normalized(key: String, pkg: String, name: String): Pair<String, String>? {
            val clean = ConversationKeys.displayName(pkg, name)
            if (clean.isEmpty() || clean == name) return null
            // V4 stored a hash, not the profile. Only rewrite a verified legacy
            // name key; shortcut keys need no change and other profiles stay isolated.
            val profile = profiles.firstOrNull {
                ConversationKeys.key(pkg, it.toString(), "name:$name") == key
            } ?: return null
            return ConversationKeys.key(pkg, profile.toString(), "name:$clean") to clean
        }

        val packages = "pkg IN ('com.tencent.mobileqq','com.tencent.tim')"
        val rules = db.rawQuery(
            "SELECT source_key,pkg,name FROM conversation_rules WHERE $packages ORDER BY rowid DESC", null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    normalized(key, cursor.getString(1), cursor.getString(2))?.let { add(key to it) }
                }
            }
        }
        for ((old, target) in rules) {
            val (key, name) = target
            // An existing clean rule wins; otherwise keep the most recently saved alias.
            db.execSQL(
                "UPDATE OR IGNORE conversation_rules SET source_key=?,name=? WHERE source_key=?",
                arrayOf(key, name, old),
            )
            db.delete("conversation_rules", "source_key=?", arrayOf(old))
        }
        val events = db.rawQuery(
            "SELECT DISTINCT conversation_key,pkg,conversation_name FROM events " +
                "WHERE $packages AND conversation_key IS NOT NULL AND conversation_name IS NOT NULL", null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    normalized(key, cursor.getString(1), cursor.getString(2))?.let { add(key to it) }
                }
            }
        }
        for ((old, target) in events) {
            db.execSQL(
                "UPDATE events SET conversation_key=?,conversation_name=? WHERE conversation_key=? AND $packages",
                arrayOf(target.first, target.second, old),
            )
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        // 防御：旧版本/异常数据库可能缺少 kv/skipped 表或 v3 新列，打开时补齐。
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL(skippedDdl)
        ensureEventFlagColumns(db)
        ensureConversationSchema(db)
    }

    private fun ensureConversationSchema(db: SQLiteDatabase) {
        val columns = db.rawQuery("PRAGMA table_info(events)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }
        for ((name, definition) in listOf(
            "conversation_key" to "TEXT",
            "conversation_name" to "TEXT",
            "conversation_class" to "TEXT NOT NULL DEFAULT ''",
        )) {
            if (name !in columns) db.execSQL("ALTER TABLE events ADD COLUMN $name $definition")
        }
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS conversation_rules (
                source_key TEXT PRIMARY KEY, name TEXT NOT NULL, pkg TEXT NOT NULL,
                app TEXT NOT NULL, kind TEXT NOT NULL
            )"""
        )
    }

    fun conversationRules(): List<ConversationRule> = readableDatabase.rawQuery(
        "SELECT source_key,name,pkg,app,kind FROM conversation_rules ORDER BY app,name,source_key",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(ConversationRule(
                ConversationSource(c.getString(0), c.getString(1), c.getString(2), c.getString(3)),
                ConversationClass.fromWire(c.getString(4)),
            ))
        }
    }

    internal fun conversationOverview(): ConversationOverview {
        val today = Fiscal.today()
        val pending = readableDatabase.rawQuery(
            """SELECT e.conversation_key,e.conversation_name,e.pkg,e.app,
                      SUM(CASE WHEN e.ts >= ? THEN 1 ELSE 0 END) AS today_count,
                      COUNT(*) AS week_count, MAX(e.ts) AS last_seen
               FROM events e
               WHERE e.ts >= ? AND e.ts < ? AND e.conversation_key IS NOT NULL
                 AND NOT EXISTS (SELECT 1 FROM conversation_rules r WHERE r.source_key=e.conversation_key)
               GROUP BY e.conversation_key
               HAVING today_count >= 2 OR week_count >= 3
               ORDER BY today_count DESC, week_count DESC, last_seen DESC, e.conversation_key""",
            arrayOf(
                Fiscal.dayStart(today).toString(), Fiscal.dayStart(today.minusDays(6)).toString(),
                Fiscal.dayEnd(today).toString(),
            ),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(ConversationCandidate(
                    ConversationSource(c.getString(0), c.getString(1), c.getString(2), c.getString(3)),
                    c.getInt(4), c.getInt(5),
                ))
            }
        }
        return ConversationOverview(conversationRules(), pending)
    }

    fun setConversationRule(source: ConversationSource, kind: ConversationClass) {
        // Explicitly choosing the application rate also marks a suggestion reviewed.
        writableDatabase.replaceOrThrow("conversation_rules", null, ContentValues().apply {
            put("source_key", source.key)
            put("name", source.name)
            put("pkg", source.pkg)
            put("app", source.app)
            put("kind", kind.wire)
        })
    }

    /** 幂等补列：老库没有 dnd/notice 时 ALTER 加上，已有则跳过。 */
    private fun ensureEventFlagColumns(db: SQLiteDatabase) {
        val existing = HashSet<String>()
        db.rawQuery("PRAGMA table_info(events)", null).use { c ->
            while (c.moveToNext()) existing.add(c.getString(1))
        }
        for (col in eventColumnsV3) {
            if (col in existing) continue
            db.execSQL("ALTER TABLE events ADD COLUMN $col INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun insertEvent(e: NotifEvent): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", e.id)
            put("pkg", e.pkg)
            put("app", e.app)
            put("title", e.title)
            put("ts", e.ts)
            put("priority", e.priority)
            put("category", e.category)
            put("ongoing", if (e.ongoing) 1 else 0)
            put("clearable", if (e.clearable) 1 else 0)
            put("dnd", if (e.dnd) 1 else 0)
            put("notice", if (e.notice) 1 else 0)
            put("conversation_key", e.source?.key)
            put("conversation_name", e.source?.name)
            put("conversation_class", e.conversationClass.wire)
            put("synced", 0)
        }
        val source = e.source ?: return db.insertWithOnConflict(
            "events", null, cv, SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        db.beginTransaction()
        try {
            db.rawQuery("SELECT kind FROM conversation_rules WHERE source_key=?", arrayOf(source.key)).use {
                if (it.moveToFirst()) cv.put("conversation_class", it.getString(0))
            }
            val inserted = db.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (inserted) db.execSQL(
                "UPDATE conversation_rules SET name=?,app=? WHERE source_key=? AND (name<>? OR app<>?)",
                arrayOf(source.name, source.app, source.key, source.name, source.app),
            )
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    fun pendingEvents(limit: Int = 200): List<NotifEvent> {
        val out = ArrayList<NotifEvent>()
        readableDatabase.rawQuery(
            "SELECT $eventProjection FROM events " +
                "WHERE synced=0 ORDER BY ts ASC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(c.toEvent())
        }
        return out
    }

    fun markSynced(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("UPDATE events SET synced=1 WHERE id=?")
            for (id in ids) {
                st.clearBindings()
                st.bindString(1, id)
                st.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pendingCount(): Int = countWhere("synced=0")

    fun totalCount(): Int = countWhere(null)

    private fun countWhere(where: String?): Int {
        val sql = if (where == null) "SELECT COUNT(*) FROM events" else "SELECT COUNT(*) FROM events WHERE $where"
        readableDatabase.rawQuery(sql, null).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun eventsBetween(startMs: Long, endMs: Long): List<NotifEvent> {
        val out = ArrayList<NotifEvent>()
        readableDatabase.rawQuery(
            "SELECT $eventProjection FROM events " +
                "WHERE ts>=? AND ts<? ORDER BY ts ASC",
            arrayOf(startMs.toString(), endMs.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(c.toEvent())
        }
        return out
    }

    fun recent(limit: Int): List<NotifEvent> {
        val out = ArrayList<NotifEvent>()
        readableDatabase.rawQuery(
            "SELECT $eventProjection FROM events " +
                "ORDER BY ts DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(c.toEvent())
        }
        return out
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("events", null, null)
            db.delete("skipped", null, null)
            db.delete("conversation_rules", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // -- 被跳过的通知样本（核对用，最近 3 天） ------------------------------

    fun insertSkipped(item: Skipped) {
        val cv = ContentValues().apply {
            put("id", item.id)
            put("pkg", item.pkg)
            put("app", item.app)
            put("title", item.title)
            put("ts", item.ts)
            put("reason", item.reason)
        }
        writableDatabase.insertWithOnConflict("skipped", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun skippedSince(startMs: Long, limit: Int = 120): List<Skipped> {
        val out = ArrayList<Skipped>()
        readableDatabase.rawQuery(
            "SELECT id,pkg,app,title,ts,reason FROM skipped WHERE ts>=? " +
                "ORDER BY ts DESC LIMIT ?",
            arrayOf(startMs.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Skipped(
                        id = c.getString(0),
                        pkg = c.getString(1),
                        app = c.getString(2),
                        title = c.getString(3) ?: "",
                        ts = c.getLong(4),
                        reason = c.getString(5) ?: "",
                    ),
                )
            }
        }
        return out
    }

    fun skippedCountSince(startMs: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM skipped WHERE ts>=?",
            arrayOf(startMs.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun skippedReasonsSince(startMs: Long): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT reason, COUNT(*) FROM skipped WHERE ts>=? GROUP BY reason ORDER BY 2 DESC",
            arrayOf(startMs.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
        }
        return out
    }

    fun pruneSkipped(cutoffMs: Long): Int =
        writableDatabase.delete("skipped", "ts < ?", arrayOf(cutoffMs.toString()))

    /** 清理历史刷屏/状态类事件；返回删除条数。 */
    fun deleteEvents(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        var deleted = 0
        val db = writableDatabase
        for (chunk in ids.chunked(400)) {
            val placeholders = chunk.joinToString(",") { "?" }
            deleted += db.delete("events", "id IN ($placeholders)", chunk.toTypedArray())
        }
        return deleted
    }

    /** 用户手动排除某个应用：删除该应用的本机记录。 */
    fun deleteByPackage(pkg: String): Int =
        writableDatabase.delete("events", "pkg = ?", arrayOf(pkg))

    /** 清理历史噪音：系统状态类包名，或标题命中文案规则。返回删除条数。 */
    fun purgeJunk(packages: List<String>, titleNeedles: List<String>): Int {
        if (packages.isEmpty() && titleNeedles.isEmpty()) return 0
        val ors = ArrayList<String>()
        val args = ArrayList<String>()
        if (packages.isNotEmpty()) {
            ors.add("pkg IN (" + packages.joinToString(",") { "?" } + ")")
            args.addAll(packages)
        }
        for (needle in titleNeedles) {
            ors.add("title LIKE ?")
            args.add("%$needle%")
        }
        return writableDatabase.delete("events", "(" + ors.joinToString(" OR ") + ")", args.toTypedArray())
    }

    fun pruneOlderThan(cutoffMs: Long): Int =
        writableDatabase.delete("events", "ts < ?", arrayOf(cutoffMs.toString()))

    private fun android.database.Cursor.toEvent(): NotifEvent = NotifEvent(
        id = getString(0),
        pkg = getString(1),
        app = getString(2),
        title = getString(3) ?: "",
        ts = getLong(4),
        priority = getInt(5),
        category = getString(6) ?: "msg",
        ongoing = getInt(7) == 1,
        clearable = getInt(8) == 1,
        dnd = getInt(9) == 1,
        notice = getInt(10) == 1,
        source = getString(11)?.let { ConversationSource(it, getString(12).orEmpty(), getString(1), getString(2)) },
        conversationClass = ConversationClass.fromWire(getString(13)),
    )

    companion object {
        @Volatile
        private var INSTANCE: LedgerDb? = null

        fun get(context: Context): LedgerDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LedgerDb(context.applicationContext).also { INSTANCE = it }
            }
    }
}

/** 轻量键值缓存：账户凭证、税率表、最近一次服务器税单。 */
object Prefs {
    private const val FILE = "quietledger"
    private const val KEY_FILTERED_DAY = "filtered_day"
    private const val KEY_FILTERED_TODAY = "filtered_today"
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deviceId(c: Context): String {
        val s = sp(c)
        val existing = s.getString("device_id", null)
        if (existing != null) return existing
        val model = Build.MODEL ?: "android"
        val gen = "dev-" + model.lowercase().replace(Regex("[^a-z0-9]"), "").take(10) + "-" +
            UUID.randomUUID().toString().take(6)
        s.edit().putString("device_id", gen).apply()
        return gen
    }

    fun modelName(): String = (Build.MANUFACTURER ?: "") + " " + (Build.MODEL ?: "")

    fun token(c: Context): String? = sp(c).getString("token", null)
    fun accountCode(c: Context): String? = sp(c).getString("account_code", null)

    fun saveAccount(c: Context, code: String, token: String) {
        val prefs = sp(c)
        val previous = prefs.getString("account_code", null)
        val editor = prefs.edit().putString("account_code", code).putString("token", token)
        if (previous != null && previous != code) {
            editor.remove("invoice_json").remove("tariff_json").remove("last_sync_at")
            editor.remove("tax_pending").remove("pending_tax_unit").remove("pending_tax_exemption")
        }
        editor.apply()
    }

    fun tariffJson(c: Context): String? = sp(c).getString("tariff_json", null)
    fun saveTariffJson(c: Context, json: String) {
        sp(c).edit().putString("tariff_json", json).apply()
    }

    // -- v2026-11-b 账户级税则：设置页先本地生效，联网后由 Sync 推给服务器 ----
    fun taxPending(c: Context): Boolean = sp(c).getBoolean("tax_pending", false)
    fun pendingTaxUnit(c: Context): Int = sp(c).getInt("tax_pending_unit", 8)
    fun pendingTaxExemption(c: Context): Int = sp(c).getInt("tax_pending_exemption", 240)

    fun setPendingTax(c: Context, unit: Int, exemption: Int) {
        sp(c).edit()
            .putBoolean("tax_pending", true)
            .putInt("tax_pending_unit", unit.coerceIn(2, 30))
            .putInt("tax_pending_exemption", exemption.coerceIn(0, 1800))
            .apply()
    }

    fun clearPendingTax(c: Context) {
        sp(c).edit()
            .putBoolean("tax_pending", false)
            .remove("tax_pending_unit")
            .remove("tax_pending_exemption")
            .apply()
    }

    fun invoiceJson(c: Context): String? = sp(c).getString("invoice_json", null)
    fun saveInvoiceJson(c: Context, json: String) {
        sp(c).edit().putString("invoice_json", json).apply()
    }

    fun lastSyncAt(c: Context): Long = sp(c).getLong("last_sync_at", 0L)
    fun setLastSyncAt(c: Context, ts: Long) {
        sp(c).edit().putLong("last_sync_at", ts).apply()
    }

    /** 最近一条通知被计入的时间：用来判断「监听是不是还活着」。 */
    fun lastEventAt(c: Context): Long = sp(c).getLong("last_event_at", 0L)
    fun setLastEventAt(c: Context, ts: Long) {
        sp(c).edit().putLong("last_event_at", ts).apply()
    }

    fun lastListenerRebindAt(c: Context): Long = sp(c).getLong("listener_rebind_at", 0L)
    fun setLastListenerRebindAt(c: Context, ts: Long) {
        sp(c).edit().putLong("listener_rebind_at", ts).apply()
    }

    fun lastError(c: Context): String? = sp(c).getString("last_error", null)
    fun setLastError(c: Context, msg: String?) {
        sp(c).edit().putString("last_error", msg).apply()
    }

    fun digestEnabled(c: Context): Boolean = sp(c).getBoolean("digest_enabled", true)
    fun setDigestEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("digest_enabled", on).apply()
    }

    fun dndUntil(c: Context): Long = sp(c).getLong("dnd_until", 0L)
    fun setDndUntil(c: Context, ts: Long) {
        sp(c).edit().putLong("dnd_until", ts).apply()
    }

    fun onboarded(c: Context): Boolean = sp(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context, done: Boolean) {
        sp(c).edit().putBoolean("onboarded", done).apply()
    }

    fun autoUpdateOnWifi(c: Context): Boolean = sp(c).getBoolean("auto_update_wifi", false)
    fun setAutoUpdateOnWifi(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("auto_update_wifi", on).apply()
    }

    fun lastUpdateCheck(c: Context): Long = sp(c).getLong("last_update_check", 0L)
    fun setLastUpdateCheck(c: Context, ts: Long) {
        sp(c).edit().putLong("last_update_check", ts).apply()
    }

    fun pendingApkPath(c: Context): String? = sp(c).getString("pending_apk_path", null)
    fun setPendingApkPath(c: Context, path: String?) {
        sp(c).edit().putString("pending_apk_path", path).apply()
    }

    fun filteredCount(c: Context): Int = sp(c).getInt("filtered_count", 0)

    /**
     * v1.6.0：今日被过滤条数（按北京时间自然日）。
     * 旧 [filteredCount] 是历史累计，和「今日跳过」样本数必然对不上，界面改用这个。
     */
    fun filteredToday(c: Context): Int {
        val s = sp(c)
        val day = s.getString(KEY_FILTERED_DAY, null)
        return if (day == Fiscal.iso(Fiscal.today())) s.getInt(KEY_FILTERED_TODAY, 0) else 0
    }

    /** 先按天重置再 +1：跨天后第一条自动从 1 开始。 */
    fun addFiltered(c: Context) {
        val s = sp(c)
        val today = Fiscal.iso(Fiscal.today())
        val sameDay = s.getString(KEY_FILTERED_DAY, null) == today
        val next = (if (sameDay) s.getInt(KEY_FILTERED_TODAY, 0) else 0) + 1
        s.edit()
            .putString(KEY_FILTERED_DAY, today)
            .putInt(KEY_FILTERED_TODAY, next)
            .apply()
    }

    // -- v1.6.0 隐私与同步开关 ----------------------------------------------

    /** 上送通知原文（标题+正文）；默认关闭，只上送应用、时间、条数与布尔标记。 */
    fun uploadTitles(c: Context): Boolean = sp(c).getBoolean("upload_titles", false)
    fun setUploadTitles(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("upload_titles", on).apply()
    }

    /** 云端同步总开关；默认开启。关闭后不发任何网络请求，本机记账照常。 */
    fun syncEnabled(c: Context): Boolean = sp(c).getBoolean("sync_enabled", true)
    fun setSyncEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("sync_enabled", on).apply()
    }

    // -- v1.8.0 免税额用尽提醒（当天一次） ----------------------------------

    /** 免税额用完后提醒一次；默认开启。只发系统通知，不改记账与同步。 */
    fun budgetAlertOn(c: Context): Boolean = sp(c).getBoolean("budget_alert_on", true)
    fun setBudgetAlert(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("budget_alert_on", on).apply()
    }

    /** 上次提醒过的北京自然日（ISO）；当天已提醒就不再发，跨天自然失效。 */
    fun budgetNotifiedDay(c: Context): String? = sp(c).getString("budget_notified_day", null)
    fun setBudgetNotifiedDay(c: Context, day: String) {
        sp(c).edit().putString("budget_notified_day", day).apply()
    }

    fun autoMergedCount(c: Context): Int = sp(c).getInt("auto_merged", 0)
    fun addAutoMerged(c: Context, n: Int) {
        val s = sp(c)
        s.edit().putInt("auto_merged", s.getInt("auto_merged", 0) + n).apply()
    }

    fun nickname(c: Context): String = sp(c).getString("nickname", "") ?: ""
    fun setNickname(c: Context, name: String) {
        sp(c).edit().putString("nickname", name).apply()
    }

    fun syncedNickname(c: Context): String = sp(c).getString("synced_nickname", "") ?: ""
    fun setSyncedNickname(c: Context, name: String) {
        sp(c).edit().putString("synced_nickname", name).apply()
    }

    fun clearEverything(c: Context) {
        sp(c).edit().clear().apply()
    }

    // -- v1.2 个性化 / 偏好 -------------------------------------------------

    fun themeId(c: Context): String = sp(c).getString("theme_id", "paper") ?: "paper"
    fun setThemeId(c: Context, id: String) {
        sp(c).edit().putString("theme_id", id).apply()
    }

    fun weeklyRange(c: Context): Int = sp(c).getInt("weekly_range", 7).coerceIn(1, 30)
    fun setWeeklyRange(c: Context, days: Int) {
        sp(c).edit().putInt("weekly_range", days.coerceIn(1, 30)).apply()
    }

    // -- 报表周期（周/月/季/年）---------------------------------------------

    /** 报表上次停留的周期：d7 / d30 / month / quarter / year。 */
    fun reportRange(c: Context): String = sp(c).getString("report_range", "d7") ?: "d7"
    fun setReportRange(c: Context, range: String) {
        sp(c).edit().putString("report_range", range).apply()
    }

    /** 报表查看的历史月份锚点（ISO 日期，空串=今天）。 */
    fun reportAnchor(c: Context): String = sp(c).getString("report_anchor", "") ?: ""
    fun setReportAnchor(c: Context, day: String) {
        sp(c).edit().putString("report_anchor", day).apply()
    }

    fun digestHour(c: Context): Int = sp(c).getInt("digest_hour", 21).coerceIn(0, 23)
    fun digestMinute(c: Context): Int = sp(c).getInt("digest_minute", 0).coerceIn(0, 59)
    fun setDigestTime(c: Context, hour: Int, minute: Int) {
        sp(c).edit()
            .putInt("digest_hour", hour.coerceIn(0, 23))
            .putInt("digest_minute", minute.coerceIn(0, 59))
            .apply()
    }

    fun ignoredPackages(c: Context): Set<String> =
        (sp(c).getStringSet("ignored_packages", emptySet()) ?: emptySet()).toSet()

    fun addIgnoredPackage(c: Context, pkg: String) {
        val next = ignoredPackages(c).toMutableSet()
        next.add(pkg)
        sp(c).edit().putStringSet("ignored_packages", next).apply()
    }

    fun removeIgnoredPackage(c: Context, pkg: String) {
        val next = ignoredPackages(c).toMutableSet()
        next.remove(pkg)
        sp(c).edit().putStringSet("ignored_packages", next).apply()
    }
}

/** 包名 -> 应用名，通知栏里拿不到应用名，这里做一次缓存解析。 */
object AppNames {
    private val cache = ConcurrentHashMap<String, String>()

    fun label(context: Context, pkg: String): String {
        cache[pkg]?.let { return it }
        val name = try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            fallback(pkg)
        }
        cache[pkg] = name
        return name
    }

    private fun fallback(pkg: String): String = when (pkg) {
        "com.android.shell" -> "命令行通知"
        "android" -> "Android 系统"
        "com.android.systemui" -> "系统界面"
        "com.android.settings" -> "系统设置"
        "com.google.android.deskclock", "com.android.deskclock" -> "时钟"
        "com.google.android.calendar", "com.android.calendar" -> "日历"
        else -> pkg.substringAfterLast('.').ifBlank { pkg }
    }
}
