package app.apexview

import org.junit.Assert.*
import org.junit.Test

class OverlayRetainedDrawingTest {
    @Test fun retainedDisplayListIsNotReRecordedForVideoOnlyFrames() {
        val validity = OverlayRecordingValidity()
        val key = Any()
        assertTrue(validity.needsRecord(key, 640, 480))
        validity.markRecorded(key, 640, 480)
        repeat(1_000) {
            assertFalse(validity.needsRecord(key, 640, 480))
            validity.markReplayed()
        }
        assertEquals(1L, validity.recordings)
        assertEquals(1_000L, validity.replays)
    }

    @Test fun equalImmutableKeysFromNewTimingSnapshotsReuseTheRecordedLayer() {
        data class VisualKey(val geometry: String, val opacity: Int, val flags: Int)
        val validity = OverlayRecordingValidity()
        validity.markRecorded(VisualKey("monaco", 85, 4), 720, 420)
        repeat(300) {
            assertFalse(validity.needsRecord(VisualKey("monaco", 85, 4), 720, 420))
            validity.markReplayed()
        }
        assertEquals(1L, validity.recordings)
        assertEquals(300L, validity.replays)
        assertTrue(validity.needsRecord(VisualKey("monaco", 84, 4), 720, 420))
        assertTrue(validity.needsRecord(VisualKey("monaco", 85, 5), 720, 420))
        assertTrue(validity.needsRecord(VisualKey("monaco", 85, 4), 721, 420))
    }

    @Test fun changedStaticGeometryStyleOrPanelSizeInvalidatesOnlyTheNeededLayer() {
        val circuit = OverlayRecordingValidity()
        val events = OverlayRecordingValidity()
        val geometry = Any()
        val flags = Any()
        circuit.markRecorded(geometry, 400, 300)
        events.markRecorded(flags, 400, 300)
        assertFalse(circuit.needsRecord(geometry, 400, 300))
        assertFalse(events.needsRecord(flags, 400, 300))
        val nextFlags = Any()
        assertFalse(circuit.needsRecord(geometry, 400, 300))
        assertTrue(events.needsRecord(nextFlags, 400, 300))
        events.markRecorded(nextFlags, 400, 300)
        assertTrue(circuit.needsRecord(geometry, 402, 300))
        assertTrue(events.needsRecord(nextFlags, 402, 300))
        assertTrue(circuit.needsRecord(Any(), 400, 300))
        assertTrue(circuit.needsRecord(geometry, 400, 310))
    }

    @Test fun nativeRegionBoundsCoverExistingColumnsWithoutThreeFullRowLists() {
        for (rowWidth in listOf(181, 361, 722, 1083)) {
            for (density in listOf(0.5f, 1f, 2f, 3f)) {
                val unit = minOf(density, rowWidth / 361f)
                val base = towerRegionBounds(0, rowWidth, unit)
                val sectors = towerRegionBounds(1, rowWidth, unit)
                val tyres = towerRegionBounds(2, rowWidth, unit)
                assertEquals(TowerRegionBounds(0, rowWidth), base)
                assertTrue(sectors.left <= 202f * unit)
                assertTrue(sectors.right >= 330f * unit)
                assertTrue(tyres.left <= 331f * unit)
                assertTrue(tyres.right >= 361f * unit || tyres.right == rowWidth)
                assertTrue(sectors.right >= tyres.left)
                assertTrue(sectors.width < rowWidth)
                assertTrue(tyres.width < rowWidth)
            }
        }
    }

    @Test fun sharedBadgeClockOnlyRunsWhenAVisibleOnboardConsumesIt() {
        assertFalse(shouldShareOnboardBadgeClock(true, true, 4))
        assertFalse(shouldShareOnboardBadgeClock(false, false, 4))
        assertFalse(shouldShareOnboardBadgeClock(false, true, 0))
        assertTrue(shouldShareOnboardBadgeClock(false, true, 1))
        assertTrue(shouldShareOnboardBadgeClock(false, true, 6))
        // Preserve the existing 10Hz sampling and backwards clock recovery;
        // the independent draw-only 50/60Hz clock is deliberately unchanged.
        assertTrue(isOverlaySampleDue(1000L, Long.MIN_VALUE))
        assertFalse(isOverlaySampleDue(1099L, 1000L))
        assertTrue(isOverlaySampleDue(1100L, 1000L))
        assertTrue(isOverlaySampleDue(900L, 1000L))
    }

    @Test fun drawRearmSkipsHighRefreshWakeupWithoutSleepingAfterLateWork() {
        val frame = 1_000_000_000L
        assertEquals(9L, overlayDrawRearmDelayMs(frame, frame))
        assertEquals(5L, overlayDrawRearmDelayMs(frame, frame + 4_000_000L))
        assertEquals(0L, overlayDrawRearmDelayMs(frame, frame + 9_000_000L))
        assertEquals(0L, overlayDrawRearmDelayMs(frame, frame - 1L))
        assertTrue(isOverlayDrawFrameDue(frame + 16_666_666L, frame))
    }

    @Test fun drawClockSchedulesDrawOnlyRefreshForSixtyAndOneTwentyHertz() {
        for (hz in listOf(50, 60, 120)) {
            val interval = 1_000_000_000L / hz
            var last = Long.MIN_VALUE
            var triggered = 0
            var frame = 0L
            while (frame < 1_000_000_000L) {
                if (isOverlayDrawFrameDue(frame, last)) {
                    triggered++
                    last = frame
                }
                frame += interval
            }
            assertTrue("actual draw updates at $hz Hz were $triggered",
                triggered in 45..65)
        }
        assertTrue(isOverlayDrawFrameDue(500L, 600L))
    }
}
