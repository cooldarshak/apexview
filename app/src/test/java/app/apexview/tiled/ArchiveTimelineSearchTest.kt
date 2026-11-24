package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class ArchiveTimelineSearchTest {
    @Test fun exactBoundariesAndArbitraryTargetsMatchLinearReference() {
        val starts = List(8000) { it * 5_760_000L }
        for (i in listOf(0, 1, 25, 3200, 7999)) {
            for (offset in listOf(-1L, 0L, 1L, 3_000_000L)) {
                val target = starts[i] + offset
                var comparisons = 0
                val actual = ArchiveTimelineSearch.floorIndex(starts.size, target) {
                    comparisons++; starts[it]
                }
                assertEquals(starts.indexOfLast { it <= target }.coerceAtLeast(0), actual)
                assertTrue(comparisons <= 14)
            }
        }
        assertEquals(7999, ArchiveTimelineSearch.floorIndex(starts.size, Long.MAX_VALUE) { starts[it] })
        assertEquals(0, ArchiveTimelineSearch.floorIndex(1, Long.MIN_VALUE) { 0L })
    }
    @Test fun extremeSeekTimesDoNotOverflow() {
        assertEquals(0L, ArchiveTimelineSearch.msToUs(Long.MIN_VALUE))
        assertEquals(10_000L, ArchiveTimelineSearch.msToUs(10L))
        assertTrue(ArchiveTimelineSearch.msToUs(Long.MAX_VALUE) > 0L)
    }
}
