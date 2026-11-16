package app.apexview.tiled

import kotlin.math.abs

/**
 * Picks time-aligned VOD segments without jumping to the end of a finite DASH presentation.
 * After the first segment, only the next segment after [previousStartUs] is eligible. The
 * maximum lead keeps capture close to the archive player's clock instead of downloading ahead.
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

        val latestAllowedStartUs = Math.addExact(targetTimeUs, maximumLeadUs)
        val masterCandidates = candidateWindows.first()
            .asSequence()
            .filter { it.absoluteStartUs <= latestAllowedStartUs }
            .filter { previousStartUs == null || it.absoluteStartUs > previousStartUs }
            .let { candidates ->
                if (previousStartUs == null) {
                    candidates.sortedBy { abs(it.absoluteStartUs - targetTimeUs) }
                } else {
                    candidates.sortedBy { it.absoluteStartUs }
                }
            }

        for (master in masterCandidates) {
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
