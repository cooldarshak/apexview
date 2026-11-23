package app.apexview.tiled

import kotlin.math.abs

/**
 * Picks time-aligned VOD segments without jumping to the end of a finite DASH presentation.
 * After the first segment, only the next segment after [previousStartUs] is eligible. The
 * caller's maximum lead bounds prefetch. Startup always selects a segment at/before the clock.
 */
internal object ArchiveDashSegmentSelector {
    data class Candidate(val number: Long, val absoluteStartUs: Long)

    fun selectAligned(
        candidateWindows: List<List<Candidate>>,
        targetTimeUs: Long,
        previousStartUs: Long?,
        toleranceUs: Long,
        maximumLeadUs: Long = 750_000L
    ): List<Candidate>? {
        if (candidateWindows.size < 2 || candidateWindows.any { it.isEmpty() }) return null
        require(toleranceUs >= 0L && maximumLeadUs >= 0L)

        val latestAllowedStartUs = if (previousStartUs == null) targetTimeUs
            else Math.addExact(targetTimeUs, maximumLeadUs)
        val masterCandidates = candidateWindows.first().asSequence()
            .filter { it.absoluteStartUs <= latestAllowedStartUs }
            .filter { previousStartUs == null || it.absoluteStartUs > previousStartUs }

        // Steady-state capture only needs the next chronological master fragment.
        // Do not allocate and sort the entire archive candidate list every time
        // one six-second CMAF segment is requested, especially at 8 feeds/1.5x.
        // Startup/seek still orders earlier fallback windows by clock proximity.
        val eligible = if (previousStartUs == null) {
            masterCandidates.sortedBy { abs(it.absoluteStartUs - targetTimeUs) }
        } else {
            listOfNotNull(masterCandidates.minByOrNull { it.absoluteStartUs }).asSequence()
        }
        // Continuing across an unmatched fragment would skip HEVC references.
        for (master in eligible) {
            val matches = candidateWindows.drop(1).map { window ->
                window.minByOrNull { abs(it.absoluteStartUs - master.absoluteStartUs) }
            }
            if (matches.all {
                    it != null && abs(it.absoluteStartUs - master.absoluteStartUs) <= toleranceUs
                }) {
                return listOf(master) + matches.filterNotNull()
            }
        }
        return null
    }
}
