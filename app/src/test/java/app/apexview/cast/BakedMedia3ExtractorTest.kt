package app.apexview.cast

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Media3 1.11.1 HLS-mode extractor, not only FFmpeg or TS byte parity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BakedMedia3ExtractorTest {
    @Test fun audFreeRustTransportProducesVideoAndAudioSamplesInHlsMode() {
        val path = System.getenv("PITWALL_BAKED_TS_FIXTURE_DIR")
        assumeTrue(!path.isNullOrBlank())
        val dir = File(requireNotNull(path), "no-aud")
        assertTrue("AUD-free Rust fixture missing", dir.resolve("segment-0.ts").isFile)
        val formats = mutableMapOf<Int, Format>()
        val timestamps = mutableMapOf<Int, MutableList<Long>>()
        val tracks = mutableMapOf<Int, TrackOutput>()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
                object : TrackOutput by DiscardingTrackOutput() {
                    override fun format(format: Format) { formats[type] = format }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int,
                                                cryptoData: TrackOutput.CryptoData?) {
                        if (size > 0) timestamps.getOrPut(type) { mutableListOf() }.add(timeUs)
                    }
                }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        @Suppress("DEPRECATION")
        val extractor = TsExtractor(TsExtractor.MODE_HLS, TimestampAdjuster(0),
            DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS))
        extractor.init(output)
        try {
            repeat(2) { segment ->
                val bytes = dir.resolve("segment-$segment.ts").readBytes()
                var at = 0
                val reader = DataReader { target, offset, length ->
                    if (at == bytes.size) C.RESULT_END_OF_INPUT else {
                        val n = minOf(length, bytes.size-at)
                        bytes.copyInto(target, offset, at, at+n)
                        at += n
                        n
                    }
                }
                val input = DefaultExtractorInput(reader, 0L, bytes.size.toLong())
                val position = PositionHolder()
                while (true) {
                    val result = extractor.read(input, position)
                    if (result == Extractor.RESULT_END_OF_INPUT) break
                    assertEquals(Extractor.RESULT_CONTINUE, result)
                }
            }
            assertEquals("video/avc", formats[C.TRACK_TYPE_VIDEO]?.sampleMimeType)
            assertEquals("audio/mp4a-latm", formats[C.TRACK_TYPE_AUDIO]?.sampleMimeType)
            val video = timestamps[C.TRACK_TYPE_VIDEO].orEmpty()
            val audio = timestamps[C.TRACK_TYPE_AUDIO].orEmpty()
            // Media3 1.11.1 has a separate EOF flush defect: the last pending
            // picture may need the next segment's boundary. Do not treat this
            // test as full end-of-stream acceptance until that defect is fixed.
            assertTrue("Only ${video.size} video access units", video.size >= 48)
            assertTrue("Only ${audio.size} audio access units", audio.size >= 90)
            assertTrue(video.zipWithNext().all { (a,b) -> b > a })
            assertTrue(audio.zipWithNext().all { (a,b) -> b > a })
        } finally { extractor.release() }
    }
}
