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
    @Test fun noisyOneSecondCastHeartbeatsDoNotMoveThePlayheadBackwards() {
        var now = 0L
        val clock = RemotePlaybackClock { now }
        clock.update(5_000, true, 1f, 90_000)
        var previous = clock.positionMs()
        repeat(16) { index ->
            now += 1_000
            // Alternating 90ms of reported network/decoder clock variation.
            clock.reconcile(5_000L + now + if (index % 2 == 0) 90 else -90, true, 1f, 90_000)
            val next = clock.positionMs()
            assertEquals("source cursor should never jump backwards", true, next >= previous)
            assertEquals("bounded correction must stay close to the TV", true,
                kotlin.math.abs(next - (5_000 + now)) < 250)
            previous = next
        }
    }

    @Test fun seeksBufferingPausesAndSpeedChangesStillTakeEffectImmediately() {
        var now = 1_000L
        val clock = RemotePlaybackClock { now }
        clock.update(30_000, true, 1f)
        now += 1_000
        clock.reconcile(31_120, true, 1f)
        now += 250
        assertEquals(true, clock.positionMs() >= 31_250)
        clock.reconcile(10_000, true, 1f) // A real backward seek is not smoothed.
        assertEquals(10_000L, clock.positionMs())
        clock.reconcile(10_000, false, 1f) // Buffering freezes right away.
        now += 4_000
        assertEquals(10_000L, clock.positionMs())
        clock.reconcile(10_000, true, 1.5f)
        now += 1_000
        assertEquals(11_500L, clock.positionMs())
        clock.reconcile(11_500, false, 1.5f)
        now += 1_000
        assertEquals(11_500L, clock.positionMs())
    }
}
