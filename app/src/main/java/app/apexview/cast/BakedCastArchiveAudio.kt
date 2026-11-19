package app.apexview.cast

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import app.apexview.tiled.CmafBoxInspector
import app.apexview.tiled.CmafHttpReader
import java.nio.ByteBuffer

/** Selects the already-authorized, CLEAR archive commentary; never captures microphone audio. */
internal class BakedCastArchiveAudio(
    private val reader: CmafHttpReader,
    private val playlistUri: Uri,
    private val startPositionUs: Long,
) : AutoCloseable {
    private var init = ByteArray(0)
    private var initKey = ""
    private var nextAudioUs = startPositionUs
    private var lastLoadedSegmentStartUs = Long.MIN_VALUE
    private var closed = false

    /** Fetch on IO, not the GL/PixelCopy thread. PTS shares the live scene's zero-based clock. */
    fun readUntil(positionUs: Long): List<BakedCastTransportStream.Sample> {
        check(!closed)
        val media = readPlaylist(playlistUri)
        val output = mutableListOf<BakedCastTransportStream.Sample>()
        for (segment in media.segments) {
            val start = segment.relativeStartTimeUs
            if (start <= lastLoadedSegmentStartUs || start + segment.durationUs <= nextAudioUs || start >= positionUs) continue
            require(segment.fullSegmentEncryptionKeyUri == null && segment.drmInitData == null) {
                "Protected F1 commentary cannot be encoded into a clear Cast stream"
            }
            val map = requireNotNull(segment.initializationSegment) { "Commentary must use CMAF/AAC" }
            val key = "${media.baseUri}|${map.url}|${map.byteRangeOffset}|${map.byteRangeLength}"
            if (initKey != key) {
                require(initKey.isEmpty()) { "Commentary codec changed; reconnect Cast" }
                val bytes = reader.read(UriUtil.resolveToUri(media.baseUri, map.url), 2 * 1024 * 1024,
                    map.byteRangeOffset, map.byteRangeLength).bytes
                try {
                    val report = CmafBoxInspector.inspect(bytes)
                    require(report.errors.isEmpty() && report.tracks.size == 1 &&
                        report.tracks.single().handler == "soun" && !report.tracks.single().encrypted) {
                        "Only unencrypted, single-track AAC commentary may be cast"
                    }
                    init = bytes.copyOf(); initKey = key
                } finally { bytes.fill(0) }
            }
            val bytes = reader.read(UriUtil.resolveToUri(media.baseUri, segment.url), 8 * 1024 * 1024,
                segment.byteRangeOffset, segment.byteRangeLength).bytes
            try {
                // Queue this whole CMAF fragment. Avoid downloading the same six-second
                // fragment every time the live encoder asks for another few frames.
                output += decodeSamples(init + bytes, start)
                lastLoadedSegmentStartUs = start
            }
            finally { bytes.fill(0) }
        }
        return output
    }

    private fun readPlaylist(uri: Uri): HlsMediaPlaylist {
        val response = reader.read(uri, 2 * 1024 * 1024)
        try {
            val playlist = HlsPlaylistParser().parse(response.uri, response.bytes.inputStream())
            return when (playlist) {
                is HlsMediaPlaylist -> playlist
                is HlsMultivariantPlaylist -> {
                    val audio = playlist.audios.firstOrNull { it.url != null }?.url
                        ?: throw IllegalArgumentException("No separate audio rendition")
                    readPlaylist(audio)
                }
                else -> throw IllegalArgumentException("Unsupported commentary playlist")
            }
        } finally { response.bytes.fill(0) }
    }

    private fun decodeSamples(bytes: ByteArray, segmentStartUs: Long): List<BakedCastTransportStream.Sample> {
        val extractor = MediaExtractor()
        val samples = mutableListOf<BakedCastTransportStream.Sample>()
        try {
            extractor.setDataSource(object : MediaDataSource() {
                override fun getSize() = bytes.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position < 0 || position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    System.arraycopy(bytes, position.toInt(), buffer, offset, count)
                    return count
                }
                override fun close() = Unit
            })
            val index = (0 until extractor.trackCount).singleOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            } ?: throw IllegalArgumentException("Commentary must contain exactly one AAC track")
            val format = extractor.getTrackFormat(index)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(index)
            val buffer = ByteBuffer.allocate(32 * 1024)
            var firstSampleTimeUs = -1L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timestamp = extractor.sampleTime
                if (firstSampleTimeUs < 0L) firstSampleTimeUs = timestamp
                val absoluteUs = segmentStartUs + (timestamp - firstSampleTimeUs)
                if (absoluteUs >= nextAudioUs && absoluteUs >= startPositionUs) {
                    val aac = ByteArray(size)
                    buffer.position(0); buffer.get(aac)
                    samples += BakedCastTransportStream.Sample(absoluteUs - startPositionUs,
                        BakedAacAdts.wrap(aac, rate, channels), video = false)
                }
                if (!extractor.advance()) break
            }
            samples.lastOrNull()?.let { nextAudioUs = it.ptsUs + startPositionUs + 1 }
        } finally { extractor.release(); bytes.fill(0) }
        return samples
    }

    override fun close() {
        closed = true
        init.fill(0); init = ByteArray(0)
    }
}

internal object BakedAacAdts {
    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000, 7350)

    fun wrap(rawAac: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val rate = rates.indexOf(sampleRate)
        require(rate >= 0 && channels in 1..7 && rawAac.size in 1..8184) {
            "Unsupported AAC format for Cast"
        }
        val length = rawAac.size + 7
        val header = byteArrayOf(0xff.toByte(), 0xf1.toByte(),
            (((2 - 1) shl 6) or (rate shl 2) or (channels ushr 2)).toByte(),
            (((channels and 3) shl 6) or (length ushr 11)).toByte(),
            ((length ushr 3) and 255).toByte(), (((length and 7) shl 5) or 31).toByte(), 0xfc.toByte())
        return header + rawAac
    }
}
