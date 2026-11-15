package app.apexview.tiled

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodecList
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side proof harness for the synthetic tile reassembly only.
 *
 * This does not touch F1 streams, licences or the production player graph. One MediaCodec
 * decoder is created for the reassembled synthetic MP4 and all output frames are drained.
 */
@RunWith(AndroidJUnit4::class)
class HevcSingleDecoderInstrumentationTest {
    @Test
    fun decodesReassembledSyntheticTilePictureSequenceWithOneMediaCodec() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val sampleFile = File(context.cacheDir, "apexview-hevc-single-decoder-proof.mp4")
        context.assets.open("reassembled.mp4").use { input ->
            sampleFile.outputStream().use { output -> input.copyTo(output) }
        }

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(sampleFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ==
                    MediaFormat.MIMETYPE_VIDEO_HEVC
            }
            assertNotNull("Synthetic MP4 does not contain a HEVC video track", trackIndex)
            val selectedTrack = trackIndex!!
            extractor.selectTrack(selectedTrack)
            val format = extractor.getTrackFormat(selectedTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, mime)

            // The harness deliberately creates exactly one decoder and never constructs a
            // second MediaCodec for this media sequence.
            val hardwareCodecInfo = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { codec ->
                    !codec.isEncoder && codec.isHardwareAccelerated &&
                        codec.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
            assertNotNull("No hardware-accelerated HEVC decoder is available", hardwareCodecInfo)
            assertTrue("Selected HEVC codec is not hardware accelerated", hardwareCodecInfo!!.isHardwareAccelerated)

            // Exactly one hardware decoder is instantiated for the complete 50-frame stream.
            decoder = MediaCodec.createByCodecName(hardwareCodecInfo.name)
            val decoderName = decoder.name
            assertTrue("Created decoder is not hardware accelerated", decoder.codecInfo.isHardwareAccelerated)
            decoder.configure(format, null, null, 0)
            decoder.start()

            var inputEnded = false
            var outputEnded = false
            var decodedFrames = 0
            var outputWidth: Int? = null
            var outputHeight: Int? = null
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS

            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        assertNotNull("MediaCodec returned a null input buffer", inputBuffer)
                        inputBuffer!!.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else 0
                            decoder.queueInputBuffer(
                                inputIndex, 0, sampleSize, presentationTimeUs, flags
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        outputWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                        outputHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) decodedFrames++
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (eos) outputEnded = true
                    }
                }
            }

            assertTrue("Decoder did not reach end of stream before timeout", outputEnded)
            assertEquals("Unexpected decoded frame count", EXPECTED_FRAMES, decodedFrames)
            assertEquals(EXPECTED_WIDTH, outputWidth)
            assertEquals(EXPECTED_HEIGHT, outputHeight)
            val codecMetrics = decoder.metrics
            val metricSummary = codecMetrics.keySet().sorted().joinToString { key ->
                "$key=${codecMetrics.get(key)}"
            }
            Log.i(
                LOG_TAG,
                "SYNTHETIC_SINGLE_HARDWARE_DECODER_PASS decoder=$decoderName " +
                    "hardwareAccelerated=true decoderInstancesCreated=1 frames=$decodedFrames " +
                    "dimensions=${outputWidth}x${outputHeight} metrics={$metricSummary}"
            )
        } finally {
            try {
                decoder?.stop()
            } catch (_: IllegalStateException) {
                // The codec may not have reached the started state on a setup failure.
            }
            decoder?.release()
            extractor.release()
            sampleFile.delete()
        }
    }

    private companion object {
        const val LOG_TAG = "ApexviewHevcProof"
        const val EXPECTED_FRAMES = 50
        const val EXPECTED_WIDTH = 256
        const val EXPECTED_HEIGHT = 128
        const val CODEC_TIMEOUT_US = 10_000L
        const val DECODE_TIMEOUT_MS = 60_000L
    }
}
