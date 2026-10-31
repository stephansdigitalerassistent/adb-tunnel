package ch.heuscher.adbtunnel

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPacingTest {
    private val pacing = RetryPacing()

    @Test
    fun staysAwakeThroughTheWindow() {
        assertEquals(0, pacing.nextSleepMs(1_000))
        assertEquals(0, pacing.nextSleepMs(1_000 + RetryPacing.AWAKE_WINDOW_MS - 1))
    }

    @Test
    fun sleepsLongerEachTimeUpToTheLongest() {
        pacing.nextSleepMs(0)
        val sleeps = List(6) { pacing.nextSleepMs(RetryPacing.AWAKE_WINDOW_MS + it) }
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), sleeps)
    }

    @Test
    fun restartGivesANewAwakeWindow() {
        pacing.nextSleepMs(0)
        pacing.nextSleepMs(RetryPacing.AWAKE_WINDOW_MS)
        pacing.restart()
        val later = 10 * RetryPacing.AWAKE_WINDOW_MS
        assertEquals(0, pacing.nextSleepMs(later))
        assertEquals(RetryPacing.FIRST_SLEEP_MS, pacing.nextSleepMs(later + RetryPacing.AWAKE_WINDOW_MS))
    }

    /** 13 hours with a network but no server: about 4,700 tries at the old fixed 10 s. */
    @Test
    fun aLongOutageTriesAFewTimesAnHour() {
        var now = 0L
        var tries = 0
        while (now < 13 * 3_600_000L) {
            tries++
            val sleep = pacing.nextSleepMs(now)
            now += if (sleep == 0L) 10_000 else sleep
        }
        assertEquals(93, tries)
    }
}
