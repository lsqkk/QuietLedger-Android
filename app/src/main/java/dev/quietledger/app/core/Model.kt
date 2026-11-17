package dev.quietledger.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * 税期结算统一使用北京时间（UTC+8），与后端保持一致。
 * 设备本地时区只影响系统界面，不参与计税。
 */
object Fiscal {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val HM_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val MD_FMT = DateTimeFormatter.ofPattern("MM-dd")

    fun today(): LocalDate = LocalDate.now(ZONE)
    fun dateOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZONE).toLocalDate()
    fun hourOf(ts: Long): Int = Instant.ofEpochMilli(ts).atZone(ZONE).hour
    fun hm(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZONE).format(HM_FMT)
    fun md(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZONE).format(MD_FMT)
    fun iso(date: LocalDate): String = date.format(DATE_FMT)
    fun parseDate(s: String): LocalDate = LocalDate.parse(s, DATE_FMT)
    fun dayStart(date: LocalDate): Long = date.atStartOfDay(ZONE).toInstant().toEpochMilli()
    fun dayEnd(date: LocalDate): Long = date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()

    fun weekdayZh(date: LocalDate): String = when (date.dayOfWeek.value) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; else -> "周日"
    }

    fun clockText(ts: Long): String {
        val z = Instant.ofEpochMilli(ts).atZone(ZONE)
        return String.format("%02d:%02d", z.hour, z.minute)
    }

    /** 服务器开票时间是 ISO-8601 UTC，界面统一换算成北京时间显示。 */
    fun issuedText(iso: String): String {
        if (iso.isBlank()) return "未结算"
        return try {
            val z = Instant.parse(iso).atZone(ZONE)
            String.format("%02d:%02d:%02d", z.hour, z.minute, z.second)
        } catch (_: Exception) {
            iso
        }
    }
}

data class NotifEvent(
    val id: String,
    val pkg: String,
    val app: String,
    val title: String,
    val ts: Long,
    val priority: Int,
    val category: String,
    val ongoing: Boolean,
    val clearable: Boolean,
    /** v2026-11-c：投递时设备处于免打扰（系统 DND 或本 App 静默）。只存布尔。 */
    val dnd: Boolean = false,
    /** v2026-11-c：命中群发提醒（@所有人/@全体成员/@all/@everyone）。只存布尔。 */
    val notice: Boolean = false,
    val source: ConversationSource? = null,
    val conversationClass: ConversationClass = ConversationClass.DEFAULT,
)

/**
 * 被判定为「不算打扰」而没有入账的通知。
 * 只保留最近几天的少量样本，用途是让用户能自己核对：
 * 到底是没收到通知，还是收到了但被规则跳过了。
 */
data class Skipped(
    val id: String,
    val pkg: String,
    val app: String,
    val title: String,
    val ts: Long,
    val reason: String,
)

data class Tariff(
    val version: String,
    val unitSeconds: Int,
    val nightMultiplier: Double,
    val nightStartHour: Int,
    val nightEndHour: Int,
    /** v2026-11-b：连发是折扣系数（0.4），不是加成。 */
    val burstMultiplier: Double,
    val burstWindowSec: Int,
    /** v2026-11-c：免打扰期间到达的折扣（0.5）。 */
    val dndMultiplier: Double = 0.5,
    /** v2026-11-c：群发提醒（@所有人等）的加成（2.0）。 */
    val noticeMultiplier: Double = 2.0,
    /** v2026-11-c：单条封顶倍数，单条税秒不超过 unit × 3.0。 */
    val capFactor: Double = 3.0,
    val exemptionPerDay: Int,
    /** 包名前缀 -> 单价权重；最长前缀优先，未命中用 1.0。 */
    val appMultipliers: Map<String, Double> = emptyMap(),
    val updatedAt: String,
) {
    fun isNight(hour: Int): Boolean = hour >= nightStartHour || hour < nightEndHour

    fun appFactor(pkg: String): Double {
        var factor = APP_MULTIPLIER_DEFAULT
        var bestLength = -1
        for ((prefix, value) in appMultipliers) {
            if (pkg.startsWith(prefix) && prefix.length > bestLength) {
                bestLength = prefix.length
                factor = value
            }
        }
        return factor
    }

    companion object {
        const val APP_MULTIPLIER_DEFAULT = 1.0

        /** 与服务端 APP_MULTIPLIERS / docs/TAX-RULES.md 保持一致。 */
        val DEFAULT_APP_MULTIPLIERS: Map<String, Double> = linkedMapOf(
            "com.tencent.mm" to 0.5,        // 微信
            "com.tencent.mobileqq" to 0.5,  // QQ
            "com.tencent.tim" to 0.5,       // TIM
            "com.tencent.wework" to 0.4,    // 企业微信
            "com.alibaba.android.rimet" to 0.4, // 钉钉
            "com.ss.android.lark" to 0.4,   // 飞书
            "com.larksuite.suite" to 0.4,   // Lark
            "com.microsoft.teams" to 0.4,   // Teams
            "com.slack" to 0.4,             // Slack
        )

        /** 服务器不可用时的保底规则，首次联网后会被服务端税率表覆盖。 */
        val DEFAULT = Tariff(
            version = "local-default",
            unitSeconds = 8,
            nightMultiplier = 1.5,
            nightStartHour = 23,
            nightEndHour = 7,
            burstMultiplier = 0.4,
            burstWindowSec = 120,
            exemptionPerDay = 240,
            appMultipliers = DEFAULT_APP_MULTIPLIERS,
            updatedAt = "",
        )
    }
}

/**
 * 一条通知按当前税则标记后的结果：夜间/连发/免打扰/群发是否命中、实际税秒与优惠秒数。
 * 明细折叠、票据「优惠已减 N 秒」、桌面组件都从这里取数，不再各自计价。
 */
data class MarkedEvent(
    val event: NotifEvent,
    val night: Boolean,
    val burst: Boolean,
    val dnd: Boolean,
    val notice: Boolean,
    val duty: Int,
    /** 单条优惠秒数 = 不含静默/连发/封顶折扣的原价 − 实际税秒。 */
    val saved: Int,
)

data class InvoiceLine(
    val app: String,
    val pkg: String,
    val count: Int,
    val duty: Int,
    val night: Int,
    val burst: Int,
    /** v2026-11-c：命中免打扰折扣的条数。 */
    val dnd: Int = 0,
    /** v2026-11-c：命中群发加成的条数。 */
    val notice: Int = 0,
    val note: String = "",
)

data class Invoice(
    val invoiceId: String,
    val accountCode: String,
    val date: String,
    val issuedAt: String,
    val tariffVersion: String,
    val lines: List<InvoiceLine>,
    val totalDuty: Int,
    val exemption: Int,
    val balance: Int,
    val status: String,
    val events: Int,
    val nightRatio: Double,
    val bursts: Int,
    /** v2026-11-c：全部优惠合计（连发 + 静默 + 封顶）省下的秒数；字段名沿用旧名。 */
    val savedSeconds: Int = 0,
    /** v2026-11-c：命中免打扰折扣的条数（默认值保证旧缓存可解析）。 */
    val dndEvents: Int = 0,
    /** v2026-11-c：命中群发加成的条数。 */
    val noticeEvents: Int = 0,
    val byHour: IntArray,
    val devices: List<String>,
    val source: String,
) {
    val isOwed: Boolean get() = balance < 0

    companion object {
        fun empty(date: String, accountCode: String, tariff: Tariff) = Invoice(
            invoiceId = "LOCAL-" + date.replace("-", ""),
            accountCode = accountCode,
            date = date,
            issuedAt = "",
            tariffVersion = tariff.version,
            lines = emptyList(),
            totalDuty = 0,
            exemption = tariff.exemptionPerDay,
            balance = tariff.exemptionPerDay,
            status = "paid",
            events = 0,
            nightRatio = 0.0,
            bursts = 0,
            byHour = IntArray(24),
            devices = emptyList(),
            source = "local",
        )
    }
}

data class WeeklyDay(val date: String, val events: Int, val duty: Int)
data class WeeklyApp(val app: String, val count: Int, val duty: Int)
data class Weekly(
    val start: String,
    val end: String,
    val totalDuty: Int,
    val totalEvents: Int,
    val days: List<WeeklyDay>,
    val topApps: List<WeeklyApp>,
    val nightRatio: Double,
    val tariffVersion: String,
    val source: String,
)

// ---------------------------------------------------------------- 报表（周 / 月 / 季 / 年）

/** 报表周期。周是滚动 N 天；月/季/年是自然周期（本机按北京时间、服务器同口径）。 */
enum class ReportKind(val key: String) {
    WEEK("week"), MONTH("month"), QUARTER("quarter"), YEAR("year");

    companion object {
        fun fromKey(key: String?): ReportKind = entries.firstOrNull { it.key == key } ?: WEEK
    }
}

/** 报表里一根柱子。日粒度 label 是 MM-dd，月粒度是 YYYY-MM；start/end 用于下钻。 */
data class ReportBucket(
    val label: String,
    val duty: Int,
    val events: Int,
    val start: String = "",
    val end: String = "",
)

/**
 * 报表税目排行的一行。[pkg] 用于取应用图标与下钻趋势；服务器旧版本可能不带，
 * 或缺省为空（汇总行「其他」），此时界面退回应用名首字占位。
 */
data class ReportApp(
    val app: String,
    val count: Int,
    val duty: Int,
    val pkg: String = "",
)

/** 等长的上一周期，用来给「较上期」一个参照；没有上一期时为空。 */
data class ReportWindow(val start: String, val end: String, val totalDuty: Int, val totalEvents: Int)

data class Report(
    val kind: String,
    val start: String,
    val end: String,
    val dayCount: Int,
    val totalDuty: Int,
    val totalEvents: Int,
    val savedSeconds: Int,
    val nightRatio: Double,
    /** 日粒度（周/月）或月粒度（季/年）。 */
    val buckets: List<ReportBucket>,
    val topApps: List<ReportApp>,
    val prev: ReportWindow?,
    val tariffVersion: String,
    val source: String,
) {
    /** 与上一周期相比的应缴差（正数 = 本期更多）。 */
    val delta: Int get() = prev?.let { totalDuty - it.totalDuty } ?: 0
    val monthBuckets: Boolean get() = kind == ReportKind.QUARTER.key || kind == ReportKind.YEAR.key
}

data class Account(val accountCode: String, val token: String, val deviceId: String)

data class DeviceInfo(val deviceId: String, val model: String, val lastSeenAt: String, val eventCount: Int)

/**
 * 计税引擎：与服务端 v2026-11-c 规则逐位一致（后端会独立重算，APK 只把事件交给服务器）。
 * 单价默认 8 秒/条（账户可改 2–30）；应用权重：微信/QQ/TIM ×0.5、办公类 ×0.4，其余 ×1.0；
 * 夜间（23:00-07:00）×1.5；群发提醒（@所有人等）×2.0；投递时免打扰 ×0.5；
 * 同应用同自然日内连发（<=120 秒）从第二条起 ×0.4 折扣；单条封顶 unit×3.0；
 * 顺序固定为 app × night × notice × dnd × burst，封顶后
 * duty = max(1, ceil(min(unit × mult, unit × 3.0)))，只在最后取整一次；
 * 每日免税额默认 240 秒（账户可改 0–1800）。
 */
object Duty {

    private class Acc(val pkg: String, val app: String) {
        var count = 0
        var duty = 0
        var night = 0
        var burst = 0
        var dnd = 0
        var notice = 0
    }

    /** 与服务端 CEIL_EPSILON 相同：容忍 IEEE-754 尾差（如 25 × 0.4 多出 1 秒）。 */
    private const val CEIL_EPSILON = 1e-9

    private fun dutyCeil(value: Double): Int =
        ceil(value - CEIL_EPSILON).toInt().coerceAtLeast(1)

    /**
     * 按 ts 升序标记每条通知：是否夜间/连发/免打扰/群发、实际税秒与优惠秒数。
     * 连发只在 Asia/Shanghai 自然日内判断（key 带本地日期，0 点自动重置），
     * 因此传多天的事件也不会把跨日的第一条算成连发，与服务端 burst_flags 一致。
     * 明细折叠 / 票据「优惠已减 N 秒」/ 桌面组件都用这里的结果，不再各自计价。
     */
    fun mark(events: List<NotifEvent>, tariff: Tariff): List<MarkedEvent> {
        val sorted = events.sortedWith(compareBy<NotifEvent> { it.ts }.thenBy { it.id })
        val lastTsByDayPkg = HashMap<String, Long>()
        return sorted.map { e ->
            val hour = Fiscal.hourOf(e.ts).coerceIn(0, 23)
            val night = tariff.isNight(hour)
            val dnd = e.dnd
            val notice = e.notice
            val kind = e.conversationClass
            val dayKey = Fiscal.iso(Fiscal.dateOf(e.ts)) + "|" + e.pkg + "|" + kind.wire
            val prev = lastTsByDayPkg[dayKey]
            val burst = kind != ConversationClass.FREE && prev != null &&
                (e.ts - prev) <= tariff.burstWindowSec * 1000L
            if (kind != ConversationClass.FREE) lastTsByDayPkg[dayKey] = e.ts

            // 与服务端逐位一致的运算顺序：appFactor → 夜间 → 群发 → 免打扰 → 连发 → 封顶。
            var mult = kind.factor ?: tariff.appFactor(e.pkg)
            var baseMult = mult
            if (night) {
                mult *= tariff.nightMultiplier
                baseMult *= tariff.nightMultiplier
            }
            if (notice) {
                mult *= tariff.noticeMultiplier
                baseMult *= tariff.noticeMultiplier
            }
            if (dnd) mult *= tariff.dndMultiplier
            if (burst) mult *= tariff.burstMultiplier
            val unit = tariff.unitSeconds.toDouble()
            val raw = unit * mult
            val capped = minOf(raw, unit * tariff.capFactor)
            val duty = if (kind == ConversationClass.FREE) 0 else dutyCeil(capped)
            // 优惠合计：只含静默/连发/封顶，不含夜间与群发加成。
            val base = if (kind == ConversationClass.FREE) 0 else dutyCeil(unit * baseMult)
            val saved = (base - duty).coerceAtLeast(0)
            MarkedEvent(e, night, burst, dnd, notice, duty, saved)
        }
    }

    fun compute(
        events: List<NotifEvent>,
        tariff: Tariff,
        date: LocalDate,
        today: LocalDate,
        accountCode: String,
    ): Invoice {
        val start = Fiscal.dayStart(date)
        val end = Fiscal.dayEnd(date)
        val inDay = events.filter { it.ts in start until end }
        val marked = mark(inDay, tariff)

        val byPkg = LinkedHashMap<String, Acc>()
        val byHour = IntArray(24)
        var totalDuty = 0
        var savedSeconds = 0
        var nightCount = 0
        var burstCount = 0
        var dndCount = 0
        var noticeCount = 0

        for (m in marked) {
            val e = m.event
            val hour = Fiscal.hourOf(e.ts).coerceIn(0, 23)
            byHour[hour] = byHour[hour] + 1

            val acc = byPkg.getOrPut(e.pkg) { Acc(e.pkg, e.app) }
            acc.count += 1
            acc.duty += m.duty
            if (m.night) { acc.night += 1; nightCount += 1 }
            if (m.burst) { acc.burst += 1; burstCount += 1 }
            if (m.dnd) { acc.dnd += 1; dndCount += 1 }
            if (m.notice) { acc.notice += 1; noticeCount += 1 }
            totalDuty += m.duty
            savedSeconds += m.saved
        }

        val allLines = byPkg.values.map { acc ->
            // 固定顺序：夜间、连发、免打扰、群发；只拼非零项。
            val notes = buildList {
                if (acc.night > 0) add("夜间 ${acc.night} 条")
                if (acc.burst > 0) add("连发 ${acc.burst} 条")
                if (acc.dnd > 0) add("免打扰 ${acc.dnd} 条")
                if (acc.notice > 0) add("群发 ${acc.notice} 条")
            }
            InvoiceLine(
                app = acc.app,
                pkg = acc.pkg,
                count = acc.count,
                duty = acc.duty,
                night = acc.night,
                burst = acc.burst,
                dnd = acc.dnd,
                notice = acc.notice,
                note = notes.joinToString(" · "),
            )
        }.sortedWith(compareByDescending<InvoiceLine> { it.duty }.thenBy { it.app })

        val lines: List<InvoiceLine> = if (allLines.size > 40) {
            val head = allLines.take(39)
            val rest = allLines.drop(39)
            head + InvoiceLine(
                app = "其他",
                pkg = "",
                count = rest.sumOf { it.count },
                duty = rest.sumOf { it.duty },
                night = rest.sumOf { it.night },
                burst = rest.sumOf { it.burst },
                dnd = rest.sumOf { it.dnd },
                notice = rest.sumOf { it.notice },
                note = "长尾税目合并",
            )
        } else {
            allLines
        }

        val exemption = tariff.exemptionPerDay
        val balance = exemption - totalDuty
        val isToday = date == today
        return Invoice(
            invoiceId = "LOCAL-" + Fiscal.iso(date).replace("-", ""),
            accountCode = accountCode,
            date = Fiscal.iso(date),
            issuedAt = if (isToday) "本机 " + Fiscal.clockText(System.currentTimeMillis()) else "本机",
            tariffVersion = tariff.version,
            lines = lines,
            totalDuty = totalDuty,
            exemption = exemption,
            balance = balance,
            status = if (balance >= 0) "paid" else "owed",
            events = inDay.size,
            nightRatio = if (inDay.isEmpty()) 0.0 else nightCount.toDouble() / inDay.size,
            bursts = burstCount,
            savedSeconds = savedSeconds,
            dndEvents = dndCount,
            noticeEvents = noticeCount,
            byHour = byHour,
            devices = emptyList(),
            source = "local",
        )
    }

    fun weeklyLocal(
        events: List<NotifEvent>,
        tariff: Tariff,
        end: LocalDate,
        accountCode: String,
        rangeDays: Int = 7,
    ): Weekly {
        val days = rangeDays.coerceIn(1, 30)
        val start = end.minusDays((days - 1).toLong())
        val dayList = (0 until days).map { start.plusDays(it.toLong()) }
        val invoices = dayList.map { compute(events, tariff, it, end, accountCode) }
        val dayRows = dayList.mapIndexed { i, d ->
            WeeklyDay(Fiscal.iso(d), invoices[i].events, invoices[i].totalDuty)
        }
        val rangeStart = Fiscal.dayStart(start)
        val rangeEnd = Fiscal.dayEnd(end)
        val inRange = events.filter { it.ts in rangeStart until rangeEnd }
        val dutyByApp = HashMap<String, Pair<Int, Int>>() // app -> count, duty
        for (inv in invoices) {
            for (line in inv.lines) {
                if (line.pkg.isEmpty() && line.app == "其他") continue
                val cur = dutyByApp[line.app] ?: (0 to 0)
                dutyByApp[line.app] = (cur.first + line.count) to (cur.second + line.duty)
            }
        }
        val top = dutyByApp.entries
            .sortedByDescending { it.value.second }
            .take(5)
            .map { WeeklyApp(it.key, it.value.first, it.value.second) }
        val nightCount = inRange.count { e ->
            val h = Fiscal.hourOf(e.ts)
            tariff.isNight(h)
        }
        return Weekly(
            start = Fiscal.iso(start),
            end = Fiscal.iso(end),
            totalDuty = dayRows.sumOf { it.duty },
            totalEvents = dayRows.sumOf { it.events },
            days = dayRows,
            topApps = top,
            nightRatio = if (inRange.isEmpty()) 0.0 else nightCount.toDouble() / inRange.size,
            tariffVersion = tariff.version,
            source = "local",
        )
    }

    // -- 报表：周 / 月 / 季 / 年 -------------------------------------------------

    /** 周期起点：周=滚动 N 天，月/季/年=自然周期（北京时间）。 */
    fun reportStart(kind: ReportKind, end: LocalDate, days: Int = 7): LocalDate = when (kind) {
        ReportKind.WEEK -> end.minusDays((days.coerceIn(1, 30) - 1).toLong())
        ReportKind.MONTH -> end.withDayOfMonth(1)
        ReportKind.QUARTER -> LocalDate.of(end.year, ((end.monthValue - 1) / 3) * 3 + 1, 1)
        ReportKind.YEAR -> LocalDate.of(end.year, 1, 1)
    }

    /**
     * 本机报表：逐日计价复用 [compute]（与票据/周报/服务端同一套规则），
     * 再按日或按月聚合成柱子。prev 是等长的上一周期，只用于「较上期」。
     */
    fun reportLocal(
        events: List<NotifEvent>,
        tariff: Tariff,
        kind: ReportKind,
        end: LocalDate,
        days: Int,
        accountCode: String,
    ): Report {
        val start = reportStart(kind, end, days)
        val dayCount = (end.toEpochDay() - start.toEpochDay() + 1).toInt().coerceAtLeast(1)
        val dates = (0 until dayCount).map { start.plusDays(it.toLong()) }
        val invoices = dates.map { compute(events, tariff, it, end, accountCode) }
        val totalDuty = invoices.sumOf { it.totalDuty }
        val totalEvents = invoices.sumOf { it.events }
        val savedSeconds = invoices.sumOf { it.savedSeconds }
        val rangeStart = Fiscal.dayStart(start)
        val rangeEnd = Fiscal.dayEnd(end)
        val inRange = events.filter { it.ts in rangeStart until rangeEnd }
        val nightCount = inRange.count { tariff.isNight(Fiscal.hourOf(it.ts)) }

        val buckets: List<ReportBucket> = if (kind == ReportKind.WEEK || kind == ReportKind.MONTH) {
            dates.mapIndexed { i, d ->
                ReportBucket(
                    label = Fiscal.iso(d).substring(5),
                    duty = invoices[i].totalDuty,
                    events = invoices[i].events,
                    start = Fiscal.iso(d),
                    end = Fiscal.iso(d),
                )
            }
        } else {
            // 季/年按自然月聚合；周期首尾裁剪到 start/end，零税月也保留一根柱子。
            val out = ArrayList<ReportBucket>()
            var month = start.withDayOfMonth(1)
            while (!month.isAfter(end)) {
                val first = if (month.isBefore(start)) start else month
                val monthEnd = month.withDayOfMonth(month.lengthOfMonth())
                val last = if (monthEnd.isAfter(end)) end else monthEnd
                val from = (first.toEpochDay() - start.toEpochDay()).toInt()
                val to = (last.toEpochDay() - start.toEpochDay()).toInt()
                out.add(
                    ReportBucket(
                        label = Fiscal.iso(month).substring(0, 7),
                        duty = invoices.subList(from, to + 1).sumOf { it.totalDuty },
                        events = invoices.subList(from, to + 1).sumOf { it.events },
                        start = Fiscal.iso(first),
                        end = Fiscal.iso(last),
                    ),
                )
                month = month.plusMonths(1)
            }
            out
        }

        // 排行按「应用 + 包名」聚合：同一个应用名可能来自多个包（如微信分身），
        // 不能只看名字合并；包名也用于报表行取图标与点开趋势。
        class Row(val pkg: String, val app: String, var count: Int, var duty: Int)
        val rows = LinkedHashMap<String, Row>()
        for (inv in invoices) {
            for (line in inv.lines) {
                if (line.pkg.isEmpty() && line.app == "其他") continue
                val key = line.pkg.ifEmpty { line.app }
                val row = rows.getOrPut(key) { Row(line.pkg, line.app, 0, 0) }
                row.count += line.count
                row.duty += line.duty
            }
        }
        val top = rows.values
            .sortedWith(compareByDescending<Row> { it.duty }.thenByDescending { it.count })
            .take(5)
            .map { ReportApp(app = it.app, count = it.count, duty = it.duty, pkg = it.pkg) }

        val prevStart = start.minusDays(dayCount.toLong())
        val prevEnd = start.minusDays(1)
        val prevInvoices = (0 until dayCount).map {
            compute(events, tariff, prevStart.plusDays(it.toLong()), prevEnd, accountCode)
        }
        val prev = ReportWindow(
            start = Fiscal.iso(prevStart),
            end = Fiscal.iso(prevEnd),
            totalDuty = prevInvoices.sumOf { it.totalDuty },
            totalEvents = prevInvoices.sumOf { it.events },
        )

        return Report(
            kind = kind.key,
            start = Fiscal.iso(start),
            end = Fiscal.iso(end),
            dayCount = dayCount,
            totalDuty = totalDuty,
            totalEvents = totalEvents,
            savedSeconds = savedSeconds,
            nightRatio = if (totalEvents == 0) 0.0 else nightCount.toDouble() / totalEvents,
            buckets = buckets,
            topApps = top,
            prev = prev,
            tariffVersion = tariff.version,
            source = "local",
        )
    }
}

object Fmt {
    fun seconds(n: Int): String = when {
        n >= 3600 -> {
            val h = n / 3600
            val m = (n % 3600) / 60
            "$h 时 $m 分"
        }
        n >= 60 -> {
            val m = n / 60
            val s = n % 60
            if (s == 0) "$m 分" else "$m 分 $s 秒"
        }
        else -> "$n 秒"
    }

    fun signedSeconds(n: Int): String = if (n > 0) "+$n" else "$n"

    fun percent(v: Double): String = String.format("%.0f%%", v * 100)
}
