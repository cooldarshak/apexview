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
        // 1,000,000us is evicted by the 128-entry ring. The last frame
        // (6,100,000us) must still correlate across the logging boundary.
        timing.onTextureLatched(1_000_000_000L, 9_000_000_000L)
        timing.onTextureLatched(6_100_000_000L, 9_200_000_000L)
        val result = timing.snapshotAndReset()
        assertEquals("evicted first PTS stays unattributed", 1L, result.unmatchedLatches)
        assertEquals("retained final PTS matches", 1L, result.matchedLatches)
        timing.reset()
        assertEquals(0L, timing.snapshotAndReset().swapsAfterLatch)
    }
    @Test fun lateMetadataMatchesOnlyExactEarlierTexturePtsWithoutNegativeLatency() {
        val timing = SceneCodecTimestampCorrelation().apply { active = true }
        timing.onTextureLatched(1_000_000_000L, 1_004_000_000L)
        timing.onSwapSubmitted(1_005_000_000L)
        // Metadata arrived after EGL latched the frame. The planned release
        // can precede latch, but callback arrival may follow it.
        timing.onCodecFrame(1_000_000L, 1_002_000_000L, 1_006_000_000L)
        val report = timing.snapshotAndReset()
        assertEquals(1L, report.codecFrames)
        assertEquals(1L, report.matchedLatches)
        assertEquals(1L, report.lateMatchedLatches)
        assertEquals(1L, report.unmatchedLatches) // missing only AT latch time
        assertEquals(0, report.outstandingLatches)
        assertEquals(2.0, report.releaseToLatch.maxMs, 0.001)
        assertEquals(0L, report.callbackToLatch.count) // never fabricate negative latency
        assertEquals(1.0, report.latchToSwap.maxMs, 0.001)
    }

    @Test fun lateMetadataNeverCrossesSeekEpochOrMatchesAnUnrelatedPts() {
        val timing = SceneCodecTimestampCorrelation().apply { active = true }
        timing.onTextureLatched(400_000_000L, 401_000_000L)
        assertEquals(1, timing.snapshotAndReset().outstandingLatches)
        timing.onCodecFrame(500_000L, 400_000_000L, 401_000_000L)
        var report = timing.snapshotAndReset()
        assertEquals(0L, report.lateMatchedLatches)
        assertEquals(1, report.outstandingLatches)
        timing.reset()
        timing.onCodecFrame(400_000L, 600_000_000L, 600_000_000L)
        report = timing.snapshotAndReset()
        assertEquals(0L, report.lateMatchedLatches)
        assertEquals(0L, report.matchedLatches)
        assertEquals(0, report.outstandingLatches)
        timing.onTextureLatched(400_000_000L, 605_000_000L)
        assertEquals(1L, timing.snapshotAndReset().matchedLatches)
    }

    @Test fun pendingLatchCorrelationIsBoundedAndSurvivesFiveSecondLogReset() {
        val timing = SceneCodecTimestampCorrelation().apply { active = true }
        repeat(256) { i ->
            timing.onTextureLatched((1_000_000L + i * 20_000L) * 1000L, 9_000_000_000L)
        }
        assertEquals(128, timing.snapshotAndReset().outstandingLatches)
        timing.onCodecFrame(6_100_000L, 8_000_000_000L, 9_100_000_000L)
        timing.onCodecFrame(1_000_000L, 8_000_000_000L, 9_100_000_000L)
        val report = timing.snapshotAndReset()
        assertEquals(1L, report.lateMatchedLatches)
        assertEquals(127, report.outstandingLatches)
    }

    @Test fun reusedPtsNeverBackfillsAFrameWithReleaseScheduledTooFarInTheFuture() {
        val timing = SceneCodecTimestampCorrelation().apply { active = true }
        timing.onTextureLatched(900_000_000L, 950_000_000L)
        // The next rendition/decoder frame uses the same PTS but was only
        // scheduled 50ms later: it must not claim the earlier image.
        timing.onCodecFrame(900_000L, 1_000_000_000L, 1_000_000_000L)
        var metrics = timing.snapshotAndReset()
        assertEquals(0L, metrics.lateMatchedLatches)
        assertEquals(0L, metrics.matchedLatches)
        assertEquals(1, metrics.outstandingLatches)
        timing.onTextureLatched(900_000_000L, 1_005_000_000L)
        metrics = timing.snapshotAndReset()
        assertEquals(1L, metrics.matchedLatches)
        assertEquals(0L, metrics.lateMatchedLatches)
    }

    @Test fun boundedClockSchedulingLeadStillAllowsExactLateMetadataMatch() {
        val timing = SceneCodecTimestampCorrelation().apply { active = true }
        timing.onTextureLatched(2_000_000_000L, 2_020_000_000L)
        timing.onCodecFrame(2_000_000L, 2_040_000_000L, 2_041_000_000L)
        val metrics = timing.snapshotAndReset()
        assertEquals(1L, metrics.lateMatchedLatches)
        assertEquals(0, metrics.outstandingLatches)
        // Planned release and callback both follow the latch, so neither is
        // a valid positive latency sample.
        assertEquals(0L, metrics.releaseToLatch.count)
        assertEquals(0L, metrics.callbackToLatch.count)
    }

}
