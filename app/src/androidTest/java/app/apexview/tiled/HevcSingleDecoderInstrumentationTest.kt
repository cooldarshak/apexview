package app.apexview.tiled

import android.media.MediaCodec
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import androidx.media3.common.Player
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodecList
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
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
 * This does not touch F1 streams, licences or the production player graph. One hardware
 * MediaCodec decoder renders the reassembled synthetic MP4 to one ImageReader Surface.
 * The listener counts the actual frames delivered to that single output surface.
 */
@RunWith(AndroidJUnit4::class)
class HevcSingleDecoderInstrumentationTest {
    @Test
    fun decodesReassembledSyntheticTilePictureSequenceWithOneMediaCodec() =
        decodeAssetWithOneHardwareDecoder("reassembled.mp4")

    @Test
    fun decodesReassembledSyntheticTwoByTwoPictureSequenceWithOneMediaCodec() =
        decodeAssetWithOneHardwareDecoder("reassembled_2x2.mp4")

    @Test
    fun playsComposedClearCmafThroughOneMedia3PlayerDecoderAndSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val cmaf = instrumentation.context.assets.open("hevc_tizen_cmaf_grid.mp4").use { it.readBytes() }
        assertTrue("Composed clear CMAF asset is missing", cmaf.isNotEmpty())

        val outputReader = ImageReader.newInstance(
            COMPOSED_WIDTH, COMPOSED_HEIGHT, ImageFormat.YUV_420_888, MAX_SURFACE_IMAGES
        )
        val outputThread = HandlerThread("ApexviewMedia3ComposedSurface").apply { start() }
        val renderedFrames = AtomicInteger(0)
        outputReader.setOnImageAvailableListener({ reader ->
            while (true) {
                val frame = reader.acquireNextImage() ?: break
                try {
                    renderedFrames.incrementAndGet()
                } finally {
                    frame.close()
                }
            }
        }, Handler(outputThread.looper))

        var composed: HevcComposedMediaPlayer? = null
        try {
            composed = HevcComposedMediaPlayer.createFromClearCmaf(
                target,
                cmaf,
                outputReader.surface
            )
            val player = composed.player
            val deadline = SystemClock.elapsedRealtime() + COMPOSED_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline &&
                player.playbackState != Player.STATE_ENDED &&
                composed.diagnostics.playbackError == null) {
                SystemClock.sleep(20)
            }
            SystemClock.sleep(500)

            val evidence = composed.diagnostics
            assertEquals("Media3 must create exactly one video decoder", 1, evidence.decoderInstancesCreated)
            assertTrue("The selected HEVC decoder must be hardware accelerated", evidence.hardwareAccelerated == true)
            assertEquals("Exactly one output Surface is owned by the composed player", 1, composed.outputSurfaceCount)
            assertTrue("No Media3 playback error expected: ${evidence.playbackError}", evidence.playbackError == null)
            assertTrue("First composed frame was not rendered", evidence.firstFrameRendered)
            assertEquals(COMPOSED_WIDTH, evidence.outputWidth)
            assertEquals(COMPOSED_HEIGHT, evidence.outputHeight)
            assertEquals("Expected all 50 composed frames on the one output Surface", 50, renderedFrames.get())
            Log.i(
                LOG_TAG,
                "MEDIA3_COMPOSED_CLEAR_CMAF_SINGLE_DECODER_PASS " +
                    "decoder=${evidence.decoderName} hardwareAccelerated=${evidence.hardwareAccelerated} " +
                    "decoderInstancesCreated=${evidence.decoderInstancesCreated} " +
                    "outputSurfacesCreated=${composed.outputSurfaceCount} " +
                    "outputSurfaceFrames=${renderedFrames.get()} " +
                    "dimensions=${evidence.outputWidth}x${evidence.outputHeight} " +
                    "droppedFrames=${evidence.droppedFrames}"
            )
        } finally {
            composed?.close()
            outputReader.close()
            outputThread.quitSafely()
            outputThread.join(2_000)
        }
    }

    private fun decodeAssetWithOneHardwareDecoder(assetName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assetContext = instrumentation.context
        // Instrumentation package cache directories are not guaranteed to exist on every
        // Android build. Keep the fixture in the target app sandbox and create the directory
        // explicitly before opening the output stream.
        val targetCacheDir = instrumentation.targetContext.cacheDir
        assertTrue(
            "Unable to create target app cache directory: ${targetCacheDir.absolutePath}",
            targetCacheDir.isDirectory || targetCacheDir.mkdirs()
        )
        val sampleFile = File(targetCacheDir, "apexview-hevc-single-decoder-proof-$assetName")
        assetContext.assets.open(assetName).use { input ->
            sampleFile.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue("Synthetic HEVC fixture was not copied to app cache", sampleFile.isFile && sampleFile.length() > 0L)

        val extractor = MediaExtractor()
        val outputReader = ImageReader.newInstance(
            EXPECTED_WIDTH, EXPECTED_HEIGHT, ImageFormat.YUV_420_888, MAX_SURFACE_IMAGES
        )
        val outputThread = HandlerThread("ApexviewHevcOutputSurface").apply { start() }
        val renderedFrames = AtomicInteger(0)
        outputReader.setOnImageAvailableListener({ reader ->
            while (true) {
                val image = reader.acquireNextImage() ?: break
                try {
                    renderedFrames.incrementAndGet()
                } finally {
                    image.close()
                }
            }
        }, Handler(outputThread.looper))
        var decoder: MediaCodec? = null
        var decoderInstancesCreated = 0
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
            val hevcDecoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { codec ->
                    !codec.isEncoder && codec.supportedTypes.any {
                        it.equals(mime, ignoreCase = true)
                    }
                }

            // Report the complete device codec view before selecting anything. The previous
            // preflight required isFormatSupported(format) to return true, which can be more
            // restrictive than MediaCodec.configure() for extractor-provided CSD/metadata.
            // Keep that result diagnostic and let the real configure call be authoritative.
            val codecDiagnostics = hevcDecoders.joinToString(separator = "\\n") { codec ->
                val capabilities = runCatching {
                    codec.getCapabilitiesForType(mime!!)
                }.getOrNull()
                val video = capabilities?.videoCapabilities
                val formatSupported = capabilities?.let { caps ->
                    runCatching { caps.isFormatSupported(format) }.getOrNull()
                }
                val profiles = capabilities?.profileLevels?.joinToString { level ->
                    "${level.profile}/${level.level}"
                } ?: "unavailable"
                "name=${codec.name},hardware=${codec.isHardwareAccelerated}," +
                    "software=${codec.isSoftwareOnly},vendor=${codec.isVendor}," +
                    "formatSupported=${formatSupported ?: "unknown"}," +
                    "profiles=$profiles," +
                    "widths=${video?.supportedWidths ?: "unknown"}," +
                    "heights=${video?.supportedHeights ?: "unknown"}," +
                    "alignment=${video?.widthAlignment ?: "?"}x${video?.heightAlignment ?: "?"}"
            }
            Log.i(
                LOG_TAG,
                "HEVC_DECODER_DISCOVERY mime=$mime width=${format.getInteger(MediaFormat.KEY_WIDTH)} " +
                    "height=${format.getInteger(MediaFormat.KEY_HEIGHT)} " +
                    "candidateCount=${hevcDecoders.size} candidates={$codecDiagnostics}"
            )

            val hardwareCandidates = hevcDecoders.filter { it.isHardwareAccelerated }
            assertTrue(
                "No hardware-accelerated HEVC decoder is advertised by Android. " +
                    "Device codec diagnostics: {$codecDiagnostics}",
                hardwareCandidates.isNotEmpty()
            )

            // Prefer a candidate whose advertised capabilities accept the extracted format,
            // but do not reject the candidate solely on this advisory preflight. configure()
            // below is the definitive test and its exception will be preserved in the failure.
            val hardwareCodecInfo = hardwareCandidates.sortedByDescending { codec ->
                runCatching {
                    codec.getCapabilitiesForType(mime!!).isFormatSupported(format)
                }.getOrDefault(false)
            }.first()
            val advertisedFormatSupport = runCatching {
                hardwareCodecInfo.getCapabilitiesForType(mime!!).isFormatSupported(format)
            }.getOrDefault(false)
            Log.i(
                LOG_TAG,
                "HEVC_DECODER_SELECTED name=${hardwareCodecInfo.name} " +
                    "hardwareAccelerated=${hardwareCodecInfo.isHardwareAccelerated} " +
                    "isFormatSupported=$advertisedFormatSupport; attempting MediaCodec.configure"
            )
            assertTrue(
                "Selected HEVC codec is not hardware accelerated: ${hardwareCodecInfo.name}",
                hardwareCodecInfo.isHardwareAccelerated
            )

            // Exactly one hardware decoder is instantiated for the complete 50-frame stream.
            decoder = MediaCodec.createByCodecName(hardwareCodecInfo.name)
            decoderInstancesCreated++
            val decoderName = decoder.name
            assertTrue("Created decoder is not hardware accelerated", decoder.codecInfo.isHardwareAccelerated)
            decoder.configure(format, outputReader.surface, null, 0)
            decoder.start()

            var inputEnded = false
            var outputEnded = false
            var decodedFrames = 0
            var outputWidth: Int? = format.getInteger(MediaFormat.KEY_WIDTH)
            var outputHeight: Int? = format.getInteger(MediaFormat.KEY_HEIGHT)
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
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (eos) {
                            decoder.releaseOutputBuffer(outputIndex, false)
                            outputEnded = true
                        } else {
                            decodedFrames++
                            // Render each decoded picture into the same ImageReader Surface.
                            decoder.releaseOutputBuffer(outputIndex, true)
                        }
                    }
                }
            }

            assertTrue("Decoder did not reach end of stream before timeout", outputEnded)
            val renderDeadline = SystemClock.elapsedRealtime() + RENDER_TIMEOUT_MS
            while (renderedFrames.get() < EXPECTED_FRAMES &&
                SystemClock.elapsedRealtime() < renderDeadline) {
                SystemClock.sleep(RENDER_POLL_MS)
            }
            assertEquals("Unexpected decoder output-frame count", EXPECTED_FRAMES, decodedFrames)
            assertEquals(
                "Unexpected number of frames rendered to the single ImageReader Surface",
                EXPECTED_FRAMES,
                renderedFrames.get()
            )
            assertEquals("Exactly one decoder instance must be created", 1, decoderInstancesCreated)
            assertEquals(EXPECTED_WIDTH, outputWidth)
            assertEquals(EXPECTED_HEIGHT, outputHeight)
            val codecMetrics = decoder.metrics
            val metricSummary = codecMetrics.keySet().sorted().joinToString { key ->
                "$key=${codecMetrics.get(key)}"
            }
            Log.i(
                LOG_TAG,
                "SYNTHETIC_SINGLE_HARDWARE_DECODER_PASS asset=$assetName decoder=$decoderName " +
                    "hardwareAccelerated=true decoderInstancesCreated=$decoderInstancesCreated " +
                    "outputSurfacesCreated=1 outputSurfaceFrames=${renderedFrames.get()} " +
                    "decoderFrames=$decodedFrames dimensions=${outputWidth}x${outputHeight} metrics={$metricSummary}"
            )
        } finally {
            try {
                decoder?.stop()
            } catch (_: IllegalStateException) {
                // The codec may not have reached the started state on a setup failure.
            }
            decoder?.release()
            extractor.release()
            outputReader.close()
            outputThread.quitSafely()
            outputThread.join(2_000)
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
        const val RENDER_TIMEOUT_MS = 15_000L
        const val RENDER_POLL_MS = 10L
        const val MAX_SURFACE_IMAGES = 8
        const val COMPOSED_WIDTH = 1920
        const val COMPOSED_HEIGHT = 540
        const val COMPOSED_TIMEOUT_MS = 60_000L
    }
}
