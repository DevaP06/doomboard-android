package app.doomboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StatsTest {
    private val today = LocalDate.of(2026, 11, 20)
    private fun hours(vararg pairs: Pair<Int, Int>) = List(24) { h -> pairs.firstOrNull { it.first == h }?.second ?: 0 }

    @Test fun vsYesterdayComparesSameTimeOfDay() {
        val days = mapOf(
            today.minusDays(1) to Day(reels = 300, hours = hours(9 to 50, 22 to 250)), // most of yesterday was late
            today to Day(reels = 60, hours = hours(9 to 60)),
        )
        // at 10 AM: yesterday had 50 by now, today has 60 -> +20%, not -80%
        assertEquals("+20%", Stats.kpis(days, today, nowHour = 10, limit = 0)[0].value)
    }

    @Test fun peakHourAndLateNight() {
        val days = mapOf(today to Day(reels = 40, hours = hours(1 to 10, 23 to 30)))
        val k = Stats.kpis(days, today, nowHour = 23, limit = 0)
        assertEquals("11 PM", k[4].value.uppercase())
        assertEquals("10", k[5].value)
    }

    @Test fun streakStopsAtFirstDayOverLimit() {
        val days = mapOf(
            today.minusDays(3) to Day(reels = 50),
            today.minusDays(2) to Day(reels = 150), // over 100
            today.minusDays(1) to Day(reels = 80),
            today to Day(reels = 20),
        )
        assertEquals(2, Stats.limitStreak(days, today, 100))
        assertEquals(0, Stats.limitStreak(mapOf(today to Day(reels = 101)), today, 100))
    }

    @Test fun limitAlertsAt80PercentLimitAndEvery20Over() {
        assertEquals(true, Stats.limitAlert(80, 100)!!.startsWith("Heads up"))
        assertEquals(true, Stats.limitAlert(100, 100)!!.contains("Limit reached"))
        assertEquals(true, Stats.limitAlert(120, 100)!!.startsWith("Still scrolling"))
        assertNull(Stats.limitAlert(101, 100))
        assertNull(Stats.limitAlert(50, 0))
    }
}
