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
import app.apexview.tiled.NativeHotPath
import java.nio.ByteBuffer

/** Selects the already-authorized, CLEAR archive commentary; never captures microphone audio. */
internal class BakedCastArchiveAudio(
    private val reader: CmafHttpReader,
    private val playlistUri: Uri,
    private val startPositionUs: Long,
    private val nativeRequested: () -> Boolean = { false },
) : AutoCloseable {
    private var init = ByteArray(0)
    private var initKey = ""
    private val progress = BakedAudioReadProgress(startPositionUs)
    private var closed = false
    private var completedPlaylist: HlsMediaPlaylist? = null
    private var sampleBuffer: ByteBuffer? = null
    private var requestedBefore: Boolean? = null
    private var actualNative: Boolean? = null
    private fun wipe(buffer: ByteBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(0.toByte())
        buffer.clear()
    }
    private fun bufferFor(requested: Boolean): ByteBuffer {
        if (requestedBefore != requested || sampleBuffer == null) {
            sampleBuffer?.let(::wipe)
            sampleBuffer = if (requested) ByteBuffer.allocateDirect(32 * 1024)
                else ByteBuffer.allocate(32 * 1024)
            requestedBefore = requested
            actualNative = null
        }
        return requireNotNull(sampleBuffer)
    }

    /** Fetch on IO, not the GL/PixelCopy thread. PTS shares the live scene's zero-based clock. */
    fun readUntil(positionUs: Long, endLimitUs: Long = Long.MAX_VALUE): List<BakedCastTransportStream.Sample> {
        check(!closed)
        val media = completedPlaylist ?: readPlaylist(playlistUri).also { if (it.hasEndTag) completedPlaylist = it }
        return progress.transaction { output ->
            val first = firstUnloadedAudioSegment(media.segments.size, progress.lastLoadedSegmentStartUs) {
                media.segments[it].relativeStartTimeUs
            }
            for (index in first until media.segments.size) {
                val segment = media.segments[index]
                val start = segment.relativeStartTimeUs
                if (start >= positionUs) break
                val end = Math.addExact(start, segment.durationUs)
                if (end <= progress.nextAudioUs) continue
                // Do not partially consume a fragment: retain the exact audio
                // cursor until the publisher has freed enough media-time room.
                if (end > endLimitUs) {
                    require(segment.durationUs <= 15_000_000L) { "Commentary segment exceeds Cast media window" }
                    break
                }
                require(segment.fullSegmentEncryptionKeyUri == null && segment.drmInitData == null) {
                    "Protected F1 commentary cannot be encoded into a clear Cast stream"
                }
                val map = requireNotNull(segment.initializationSegment) { "Commentary must use CMAF/AAC" }
                val key = "${media.baseUri}|${map.url}|${map.byteRangeOffset}|${map.byteRangeLength}"
                if (initKey != key) {
                    require(initKey.isEmpty()) { "Commentary codec changed; reconnect Cast" }
                    val bytes = reader.read(UriUtil.resolveToUri(media.baseUri, map.url), 2 * 1024 * 1024,
                        map.byteRangeOffset, map.byteRangeLength).bytes
                    var transferred = false
                    try {
                        val report = CmafBoxInspector.inspect(bytes)
                        require(report.errors.isEmpty() && report.tracks.size == 1 &&
                            report.tracks.single().handler == "soun" && !report.tracks.single().encrypted) {
                            "Only unencrypted, single-track AAC commentary may be cast"
                        }
                        init = bytes; initKey = key; transferred = true
                    } finally { if (!transferred) bytes.fill(0) }
                }
                val bytes = reader.read(UriUtil.resolveToUri(media.baseUri, segment.url), 8 * 1024 * 1024,
                    segment.byteRangeOffset, segment.byteRangeLength).bytes
                try {
                    // Queue this whole CMAF fragment. Avoid downloading the same six-second
                    // fragment every time the live encoder asks for another few frames.
                    output += decodeSamples(init, bytes, start)
                    progress.lastLoadedSegmentStartUs = start
                }
                finally { bytes.fill(0) }
            }
        }
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

    private fun decodeSamples(initialization: ByteArray, bytes: ByteArray, segmentStartUs: Long): List<BakedCastTransportStream.Sample> {
        val extractor = MediaExtractor()
        val samples = mutableListOf<BakedCastTransportStream.Sample>()
        try {
            extractor.setDataSource(object : MediaDataSource() {
                override fun getSize() = initialization.size.toLong() + bytes.size
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                    readJoinedAudio(initialization, bytes, position, buffer, offset, size)
                override fun close() = Unit
            })
            val index = (0 until extractor.trackCount).singleOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            } ?: throw IllegalArgumentException("Commentary must contain exactly one AAC track")
            val format = extractor.getTrackFormat(index)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(index)
            val requested = nativeRequested()
            val buffer = bufferFor(requested)
            var firstSampleTimeUs = -1L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timestamp = extractor.sampleTime
                if (firstSampleTimeUs < 0L) firstSampleTimeUs = timestamp
                val absoluteUs = segmentStartUs + (timestamp - firstSampleTimeUs)
                if (absoluteUs >= progress.nextAudioUs && absoluteUs >= startPositionUs) {
                    val packet = BakedAacAdts.wrap(buffer, size, rate, channels, requested && actualNative != false) { native ->
                        if (actualNative != native) {
                            actualNative = native
                            android.util.Log.i("PitwallNativeHotPath", "AUDIO_FRAMING_ENGINE requested=" + (if (requested) "RUST" else "JVM") +
                                " actual=" + (if (native) "RUST reason=ADTS_WRITTEN" else if (requested) "JVM reason=NATIVE_BATCH_UNAVAILABLE" else "JVM reason=USER_DISABLED"))
                        }
                    }
                    samples += BakedCastTransportStream.Sample(absoluteUs - startPositionUs, packet, video = false)
                }
                if (!extractor.advance()) break
            }
            samples.lastOrNull()?.let { progress.nextAudioUs = it.ptsUs + startPositionUs + 1 }
        } catch (failure: Throwable) {
            samples.forEach { it.bytes.fill(0) }
            throw failure
        } finally { extractor.release() }
        return samples
    }

    override fun close() {
        closed = true
        init.fill(0); init = ByteArray(0)
        completedPlaylist = null
        sampleBuffer?.let(::wipe)
        sampleBuffer = null
    }
}

/** Expose the two immutable CMAF extents as one virtual extractor stream;
 * do not concatenate the initialization and media fragment for each read.
 */
internal fun readJoinedAudio(init: ByteArray, media: ByteArray, position: Long,
    target: ByteArray, offset: Int, size: Int): Int {
    val total = init.size.toLong() + media.size
    if (position < 0 || position >= total) return -1
    require(offset >= 0 && size >= 0 && offset <= target.size && size <= target.size - offset)
    val count = minOf(size.toLong(), total - position).toInt()
    val first = if (position < init.size) minOf(count, init.size - position.toInt()) else 0
    if (first > 0) System.arraycopy(init, position.toInt(), target, offset, first)
    val second = count - first
    if (second > 0) System.arraycopy(media, (position + first - init.size).toInt(),
        target, offset + first, second)
    return count
}

internal object BakedAacAdts {
    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000, 7350)

    private fun validate(size: Int, sampleRate: Int, channels: Int): Int {
        val rate = rates.indexOf(sampleRate)
        require(rate >= 0 && channels in 1..7 && size in 1..8184) {
            "Unsupported AAC format for Cast"
        }
        return rate
    }

    private fun writeHeader(out: ByteArray, rate: Int, channels: Int) {
        val length = out.size
        out[0] = 0xff.toByte(); out[1] = 0xf1.toByte()
        out[2] = (((2 - 1) shl 6) or (rate shl 2) or (channels ushr 2)).toByte()
        out[3] = (((channels and 3) shl 6) or (length ushr 11)).toByte()
        out[4] = ((length ushr 3) and 255).toByte()
        out[5] = (((length and 7) shl 5) or 31).toByte()
        out[6] = 0xfc.toByte()
    }

    fun wrap(rawAac: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val rate = validate(rawAac.size, sampleRate, channels)
        return ByteArray(rawAac.size + 7).also { out ->
            writeHeader(out, rate, channels)
            rawAac.copyInto(out, 7)
        }
    }

    /** One final owned sample allocation in either engine. Native writes the
     * header and payload directly to it; no raw intermediate ByteArray.
     */
    fun wrap(buffer: ByteBuffer, size: Int, sampleRate: Int, channels: Int,
        nativeRequested: Boolean, report: (Boolean) -> Unit): ByteArray {
        val rate = validate(size, sampleRate, channels)
        require(size <= buffer.capacity())
        val out = ByteArray(size + 7)
        val native = nativeRequested && buffer.isDirect &&
            NativeHotPath.adts(buffer, size, sampleRate, channels, out)
        if (!native) {
            writeHeader(out, rate, channels)
            buffer.position(0)
            buffer.get(out, 7, size)
        }
        report(native)
        return out
    }
}
