package app.apexview.cast.local

import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePlaybackClockTest {
    @Test fun controllerClockAdvancesWithoutAnyPhonePlayerAndCorrectsForPauseSeekSpeedAndBuffering() {
        var now = 100L
        val clock = RemotePlaybackClock { now }
        clock.update(10_000, true, 1f, 100_000)
        now += 2500
        assertEquals(12_500, clock.positionMs())
        clock.update(clock.positionMs(), false, 1f)
        now += 10_000
        assertEquals(12_500, clock.positionMs())
        clock.update(40_000, true, 2f)
        now += 5000
        assertEquals(50_000, clock.positionMs())
        clock.update(48_000, false, 2f) // Receiver buffered; no imaginary advancement.
        now += 5000
        assertEquals(48_000, clock.positionMs())
        clock.update(99_000, true, 1f)
        now += 5000
        assertEquals(100_000, clock.positionMs())
    }
}
