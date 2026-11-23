package app.apexview.scene

import org.junit.Assert.*
import org.junit.Test

class SceneCodecTimestampCorrelationTest {
    @Test fun correlatesCodecPtsToLatchAndEglSubmissionOnly() {
        val timing = SceneCodecTimestampCorrelation().apply { active = true }
        timing.onCodecFrame(1_000_000, 1_001_000_000, 1_000_000_000)
        timing.onTextureLatched(1_000_000_000, 1_003_000_000)
        timing.onSwapSubmitted(1_007_000_000)
        val snapshot = timing.snapshotAndReset()
        assertEquals(1L, snapshot.codecFrames)
        assertEquals(1L, snapshot.matchedLatches)
        assertEquals(0L, snapshot.unmatchedLatches)
        assertEquals(1L, snapshot.swapsAfterLatch)
        assertEquals(2.0, snapshot.releaseToLatch.maxMs, 0.001)
        assertEquals(3.0, snapshot.callbackToLatch.maxMs, 0.001)
        assertEquals(4.0, snapshot.latchToSwap.maxMs, 0.001)
        assertEquals(0L, timing.snapshotAndReset().matchedLatches)
    }

    @Test fun oemTimestampMismatchAndCoalescingDoNotFabricateMatches() {
        val timing = SceneCodecTimestampCorrelation().apply { active = true }
        timing.onCodecFrame(1_000_000, 1_000_000_000, 1_000_000_000)
        timing.onCodecFrame(1_020_000, 1_020_000_000, 1_020_000_000)
        timing.onTextureLatched(777, 1_030_000_000)
        timing.onTextureLatched(1_020_000_000, 1_035_000_000)
        timing.onSwapSubmitted(1_038_000_000)
        val report = timing.snapshotAndReset()
        assertEquals(1L, report.matchedLatches)
        assertEquals(1L, report.unmatchedLatches)
        assertEquals(3.0, report.latchToSwap.maxMs, 0.001)
    }

    @Test fun seekAndSurfaceRecreationDiscardOldCodecReleases() {
        val timing = SceneCodecTimestampCorrelation().apply { active = true }
        timing.onCodecFrame(500_000, 800_000_000, 800_000_000)
        timing.reset()
        timing.onTextureLatched(500_000_000, 850_000_000)
        assertEquals(1L, timing.snapshotAndReset().unmatchedLatches)
        timing.onCodecFrame(500_000, 900_000_000, 900_000_000)
        timing.onTextureLatched(500_000_000, 905_000_000)
        timing.onTextureLatched(500_000_000, 906_000_000)
        val report = timing.snapshotAndReset()
        assertEquals(1L, report.matchedLatches)
        assertEquals(1L, report.unmatchedLatches)
        timing.active = false
        timing.onCodecFrame(100, 100, 100)
        assertEquals(0L, timing.snapshotAndReset().codecFrames)
    }

    @Test fun fixedRingEvictsOldFramesButPreservesAcrossLogWindows() {
        val timing = SceneCodecTimestampCorrelation().apply { active = true }
        repeat(256) { i -> timing.onCodecFrame(1_000_000L + i * 20_000L, 1_000_000_000L, 1_000_000_000L) }
        assertEquals(256L, timing.snapshotAndReset().codecFrames)
        timing.onTextureLatched(1_000_000_000L, 9_000_000_000L)
        timing.onTextureLatched(6_100_000_000L, 9_200_000_000L)
        assertEquals(2L, timing.snapshotAndReset().unmatchedLatches)
        timing.reset()
        assertEquals(0L, timing.snapshotAndReset().swapsAfterLatch)
    }
}
