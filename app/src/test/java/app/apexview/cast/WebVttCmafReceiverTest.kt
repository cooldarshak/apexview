package app.apexview.cast

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.Mp4WebvttParser
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebVttCmafReceiverTest {
    @Test fun media3ExtractsOverlappingWebVttCuesAndClearsTheTrackAtTheBoundary() {
        val cues = listOf(
            RollingWebVtt.Cue(100_000L, 300_000L, "one", "line:80%", "First cue"),
            RollingWebVtt.Cue(200_000L, 300_000L, null, "", "Second cue"),
        )
        val bytes = WebVttCmaf.initialization() + WebVttCmaf.fragment(1, 9_000_000L, 0, 400_000L, cues)
        val samples = mutableListOf<Pair<Long, List<String>>>()
        val input = DefaultExtractorInput(DataReader(bytes.inputStream()::read), 0, bytes.size.toLong())
        val extractor = FragmentedMp4Extractor(FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA)
        val raw = ByteArrayOutputStream()
        val parser = Mp4WebvttParser()
        var mimeType: String? = null
        val track = object : TrackOutput {
            override fun format(format: Format) { mimeType = format.sampleMimeType }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val buffer = ByteArray(length)
                val count = input.read(buffer, 0, length)
                if (count > 0) raw.write(buffer, 0, count)
                return count
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                val buffer = ByteArray(length)
                data.readBytes(buffer, 0, length)
                raw.write(buffer)
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                assertEquals(0, offset)
                val sample = raw.toByteArray().also { raw.reset() }
                assertEquals(size, sample.size)
                val text = mutableListOf<String>()
                parser.parse(sample, 0, size, SubtitleParser.OutputOptions.allCues()) { result ->
                    text += result.cues.map { it.text.toString() }
                }
                samples.add(timeUs to text)
            }
        }
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = track.also { assertEquals(C.TRACK_TYPE_TEXT, type) }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        try {
            assertTrue(extractor.sniff(input))
            input.resetPeekPosition()
            val position = PositionHolder()
            var result: Int
            do {
                result = extractor.read(input, position)
                assertTrue(result != Extractor.RESULT_SEEK)
            } while (result != Extractor.RESULT_END_OF_INPUT)
        } finally { extractor.release() }
        assertEquals(MimeTypes.APPLICATION_MP4VTT, mimeType)
        assertEquals(listOf(9_000_000L to emptyList(), 9_100_000L to listOf("First cue"),
            9_200_000L to listOf("First cue", "Second cue"), 9_300_000L to emptyList()), samples)
    }
}
