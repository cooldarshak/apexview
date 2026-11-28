package app.apexview

import org.junit.Assert.*
import org.junit.Test

class CodecReleaseTimingTest {
    @Test fun mediaPtsAndPlannedCodecReleaseHaveSeparateCadences() {
        val stats = CodecReleaseTiming()
        val start = 1_000_000_000L
        stats.record(0L, start, start - 4_000_000L)
        stats.record(20_000L, start + 16_666_667L, start + 15_000_000L)
        stats.record(40_000L, start + 41_666_667L, start + 45_000_000L)
        val report = stats.snapshotAndReset()
        assertEquals(3, report.frames)
        assertEquals(1, report.callbacksAfterPlannedRelease)
        assertEquals(2L, report.ptsIntervals.bins[2])
        assertEquals(1L, report.plannedReleaseIntervals.bins[1])
        assertEquals(1L, report.plannedReleaseIntervals.bins[3])
        assertEquals(2L, report.plannedReleaseLead.count)
        assertEquals(0L, stats.snapshotAndReset().frames)
    }

    @Test fun epochResetExcludesForwardSeekAndOldPlaybackRate() {
        val stats = CodecReleaseTiming()
        stats.record(1_000_000L, 1_000_000_000L, 999_000_000L)
        stats.resetEpoch()
        stats.record(40_000_000L, 2_000_000_000L, 1_999_000_000L)
        val result = stats.snapshotAndReset()
        assertEquals(1L, result.frames)
        assertEquals(0L, result.ptsIntervals.count)
        assertEquals(0L, result.plannedReleaseIntervals.count)
    }

    @Test fun seekDiscontinuityMustNotCreateArtificialMediaInterval() {
        val stats = CodecReleaseTiming()
        stats.record(15_000_000L, 1_000_000_000L, 900_000_000L)
        stats.record(1_000_000L, 1_020_000_000L, 1_010_000_000L)
        assertEquals(0L, stats.snapshotAndReset().ptsIntervals.count)
    }
}
