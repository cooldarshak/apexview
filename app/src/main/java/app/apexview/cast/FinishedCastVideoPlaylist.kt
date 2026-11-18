package app.apexview.cast

import java.util.Locale

/** A bounded HLS window for the continuously encoded clear scene. */
internal class FinishedCastVideoPlaylist {
    private data class Segment(val sequence: Long, val durationUs: Long, val bytes: ByteArray)

    private val segments = ArrayDeque<Segment>()
    private var initialization: ByteArray? = null
    private var nextSequence = 1L
    private var targetDurationSeconds = 2L
    private var retainedBytes = 0L
    private var ended = false

    @Synchronized
    fun initialize(bytes: ByteArray) {
        check(!ended && initialization == null)
        require(bytes.isNotEmpty())
        initialization = bytes
    }

    @Synchronized
    fun publish(sequence: Long, durationUs: Long, bytes: ByteArray) {
        check(!ended && initialization != null)
        require(sequence == nextSequence && durationUs > 0)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        nextSequence++
        val seconds = (durationUs - 1) / 1_000_000L + 1
        targetDurationSeconds = maxOf(targetDurationSeconds, seconds)
        segments.addLast(Segment(sequence, durationUs, bytes))
        retainedBytes += bytes.size
        while (segments.size > MAX_SEGMENTS || retainedBytes > MAX_BYTES) {
            retainedBytes -= segments.removeFirst().bytes.size
        }
    }

    @Synchronized
    fun finish() {
        ended = true
    }

    @Synchronized
    fun resource(name: String): ByteArray? = when (name) {
        "init.mp4" -> initialization
        "video.m3u8" -> manifest()
        else -> name.takeIf { it.startsWith("s-") && it.endsWith(".m4s") }
            ?.removePrefix("s-")?.removeSuffix(".m4s")?.toLongOrNull()
            ?.let { number -> segments.firstOrNull { it.sequence == number }?.bytes }
    }

    private fun manifest(): ByteArray? {
        val first = segments.firstOrNull() ?: return null
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            append("#EXT-X-TARGETDURATION:").append(targetDurationSeconds).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(first.sequence).append('\n')
            append("#EXT-X-MAP:URI=\"init.mp4\"\n")
            for (segment in segments) {
                append("#EXTINF:")
                append(String.format(Locale.US, "%.6f", segment.durationUs / 1_000_000.0))
                append(",\ns-").append(segment.sequence).append(".m4s\n")
            }
            if (ended) append("#EXT-X-ENDLIST\n")
        }.toByteArray()
    }

    private companion object {
        const val MAX_SEGMENTS = 10
        const val MAX_BYTES = 24 * 1024 * 1024L
    }
}
