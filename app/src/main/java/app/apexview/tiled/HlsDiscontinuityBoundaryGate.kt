package app.apexview.tiled

/**
 * HLS discontinuity sequences are scoped to individual media playlists, not shared
 * identifiers across camera feeds. Enforce synchronized period *transitions*
 * without assuming that two renditions started numbering at the same value.
 *
 * Only call commit after independently validating and publishing the selected
 * CMAF segment. POC, initialization, PTS and protection checks are not relaxed.
 */
internal class HlsDiscontinuityBoundaryGate {
    private var previousLabels: List<String>? = null
    private var previousSequences: List<Int>? = null
    private var previousStartUs: Long? = null

    fun accepts(labels: List<String>, sequences: List<Int>, startUs: Long): Boolean {
        require(labels.size >= 2 && labels.distinct().size == labels.size && labels.size == sequences.size)
        val oldLabels = previousLabels ?: return true
        val oldSequences = previousSequences ?: return true
        val oldStart = previousStartUs ?: return true
        if (oldLabels != labels) return false
        // A new playhead after a seek is validated by the ordinary CMAF gate,
        // not by comparing a new period to a stale capture's local numbering.
        if (startUs < oldStart || startUs - oldStart > 30_000_000L) return true
        val changes = sequences.indices.map { sequences[it] - oldSequences[it] }
        return changes.all { it >= 0 } && changes.distinct().size == 1
    }

    fun commit(labels: List<String>, sequences: List<Int>, startUs: Long) {
        require(accepts(labels, sequences, startUs)) { "Unsynchronized HLS period transition" }
        previousLabels = labels.toList()
        previousSequences = sequences.toList()
        previousStartUs = startUs
    }

    fun reset() {
        previousLabels = null
        previousSequences = null
        previousStartUs = null
    }
}
