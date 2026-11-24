package app.apexview

import org.junit.Assert.*
import org.junit.Test

class PlaybackStrainMonitorTest {
    private fun samples(drops: Int, ready: Boolean = true) = listOf(
        PlaybackStrainMonitor.Sample("main", ready, drops),
        PlaybackStrainMonitor.Sample("onboard", ready, 0),
    )

    @Test fun onlySustainedMultiDecoderPressureAfterTenSecondsCanHoldVideo() {
        val monitor = PlaybackStrainMonitor()
        assertFalse(monitor.onTick(0, 1.5f, 2, samples(0)))
        var held = false
        for (tick in 1..48) {
            if (monitor.onTick(tick * 500L, 1.5f, 2, samples((tick / 2) * 12)))
                held = true
        }
        assertTrue("Sustained dropping must be recognized", held)
        assertTrue(monitor.wasStrainedAt(2, 1.5f))
        assertFalse(monitor.wasStrainedAt(2, 1.0f))
        assertFalse(monitor.wasStrainedAt(4, 1.5f))
    }

    @Test fun singleVideoOrNonAcceleratedSpeedNeverTriggersHold() {
        val monitor = PlaybackStrainMonitor()
        for (i in 1..30) {
            assertFalse(monitor.onTick(i * 500L, 1f, 2, samples(i * 25)))
            assertFalse(monitor.onTick(i * 500L, 1.5f, 1,
                listOf(PlaybackStrainMonitor.Sample("composed", true, i * 25))))
        }
        assertFalse(monitor.wasStrainedAt(1, 1.5f))
        assertFalse(monitor.wasStrainedAt(2, 1f))
    }

    @Test fun bufferingOrChangingFeedSetResetsSamplingBaseline() {
        val monitor = PlaybackStrainMonitor()
        assertFalse(monitor.onTick(0, 2f, 2, samples(0)))
        assertFalse(monitor.onTick(11_000, 2f, 2, samples(300, ready = false)))
        assertFalse(monitor.onTick(11_500, 2f, 2, samples(600)))
        assertFalse(monitor.onTick(12_000, 2f, 2, samples(900)))
        assertFalse(monitor.wasStrainedAt(2, 2f))
        assertFalse(monitor.onTick(13_000, 2f, 2, listOf(
            PlaybackStrainMonitor.Sample("new", true, 1000),
            PlaybackStrainMonitor.Sample("onboard", true, 0))))
        assertFalse(monitor.wasStrainedAt(2, 2f))
    }
}
