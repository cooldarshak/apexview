package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class ArchiveAudioSelectionPlanTest {
    @Test fun onlyKnownEqualFiniteTimelinesPermitStableMerging() {
        assertTrue(ArchiveAudioSelectionPlan.sameTimeline(100L, 100L, 10_000L, 10_000L))
        assertFalse(ArchiveAudioSelectionPlan.sameTimeline(null, 100L, 10_000L, 10_000L))
        assertFalse(ArchiveAudioSelectionPlan.sameTimeline(101L, 100L, 10_000L, 10_000L))
        assertFalse(ArchiveAudioSelectionPlan.sameTimeline(100L, 100L, 9_999L, 10_000L))
        assertFalse(ArchiveAudioSelectionPlan.sameTimeline(100L, 100L, 0L, 0L))
    }
    @Test fun primaryIsFirstAndUnverifiedOrDuplicateUrisCannotAddLoaders() {
        assertEquals(listOf("a", "b", "c"), ArchiveAudioSelectionPlan.children("a", listOf("b", "a", "c", "b")))
        assertEquals(listOf("x"), ArchiveAudioSelectionPlan.children("x", listOf("a", "b")))
        assertEquals(2, ArchiveAudioSelectionPlan.childIndex("2:audio"))
        assertNull(ArchiveAudioSelectionPlan.childIndex("0:video"))
        assertNull(ArchiveAudioSelectionPlan.childIndex("unknown"))
    }
}
