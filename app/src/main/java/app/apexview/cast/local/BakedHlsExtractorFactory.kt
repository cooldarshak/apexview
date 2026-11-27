package app.apexview.cast.local

import android.net.Uri
import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.H264Reader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Only the authenticated Pitwall baked AVC/AAC route uses this adapter.
 * Each locally muxed TS segment ends at a complete access-unit boundary.
 * Media3 1.11.1's HLS EOF check calls parseHeader() on its PES scratch after
 * that scratch has been reused for PTS. It can reject a valid segment end
 * and leave the final picture pending. Keep Media3's actual parser, hide
 * its generic EOF heuristic for our two PES readers, and issue the public
 * empty-PUSI completion once at a verified complete segment boundary.
 *
 * Our writer guarantees one complete AVC access unit per video PES.
 * Generic slice/AUD sample detection is not a second source of truth:
 * its synthetic EOF delimiter and retained slice state can emit extra
 * non-picture samples across reused HLS segments.
 * Never install this factory for arbitrary HLS, protected video or captions.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class BakedHlsExtractorFactory : HlsExtractorFactory {
    override fun createExtractor(uri: Uri, format: Format, muxedCaptionFormats: MutableList<Format>?,
        timestampAdjuster: TimestampAdjuster, responseHeaders: MutableMap<String, MutableList<String>>,
        sniffingExtractorInput: ExtractorInput, playerId: PlayerId): HlsMediaChunkExtractor {
        require(uri.lastPathSegment?.matches(Regex("view-[0-9]+\\.ts")) == true) {
            "Unexpected baked HLS segment"
        }
        return Chunk(timestampAdjuster)
    }

    internal class Chunk(timestampAdjuster: TimestampAdjuster) : HlsMediaChunkExtractor {
        private class CompletePes(private val reader: PesReader) : TsPayloadReader by reader {
            private var consumed = false
            override fun consume(data: ParsableByteArray, flags: Int) {
                if (data.bytesLeft() > 0) {
                    reader.consume(data, flags)
                    consumed = true
                }
            }
            override fun seek() {
                consumed = false
                reader.seek()
            }
            fun finishSegment() {
                if (!consumed) return
                reader.consume(ParsableByteArray(), TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR)
                consumed = false
            }
        }

        /** Media3 parses format and transfers original elementary bytes.
         * Only the owned one-picture/PES transport uses exact PES completion
         * for metadata. No copied video buffer or synthetic payload bytes. */
        private class PicturePes : ElementaryStreamReader {
            private val formatReader = H264Reader(
                SeiReader(emptyList(), MimeTypes.VIDEO_MP2T),
                false, false, MimeTypes.VIDEO_MP2T)
            private var output: TrackOutput? = null
            private var formatReady = false
            private var timeUs = C.TIME_UNSET
            private var flags = 0
            private var bytes = 0
            private var started = false
            private var firstPicture = true

            override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
                formatReader.createTracks(object : ExtractorOutput by extractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput {
                        val track = extractorOutput.track(id, type)
                        if (type != C.TRACK_TYPE_VIDEO) return track
                        output = track
                        return object : TrackOutput by track {
                            override fun format(format: Format) {
                                track.format(format)
                                formatReady = true
                            }
                            // Inferred metadata is not authoritative for this mux.
                            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int,
                                cryptoData: TrackOutput.CryptoData?) = Unit
                        }
                    }
                }, idGenerator)
            }

            override fun packetStarted(pesTimeUs: Long, flags: Int) {
                check(!started && bytes == 0 && pesTimeUs != C.TIME_UNSET) { "Invalid baked AVC PES start" }
                timeUs = pesTimeUs
                this.flags = if (flags and TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR != 0)
                    C.BUFFER_FLAG_KEY_FRAME else 0
                check(!firstPicture || this.flags != 0) { "Baked segment must start at random access" }
                started = true
                formatReader.packetStarted(pesTimeUs, flags)
            }

            override fun consume(data: ParsableByteArray) {
                check(started) { "Baked AVC data without PES start" }
                bytes = Math.addExact(bytes, data.bytesLeft())
                check(bytes <= 48 * 1024 * 1024) { "Baked AVC access unit exceeds bound" }
                formatReader.consume(data)
            }

            override fun packetFinished() {
                check(started && bytes > 0 && formatReady) { "Incomplete baked AVC access unit or missing format" }
                formatReader.packetFinished()
                checkNotNull(output).sampleMetadata(timeUs, flags, bytes, 0, null)
                bytes = 0
                started = false
                firstPicture = false
            }

            override fun endOfInputReached() {
                check(!started && bytes == 0) { "Unfinished baked AVC PES at segment end" }
                formatReader.endOfInputReached()
                // Retain parsed format/track, not stale delimiter/slice state.
                formatReader.seek()
                firstPicture = true
            }

            override fun seek() {
                formatReader.seek()
                bytes = 0
                started = false
                firstPicture = true
                timeUs = C.TIME_UNSET
                flags = 0
            }
        }

        private val readers = mutableListOf<CompletePes>()
        private val defaults = DefaultTsPayloadReaderFactory(
            DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM,
            emptyList())
        private val payloads = object : TsPayloadReader.Factory {
            override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = SparseArray()
            override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
                if (streamType != TsExtractor.TS_STREAM_TYPE_H264 &&
                    streamType != TsExtractor.TS_STREAM_TYPE_AAC_ADTS) return null
                val reader = if (streamType == TsExtractor.TS_STREAM_TYPE_H264) PesReader(PicturePes())
                    else defaults.createPayloadReader(streamType, esInfo) as? PesReader ?: return null
                return CompletePes(reader).also { readers.add(it) }
            }
        }
        @Suppress("DEPRECATION")
        private val extractor = TsExtractor(TsExtractor.MODE_HLS, timestampAdjuster, payloads)
        private val position = PositionHolder()
        private var input: ExtractorInput? = null
        private var firstPosition = 0L

        override fun init(extractorOutput: ExtractorOutput) { extractor.init(extractorOutput) }

        override fun read(extractorInput: ExtractorInput): Boolean {
            if (input !== extractorInput) {
                input = extractorInput
                firstPosition = extractorInput.position
            }
            return when (extractor.read(extractorInput, position)) {
                Extractor.RESULT_CONTINUE -> true
                Extractor.RESULT_END_OF_INPUT -> {
                    // Baked relay returns an exact Content-Length, and
                    // segments consist only of complete 188-byte TS packets.
                    // On a retry HlsMediaChunk starts at a nonzero byte offset;
                    // never compare its absolute position to a remaining length.
                    val length = extractorInput.length
                    if (extractorInput.position % 188L != 0L ||
                        (firstPosition == 0L && length >= 0L && extractorInput.position != length)) {
                        throw java.io.EOFException("Incomplete baked TS segment")
                    }
                    readers.forEach { it.finishSegment() }
                    input = null
                    false
                }
                else -> throw java.io.IOException("Unexpected baked HLS extractor seek")
            }
        }
        override fun isPackedAudioExtractor() = false
        override fun isReusable() = true
        override fun recreate(): HlsMediaChunkExtractor = throw IllegalStateException("Reusable baked TS extractor")
        override fun onTruncatedSegmentParsed() {
            throw IllegalStateException("Baked TS segments must not be truncated")
        }
    }
}
