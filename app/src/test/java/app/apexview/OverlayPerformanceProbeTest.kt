package app.apexview

import org.junit.Assert.*
import org.junit.Test

class OverlayPerformanceProbeTest {
    @Test fun fixedBinsPublishOneBoundedFiveSecondReportWithoutPerFrameLogging() {
        var now = 100L
        val messages = mutableListOf<String>()
        val probe = OverlayPerformanceProbe("cast-receiver-map",
            report = { messages.add(it) }, clockMs = { now })
        repeat(100) {
            probe.record(1_500_000L, count = 20, changedCount = 12, seekCount = 0L)
            now += 25
        }
        assertTrue(messages.isEmpty())
        now += 3_000
        probe.record(20_000_000L, count = 20, changedCount = 20, seekCount = 1L)
        assertEquals(1, messages.size)
        val entry = messages.single()
        assertTrue(entry.contains("owner=cast-receiver-map"))
        assertTrue(entry.contains("samples=101"))
        assertTrue(entry.contains("meanObjects=20"))
        assertTrue(entry.contains("changed=1220"))
        assertTrue(entry.contains("seeks=1"))
        assertTrue(entry.contains("p95BucketUpperUs=2000"))
        assertFalse(entry.contains("token"))
        assertFalse(entry.contains("location"))
        now += 5_000
        probe.record(100_000L, 1, 0)
        assertEquals(2, messages.size)
        assertTrue(messages.last().contains("samples=1"))
    }

    @Test fun histogramsRemainFixedUnderLargeSamplesAndClockResets() {
        var now = 0L
        val reports = mutableListOf<String>()
        val probe = OverlayPerformanceProbe("tower", { reports.add(it) }) { now }
        repeat(10_000) {
            probe.record(40_000_000L, 20, 20)
        }
        now = 6_000
        probe.record(0L, 0, 0)
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("p95BucketUpperUs=-1"))
    }
}
