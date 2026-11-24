package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingBucketsTest {
    @Test fun fiftyFpsAndDisplayRefreshPatternsStayDistinguishable() {
        val histogram = TimingBuckets()
        repeat(50) { histogram.record(20_000_000L) }
        val fifty = histogram.snapshotAndReset()
        assertEquals(50L, fifty.count)
        assertEquals(listOf(0L, 0L, 50L, 0L, 0L, 0L, 0L), fifty.bins.toList())
        assertEquals(23, fifty.percentileUpperMs(95))

        repeat(25) { histogram.record(16_666_667L); histogram.record(25_000_000L) }
        val mixed = histogram.snapshotAndReset()
        assertEquals(listOf(0L, 25L, 0L, 25L, 0L, 0L, 0L), mixed.bins.toList())
        assertEquals(30, mixed.percentileUpperMs(95))
    }

    @Test fun everyBinHasCorrectBoundaryAndResetDoesNotCarryOldSamples() {
        val histogram = TimingBuckets()
        histogram.record(-1L)
        listOf(0L, 13_000_000L, 19_000_000L, 23_000_000L, 30_000_000L,
            45_000_000L, 100_000_000L).forEach(histogram::record)
        val measured = histogram.snapshotAndReset()
        assertEquals(7L, measured.count)
        assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 1L, 1L), measured.bins.toList())
        assertEquals(100.0, measured.maxMs, 0.0001)
        assertEquals(100, measured.percentileUpperMs(99))
        assertEquals(0L, histogram.snapshotAndReset().count)
    }

    @Test fun displayMultipleReportsActualSelectedRateNotAvailableAlternatives() {
        assertFalse(DisplayCadence.exactMultiple(120f, 50.0))
        assertFalse(DisplayCadence.exactMultiple(60f, 50.0))
        assertTrue(DisplayCadence.exactMultiple(100f, 50.0))
        assertTrue(DisplayCadence.exactMultiple(50f, 50.0))
        assertFalse(DisplayCadence.exactMultiple(0f, 50.0))
        assertFalse(DisplayCadence.exactMultiple(100f, 0.0))
    }
}
