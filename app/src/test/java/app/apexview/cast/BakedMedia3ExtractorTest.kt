package app.apexview.cast

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.File
import app.apexview.cast.local.BakedHlsExtractorFactory
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
        val extractor = BakedHlsExtractorFactory.Chunk(TimestampAdjuster(0))
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
                while (extractor.read(input)) {}
                assertEquals("Complete video access units at segment boundary", (segment + 1) * 25,
                    timestamps[C.TRACK_TYPE_VIDEO].orEmpty().size)
            }
            assertEquals("video/avc", formats[C.TRACK_TYPE_VIDEO]?.sampleMimeType)
            assertEquals("audio/mp4a-latm", formats[C.TRACK_TYPE_AUDIO]?.sampleMimeType)
            val video = timestamps[C.TRACK_TYPE_VIDEO].orEmpty()
            val audio = timestamps[C.TRACK_TYPE_AUDIO].orEmpty()
            assertEquals(50, video.size)
            assertEquals(94, audio.size)
            assertEquals(0, formats.keys.count { it == C.TRACK_TYPE_TEXT })
            assertTrue(video.zipWithNext().all { (a,b) -> b > a })
            assertTrue(audio.zipWithNext().all { (a,b) -> b > a })
        } finally { /* Chunk owns only Media3 parser state, no external resources. */ }
    }
}
