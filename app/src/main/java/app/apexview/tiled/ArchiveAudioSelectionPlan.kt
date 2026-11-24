package app.apexview.tiled

/** Stable merge is opt-in only for independently validated, finite, separate audio
 * renditions with the same known clock and duration. Legacy unverified/muxed
 * audio remains available through its existing native source path. */
internal object ArchiveAudioSelectionPlan {
    fun sameTimeline(audioStartUs: Long?, videoStartUs: Long?,
                     audioDurationUs: Long, videoDurationUs: Long): Boolean =
        audioStartUs != null && videoStartUs != null &&
            audioStartUs == videoStartUs && audioDurationUs > 0L &&
            audioDurationUs == videoDurationUs

    /** Media3 MergingMediaSource reserves child 0 for video and prefixes track IDs
     * with the original child index. Duplicate URIs must not become extra loaders. */
    fun <T> children(primary: T, verified: Collection<T>): List<T> =
        if (primary !in verified) listOf(primary)
        else listOf(primary) + verified.filter { it != primary }.distinct()

    fun childIndex(groupId: String): Int? =
        if (':' in groupId) groupId.substringBefore(':').toIntOrNull()?.takeIf { it > 0 }
        else null
}
