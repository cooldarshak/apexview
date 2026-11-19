package app.apexview.cast

import java.net.InetAddress
import java.util.Locale

/** Rolling HLS publication for the clear, baked-view encoder. Runs on its producer thread. */
internal class BakedCastHlsSession(address: String, peer: InetAddress?) : AutoCloseable {
    private data class Segment(val sequence: Long, val durationUs: Long, val bytes: ByteArray) {
        val name get() = "view-$sequence.ts"
    }

    private val relay = ComposedCmafRelay(address, peer, liveHls = true)
    private val muxer = BakedCastTransportStream()
    private val window = ArrayDeque<Segment>()
    private var nextSequence = 0L
    private var ended = false
    private val targetDuration = 4L
    val url get() = relay.url

    fun append(samples: List<BakedCastTransportStream.Sample>, durationUs: Long) {
        check(!ended)
        require(durationUs in 1..4_000_000L) { "Keep baked Cast segments at or below four seconds" }
        val packed = muxer.segment(samples)
        // Keep retired objects available for in-flight fetches, outside the advertised playlist.
        window.addLast(Segment(nextSequence++, durationUs, packed))
        if (window.size > 8) window.removeFirst()
        publish()
    }

    fun end() {
        if (!ended && window.isNotEmpty()) {
            ended = true
            publish()
        }
    }

    private fun publish() {
        val active = window.takeLast(5)
        val playlist = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$targetDuration\n")
            append("#EXT-X-MEDIA-SEQUENCE:${active.first().sequence}\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            for (segment in active) {
                val seconds = String.format(Locale.US, "%.6f", segment.durationUs / 1_000_000.0)
                append("#EXTINF:$seconds,\n${segment.name}\n")
            }
            if (ended) append("#EXT-X-ENDLIST\n")
        }
        relay.publishResources(window.associate { it.name to it.bytes.copyOf() } +
            ("live.m3u8" to playlist.toByteArray()))
    }

    override fun close() {
        ended = true
        relay.close()
        window.clear()
    }
}
