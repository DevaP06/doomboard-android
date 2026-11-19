package app.doomboard

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

// One day of scrolling. hours[h] = reels counted in that hour (0-23).
data class Day(val reels: Int = 0, val secs: Int = 0, val hours: List<Int> = List(24) { 0 }, val sessions: Int = 0)

data class Kpi(val emoji: String, val value: String, val label: String)

// Pure Kotlin (no Android) so it can be unit-tested: see app/src/test/.../StatsTest.kt
object Stats {
    private val HOUR = DateTimeFormatter.ofPattern("h a")

    fun kpis(days: Map<LocalDate, Day>, today: LocalDate, nowHour: Int, limit: Int): List<Kpi> {
        val day = { d: LocalDate -> days[d] ?: Day() }
        val t = day(today)
        val week = (0..6).map { day(today.minusDays(it.toLong())) }                       // last 7 days incl. today
        val past = (1..7).map { day(today.minusDays(it.toLong())) }.filter { it.reels > 0 } // full days that have data

        // Fair comparison: today so far vs yesterday up to the same hour.
        val ySoFar = day(today.minusDays(1)).hours.take(nowHour + 1).sum()
        val vsYesterday = if (ySoFar == 0) "—" else ((t.reels - ySoFar) * 100.0 / ySoFar).roundToInt()
            .let { if (it >= 0) "+$it%" else "−${abs(it)}%" }

        val avgReels = if (past.isEmpty()) t.reels else (past.sumOf { it.reels }.toDouble() / past.size).roundToInt()
        val avgSecsPerDay = if (past.isEmpty()) t.secs.toDouble() else past.sumOf { it.secs }.toDouble() / past.size
        val weekReels = week.sumOf { it.reels }
        val perReel = if (weekReels == 0) "—" else "${(week.sumOf { it.secs }.toDouble() / weekReels).roundToInt()}s"

        val hourly = (0..23).map { h -> week.sumOf { it.hours[h] } }
        val peakHour = if (hourly.max() == 0) "—" else LocalTime.of(hourly.indexOf(hourly.max()), 0).format(HOUR)
        val lateNight = (0..4).sumOf { hourly[it] }

        val monthHours = avgSecsPerDay * 30 / 3600
        val pace = if (monthHours < 1) "${(monthHours * 60).roundToInt()}m" else "${"%.1f".format(monthHours)}h"

        val streak = if (limit > 0) limitStreak(days, today, limit) else null

        return listOf(
            Kpi("📈", vsYesterday, "vs yesterday by now"),
            Kpi("📅", "$avgReels", "avg reels / day"),
            Kpi("🔁", "${t.sessions}", "Reels sessions today"),
            Kpi("⚡", perReel, "avg time per reel"),
            Kpi("🕐", peakHour, "peak scroll hour (7d)"),
            Kpi("🌙", "$lateNight", "reels 12–5 AM (7d)"),
            Kpi("⏳", pace, "per month at this pace"),
            Kpi("🔥", streak?.let { "$it ${if (it == 1) "day" else "days"}" } ?: "—",
                if (limit > 0) "under-limit streak" else "set a limit for streaks"),
        )
    }

    // Days in a row within the limit, counting back from today (so far) to the first recorded day.
    fun limitStreak(days: Map<LocalDate, Day>, today: LocalDate, limit: Int): Int {
        val first = days.keys.minOrNull() ?: return 0
        var n = 0
        var d = today
        while (!d.isBefore(first) && (days[d]?.reels ?: 0) <= limit) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    // Alert text when the count crosses 80%, 100%, then every 20 over. Null = no alert for this count.
    fun limitAlert(reels: Int, limit: Int): String? {
        if (limit <= 0) return null
        val warnAt = (limit * 0.8).roundToInt().coerceAtLeast(1)
        return when {
            reels == warnAt && warnAt < limit -> "Heads up: $reels reels today. Your limit is $limit."
            reels == limit -> "🛑 Limit reached: $limit reels today. Time to touch grass?"
            reels > limit && (reels - limit) % 20 == 0 -> "Still scrolling? $reels reels today (limit $limit)."
            else -> null
        }
    }
}
