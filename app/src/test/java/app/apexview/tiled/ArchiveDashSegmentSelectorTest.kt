package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveDashSegmentSelectorTest {
    @Test
    fun selectsTheSegmentNearestTheCurrentArchivePositionAcrossFeeds() {
        val selected = ArchiveDashSegmentSelector.selectAligned(
            candidateWindows = listOf(
                listOf(candidate(10, 0), candidate(11, 1_000_000), candidate(12, 2_000_000)),
                listOf(candidate(30, 40_000), candidate(31, 1_040_000), candidate(32, 2_040_000))
            ),
            targetTimeUs = 1_100_000,
            previousStartUs = null,
            toleranceUs = 100_000
        )

        assertEquals(listOf(11L, 31L), selected?.map { it.number })
    }

    @Test
    fun choosesOnlyTheNextContiguousSegmentAfterTheLastCapturedSegment() {
        val selected = ArchiveDashSegmentSelector.selectAligned(
            candidateWindows = listOf(
                listOf(candidate(10, 0), candidate(11, 1_000_000), candidate(12, 2_000_000)),
                listOf(candidate(30, 30_000), candidate(31, 1_030_000), candidate(32, 2_030_000))
            ),
            targetTimeUs = 1_100_000,
            previousStartUs = 0,
            toleranceUs = 100_000
        )

        assertEquals(listOf(11L, 31L), selected?.map { it.number })
    }

    @Test
    fun waitsInsteadOfCapturingFarAheadOfTheArchivePlayer() {
        val selected = ArchiveDashSegmentSelector.selectAligned(
            candidateWindows = listOf(
                listOf(candidate(10, 0), candidate(11, 2_000_000)),
                listOf(candidate(20, 20_000), candidate(21, 2_020_000))
            ),
            targetTimeUs = 500_000,
            previousStartUs = 0,
            toleranceUs = 100_000,
            maximumLeadUs = 750_000
        )

        assertNull(selected)
    }

    @Test
    fun refusesFeedsWithoutACommonTimestampWithinTolerance() {
        val selected = ArchiveDashSegmentSelector.selectAligned(
            candidateWindows = listOf(
                listOf(candidate(10, 0), candidate(11, 1_000_000)),
                listOf(candidate(20, 300_000), candidate(21, 1_300_000))
            ),
            targetTimeUs = 0,
            previousStartUs = null,
            toleranceUs = 100_000
        )

        assertNull(selected)
    }

    @Test
    fun continuingCaptureNeverSkipsAnUnmatchedFragmentToReachALaterMatch() {
        val first = listOf(candidate(10, 0), candidate(11, 1_000_000), candidate(12, 2_000_000))
        val second = listOf(candidate(20, 0), candidate(22, 2_000_000))
        assertNull(ArchiveDashSegmentSelector.selectAligned(
            listOf(first, second), targetTimeUs = 2_000_000,
            previousStartUs = 0, toleranceUs = 100_000, maximumLeadUs = 2_000_000
        ))
    }

    @Test
    fun initialArchiveSelectionMayUseAnOlderCompleteWindow() {
        val first = listOf(candidate(10, 0), candidate(11, 1_000_000), candidate(12, 2_000_000))
        val second = listOf(candidate(20, 0), candidate(22, 2_000_000))
        val result = ArchiveDashSegmentSelector.selectAligned(
            listOf(first, second), targetTimeUs = 1_100_000,
            previousStartUs = null, toleranceUs = 100_000
        )
        assertEquals(listOf(10L, 20L), result?.map { it.number })
    }

    @Test
    fun continuingSelectionUsesChronologicallyNextCandidateEvenWhenListIsUnsorted() {
        val first = listOf(candidate(13, 3_000_000), candidate(11, 1_000_000),
            candidate(12, 2_000_000), candidate(10, 0))
        val second = listOf(candidate(23, 3_000_000), candidate(21, 1_000_000),
            candidate(22, 2_000_000), candidate(20, 0))
        val picked = ArchiveDashSegmentSelector.selectAligned(
            listOf(first, second), targetTimeUs = 1_100_000, previousStartUs = 0,
            toleranceUs = 10, maximumLeadUs = 2_000_000
        )
        assertEquals(listOf(11L, 21L), picked?.map { it.number })
    }

    @Test
    fun continuationCannotSkipUnmatchedNextCandidateEvenIfLaterOneMatches() {
        val first = listOf(candidate(13, 3_000_000), candidate(11, 1_000_000),
            candidate(12, 2_000_000), candidate(10, 0))
        val second = listOf(candidate(23, 3_000_000), candidate(22, 2_000_000),
            candidate(20, 0))
        assertNull(ArchiveDashSegmentSelector.selectAligned(
            listOf(first, second), targetTimeUs = 2_000_000, previousStartUs = 0,
            toleranceUs = 10, maximumLeadUs = 2_000_000
        ))
    }

    private fun candidate(number: Long, absoluteStartUs: Long) =
        ArchiveDashSegmentSelector.Candidate(number, absoluteStartUs)
}
