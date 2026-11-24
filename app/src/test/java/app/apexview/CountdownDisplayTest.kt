package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownDisplayTest {
    private fun session(clock: SessionClock, name: String = "Qualifying", finished: Long? = null) = SessionTiming(
        type = name, drivers = emptyMap(), tracks = emptyMap(), states = emptyMap(),
        outline = floatArrayOf(), hasStartFinish = false, corners = emptyList(),
        startUtcMs = 1_000L, name = name, finishedUtc = finished, clock = clock,
    )

    @Test fun quarterSecondClockTicksReuseTheSameVisibleState() {
        val clock = SessionClock(listOf(SessionClock.Mark(1_000L, 60_000L, true)))
        val timing = session(clock)
        assertEquals(countdownDisplay(timing, 1_000L), countdownDisplay(timing, 1_250L))
        assertEquals(countdownDisplay(timing, 1_250L), countdownDisplay(timing, 1_750L))
        assertEquals("0:59", countdownDisplay(timing, 2_000L)?.text)
        assertTrue(countdownDisplay(timing, 2_000L)?.running == true)
    }

    @Test fun pausedAndFinishedClocksStillUpdateCorrectly() {
        val paused = session(SessionClock(listOf(SessionClock.Mark(1_000L, 29_500L, false))))
        assertEquals("0:30", countdownDisplay(paused, 1_000L)?.text)
        assertFalse(countdownDisplay(paused, 1_000L)!!.running)
        val finished = session(SessionClock(listOf(SessionClock.Mark(1_000L, 0L, false))), finished = 2_000L)
        assertEquals("0:00", countdownDisplay(finished, 1_500L)?.text)
        assertNull(countdownDisplay(finished, 2_000L))
        assertNull(countdownDisplay(finished, null))
    }

    @Test fun racesDoNotExposeCountdowns() {
        assertNull(countdownDisplay(session(SessionClock.NONE, name = "Race"), 1_000L))
    }
}
