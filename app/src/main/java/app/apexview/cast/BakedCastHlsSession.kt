package app.apexview.cast

import java.net.InetAddress
import java.util.Locale

/** Rolling HLS publication for the clear, baked-view encoder. Runs on its producer thread. */
internal class BakedCastHlsSession(address: String, peer: InetAddress?,
    tls: app.apexview.cast.local.PitwallTls.Session? = null) : AutoCloseable {
    private data class Segment(val sequence: Long, val durationUs: Long, val bytes: ByteArray) {
        val name get() = "view-$sequence.ts"
    }

    private val relay = ComposedCmafRelay(address, peer, liveHls = true, tls = tls)
    private val muxer = BakedCastTransportStream()
    private val window = ArrayDeque<Segment>()
    private var nextSequence = 0L
    private var ended = false
    private val targetDuration = 4L
    private var retainedUs = 0L
    private var activeUs = 0L
    val advertisedDurationUs get() = activeUs
    val url get() = relay.url

    fun append(samples: List<BakedCastTransportStream.Sample>, durationUs: Long) {
        check(!ended)
        require(durationUs in 800_000L..4_000_000L) { "Keep baked Cast segments at or below four seconds" }
        val packed = muxer.segment(samples)
        // Keep retired objects available for in-flight fetches, outside the advertised playlist.
        window.addLast(Segment(nextSequence++, durationUs, packed))
        retainedUs += durationUs
        // Keep at least three target durations advertised, plus retired media for slow clients.
        while (retainedUs - window.first().durationUs >= 28_000_000L) {
            retainedUs -= window.removeFirst().durationUs
        }
        publish()
    }

    fun end() {
        if (!ended && window.isNotEmpty()) {
            ended = true
            publish()
        }
    }

    private fun publish() {
        var duration = 0L
        val active = window.asReversed().takeWhile {
            if (duration >= 12_000_000L) false else {
                duration += it.durationUs
                true
            }
        }.asReversed()
        activeUs = duration
        val playlist = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$targetDuration\n")
            append("#EXT-X-MEDIA-SEQUENCE:${active.first().sequence}\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            for (segment in active) {
                val seconds = String.format(Locale.US, "%.6f", segment.durationUs / 1_000_000.0)
                append("#EXTINF:$seconds,\n${segment.name}\n")
            }
            if (ended) append("#EXT-X-ENDLIST\n")
        }
        // Immutable transport segments can remain owned by the relay while
        // present in the rolling playlist. Avoid copying every retained
        // multi-megabyte segment on each refresh and let the relay zero only
        // retired resources after their final immutable socket read lease closes.
        relay.publishResources(window.associate { it.name to it.bytes } +
            ("live.m3u8" to playlist.toByteArray()))
    }

    override fun close() {
        ended = true
        relay.close()
        window.clear()
    }
}
