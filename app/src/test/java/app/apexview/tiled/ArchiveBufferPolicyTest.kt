package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class ArchiveBufferPolicyTest {
    @Test fun readAheadStaysWithinThreeSegmentsAndArchiveEnd() {
        assertEquals(1..2, ArchiveBufferPolicy.warmFollowing(0, 12))
        assertEquals(12..12, ArchiveBufferPolicy.warmFollowing(11, 12))
        assertTrue(ArchiveBufferPolicy.warmFollowing(12, 12).isEmpty())
    }

    @Test fun playbackRequestPreemptsAnUnrelatedExpensiveWarmup() {
        assertTrue(ArchiveBufferPolicy.preemptWarmup(42, cached = false, active = 41))
        assertTrue(ArchiveBufferPolicy.preemptWarmup(88, cached = false, active = null))
        assertFalse(ArchiveBufferPolicy.preemptWarmup(41, cached = false, active = 41))
        assertFalse(ArchiveBufferPolicy.preemptWarmup(88, cached = true, active = 41))
        assertTrue(ArchiveBufferPolicy.preemptWarmup(88, cached = true, active = 41, seek = true))
        assertFalse(ArchiveBufferPolicy.preemptWarmup(41, cached = true, active = 41, seek = true))
    }

    @Test fun anExpiredPreflightWindowCannotReplaceThePlayingSources() {
        val captured = CmafWindowTiming(11_520_000, 17_280_000)
        assertFalse(ArchiveBufferPolicy.ready(captured, 19_600_000, 120_000_000, minimumLeadUs = 0))
        assertFalse(ArchiveBufferPolicy.ready(CmafWindowTiming(23_040_000, 28_800_000), 19_600_000, 120_000_000))
    }

    @Test fun startupFreezesTheClockUntilQueuedDataHasRealLead() {
        val clock = 27_600_000L
        val first = CmafWindowTiming(11_520_000, 28_800_000)
        assertTrue(ArchiveBufferPolicy.ready(first, clock, 120_000_000, minimumLeadUs = 0))
        assertFalse(ArchiveBufferPolicy.ready(first, clock, 120_000_000))
        assertFalse(ArchiveBufferPolicy.audioMayPlay(true, true, fillingStartupBuffer = true))
        val prefetched = CmafWindowTiming(17_280_000, 34_560_000)
        assertTrue(ArchiveBufferPolicy.ready(prefetched, clock, 120_000_000))
        assertTrue(ArchiveBufferPolicy.audioMayPlay(true, true))
        assertFalse(ArchiveBufferPolicy.audioMayPlay(true, false)) // a later real video stall
        assertFalse(ArchiveBufferPolicy.audioMayPlay(false, true)) // the user's pause remains authoritative
    }

    @Test fun prefetchesTwoFollowingSegmentsWithoutEvictingTheCurrentSegment() {
        val candidates = (0L..8L).map { ArchiveDashSegmentSelector.Candidate(it, it * 5_760_000L) }
        val clock = 16_000_000L
        val lead = ArchiveBufferPolicy.maximumLeadUs(candidates, clock)
        assertEquals(23_040_000L, clock + lead)
        val next = ArchiveDashSegmentSelector.selectAligned(listOf(candidates, candidates), clock,
            previousStartUs = 11_520_000, toleranceUs = 0, maximumLeadUs = lead)!!
        assertEquals(17_280_000L, next.first().absoluteStartUs)
        val final = ArchiveDashSegmentSelector.selectAligned(listOf(candidates, candidates), clock,
            previousStartUs = 17_280_000, toleranceUs = 0, maximumLeadUs = lead)!!
        assertEquals(23_040_000L, final.first().absoluteStartUs)
        assertNull(ArchiveDashSegmentSelector.selectAligned(listOf(candidates, candidates), clock,
            previousStartUs = 23_040_000, toleranceUs = 0, maximumLeadUs = lead))
    }

    @Test fun doesNotRequireThreeSecondsPastTheEndOfTheArchive() {
        assertTrue(ArchiveBufferPolicy.ready(CmafWindowTiming(0, 2_000_000), 1_500_000, 2_000_000))
        assertFalse(ArchiveBufferPolicy.ready(CmafWindowTiming(0, 2_000_000), 2_000_000, 2_000_000))
    }

    @Test fun largeMediaTimestampsAreScaledWithoutOverflow() {
        val ticks = 1_700_000_000L * 90_000L
        assertEquals(1_700_000_000_000_000L, CmafWindowTiming.ticksToUs(ticks, 90_000))
    }
}
