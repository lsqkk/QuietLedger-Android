package dev.quietledger.app.core

import java.time.LocalDate

/**
 * 全 App 唯一的「结余 / 欠税」口径。
 *
 * 审计 P0-8：过去票据、明细四格、日详情、分享弹层、日报、桌面组件各自现场拼
 * 「欠 X 秒 / 余 X 秒 / 欠税/结余」，同一个意思四个词。这里统一成：
 * 标签只用「结余」或「欠税」，值只给正数，负数永远不出现。
 */
data class BalanceParts(
    /** 中文标签：结余 / 欠税。 */
    val label: String,
    /** 英文微标签：CREDIT / OWED。 */
    val latin: String,
    /** 绝对值（秒）。 */
    val absSeconds: Int,
    val owed: Boolean,
) {
    val valueText: String get() = "$absSeconds 秒"

    /** 带标签的一句，例如「结余 96 秒」。 */
    val shortText: String get() = "$label $absSeconds 秒"
}

/** 票据/日详情/分享/通知共用：balance < 0 记为欠税。 */
fun Invoice.balanceParts(): BalanceParts = if (isOwed) {
    BalanceParts(label = "欠税", latin = "OWED", absSeconds = -balance, owed = true)
} else {
    BalanceParts(label = "结余", latin = "CREDIT", absSeconds = balance, owed = false)
}

/** 近 N 天里的某一天：有没有欠税、应缴多少秒。 */
data class StreakDay(
    val date: String,
    val duty: Int,
    val events: Int,
    val owed: Boolean,
)

/**
 * 连续达标天数：从今天往回数，应缴 ≤ 免税额 就算达标；今天欠税时 current = 0。
 * 30 天小日历和桌面组件都读这里，避免两处各算一套。
 */
data class StreakInfo(
    val days: List<StreakDay>,
    val current: Int,
    val best: Int,
    val owedToday: Boolean,
    /** 今天超出免税额的秒数（结算后欠多少）；未欠税时为 0。 */
    val owedSeconds: Int,
)

/** 单应用趋势的一天。 */
data class AppTrendPoint(val date: String, val duty: Int, val events: Int)

/** 单应用近 N 天趋势：回答「这个应用是偶发，还是每天都在吵」。 */
data class AppTrend(
    val pkg: String,
    val app: String,
    val days: Int,
    val totalDuty: Int,
    val totalEvents: Int,
    val saved: Int,
    val points: List<AppTrendPoint>,
) {
    val maxDuty: Int get() = points.maxOfOrNull { it.duty } ?: 0
}

object Insights {

    /**
     * 近 [days] 天（含今天）逐日结算。计价一律复用 [Duty.compute]，与票据/报表/服务端同口径；
     * 账本里没有事件的日子也要出现在日历里，所以先按日期逐天算，不用事件去推。
     */
    fun streak(
        events: List<NotifEvent>,
        tariff: Tariff,
        today: LocalDate,
        days: Int = 30,
    ): StreakInfo {
        val n = days.coerceIn(1, 60)
        val out = ArrayList<StreakDay>(n)
        for (i in n - 1 downTo 0) {
            val day = today.minusDays(i.toLong())
            val inv = Duty.compute(events, tariff, day, today, "本机")
            out.add(StreakDay(Fiscal.iso(day), inv.totalDuty, inv.events, inv.isOwed))
        }
        // 从最后一天（今天）往回数连续达标；今天欠税则今天是断点。
        var current = 0
        for (i in out.indices.reversed()) {
            if (out[i].owed) break
            current += 1
        }
        var best = 0
        var run = 0
        for (d in out) {
            if (d.owed) {
                run = 0
            } else {
                run += 1
                if (run > best) best = run
            }
        }
        val todayRow = out.lastOrNull()
        return StreakInfo(
            days = out,
            current = current,
            best = maxOf(best, current),
            owedToday = todayRow?.owed == true,
            owedSeconds = todayRow?.let { (it.duty - tariff.exemptionPerDay).coerceAtLeast(0) } ?: 0,
        )
    }

    /**
     * 单应用近 [days] 天趋势。先把该应用的事件过滤出来再计价：
     * 连发判定本来就只同应用同日比较，过滤后结果与全量计价一致。
     */
    fun appTrend(
        events: List<NotifEvent>,
        tariff: Tariff,
        pkg: String,
        end: LocalDate,
        days: Int,
    ): AppTrend {
        val n = days.coerceIn(1, 60)
        val start = end.minusDays((n - 1).toLong())
        val rangeStart = Fiscal.dayStart(start)
        val rangeEnd = Fiscal.dayEnd(end)
        val mine = events.filter { it.pkg == pkg && it.ts in rangeStart until rangeEnd }
        val marked = Duty.mark(mine, tariff)
        val byDay = HashMap<String, Pair<Int, Int>>() // date -> duty, events
        var saved = 0
        for (m in marked) {
            val key = Fiscal.iso(Fiscal.dateOf(m.event.ts))
            val cur = byDay[key] ?: (0 to 0)
            byDay[key] = (cur.first + m.duty) to (cur.second + 1)
            saved += m.saved
        }
        val points = (0 until n).map { i ->
            val iso = Fiscal.iso(start.plusDays(i.toLong()))
            val v = byDay[iso] ?: (0 to 0)
            AppTrendPoint(iso, v.first, v.second)
        }
        return AppTrend(
            pkg = pkg,
            app = mine.firstOrNull()?.app ?: pkg,
            days = n,
            totalDuty = points.sumOf { it.duty },
            totalEvents = points.sumOf { it.events },
            saved = saved,
            points = points,
        )
    }

    /** 报表「复制本期摘要」：一句话，能在聊天框里直接读，不出现乘式/符号。 */
    fun reportSummary(r: Report): String {
        val start = r.start.substring(5)
        val end = r.end.substring(5)
        val peak = r.buckets.maxByOrNull { it.duty }
        val avg = if (r.dayCount <= 0) 0 else Math.round(r.totalDuty.toDouble() / r.dayCount).toInt()
        val head = "$start 至 $end：打扰 ${r.totalEvents} 条，应缴 ${r.totalDuty} 秒"
        val parts = ArrayList<String>(4)
        if (r.totalDuty >= 60) parts.add("约 ${Fmt2.duration(r.totalDuty)}")
        parts.add("日均 $avg 秒")
        if (peak != null && peak.duty > 0) {
            val label = if (r.monthBuckets) peak.label else peak.label
            parts.add("最高 $label，${peak.duty} 秒")
        }
        return head + if (parts.isEmpty()) "。" else "，" + parts.joinToString("，") + "。"
    }
}

/** 本地时长格式：与 ui 层的 Fmt 分开，避免 core 依赖 ui。 */
internal object Fmt2 {
    fun duration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m <= 0 -> "$s 秒"
            s == 0 -> "$m 分钟"
            else -> "$m 分 $s 秒"
        }
    }
}
