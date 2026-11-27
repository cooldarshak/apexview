package app.apexview.cast

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
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
        val root = File(requireNotNull(path))
        verifyExactPictures(root)
        verifyExactPictures(root.resolve("no-aud"))
    }

    private fun verifyExactPictures(dir: File) {
        assertTrue("Rust fixture missing: ${dir.name}", dir.resolve("segment-0.ts").isFile)
        data class Picture(val ptsUs: Long, val key: Boolean, val bytes: ByteArray)
        val expected = (0..1).flatMap { segment ->
            DataInputStream(dir.resolve("segment-$segment.samples").inputStream().buffered()).use { source ->
                buildList {
                    while (source.available() > 0) {
                        val pts = source.readLong()
                        val size = source.readInt()
                        val flags = source.readUnsignedByte()
                        val bytes = ByteArray(size).also { source.readFully(it) }
                        if (flags and 1 != 0) add(Picture(pts, flags and 2 != 0, bytes))
                    }
                }
            }
        }
        assertEquals(50, expected.size)
        val received = mutableListOf<Picture>()
        val pending = ByteArrayOutputStream()
        val formats = mutableMapOf<Int, Format>()
        val timestamps = mutableMapOf<Int, MutableList<Long>>()
        val tracks = mutableMapOf<Int, TrackOutput>()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
                object : TrackOutput by DiscardingTrackOutput() {
                    override fun format(format: Format) { formats[type] = format }
                    override fun sampleData(data: ParsableByteArray, length: Int) {
                        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
                    }
                    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                        if (type == C.TRACK_TYPE_VIDEO) pending.write(data.data, data.position, length)
                        data.skipBytes(length)
                    }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int,
                                                cryptoData: TrackOutput.CryptoData?) {
                        if (size > 0) timestamps.getOrPut(type) { mutableListOf() }.add(timeUs)
                        if (type == C.TRACK_TYPE_VIDEO) {
                            assertEquals("No speculative video sample offset", 0, offset)
                            assertEquals("Exact picture extent", pending.size(), size)
                            assertNull(cryptoData)
                            received.add(Picture(timeUs, flags and C.BUFFER_FLAG_KEY_FRAME != 0, pending.toByteArray()))
                            pending.reset()
                        }
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
                        val n = minOf(length, bytes.size-at, 173)
                        bytes.copyInto(target, offset, at, at+n)
                        at += n
                        n
                    }
                }
                val input = DefaultExtractorInput(reader, 0L, bytes.size.toLong())
                while (extractor.read(input)) {}
                assertFalse("Repeated EOF must not publish another picture", extractor.read(input))
                assertEquals("Complete video access units at segment boundary", (segment + 1) * 25,
                    timestamps[C.TRACK_TYPE_VIDEO].orEmpty().size)
            }
            assertEquals("video/avc", formats[C.TRACK_TYPE_VIDEO]?.sampleMimeType)
            assertEquals("audio/mp4a-latm", formats[C.TRACK_TYPE_AUDIO]?.sampleMimeType)
            val video = timestamps[C.TRACK_TYPE_VIDEO].orEmpty()
            val audio = timestamps[C.TRACK_TYPE_AUDIO].orEmpty()
            assertEquals(50, video.size)
            assertEquals(94, audio.size)
            assertEquals("No uncommitted AVC bytes", 0, pending.size())
            assertEquals(expected.size, received.size)
            expected.zip(received).forEachIndexed { i, (want, got) ->
                assertEquals("PTS of picture $i", want.ptsUs, got.ptsUs)
                assertEquals("Random access of picture $i", want.key, got.key)
                assertArrayEquals("Exact encoded picture $i", want.bytes, got.bytes)
            }
            assertEquals(0, formats.keys.count { it == C.TRACK_TYPE_TEXT })
            assertTrue(video.zipWithNext().all { (a,b) -> b > a })
            assertTrue(audio.zipWithNext().all { (a,b) -> b > a })
        } finally { /* Chunk owns only Media3 parser state, no external resources. */ }
    }
}
