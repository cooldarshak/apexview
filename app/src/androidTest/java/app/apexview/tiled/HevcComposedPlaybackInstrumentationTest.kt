package app.apexview.tiled

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the Tizen-style composed CMAF package through the actual Android Media3 DASH pipeline.
 *
 * This is a clear synthetic fixture test. It does not request F1 feeds, touch a licence, decrypt
 * protected media, or change the production multi-player playback graph.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class HevcComposedPlaybackInstrumentationTest {
    @Test
    fun playsComposedCmafThroughOneMedia3HardwareDecoderAndOneSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open(ASSET_NAME).use { it.readBytes() }
        assertTrue("Composed CMAF asset is empty", bytes.isNotEmpty())

        val (initializationSegment, mediaFragments) = splitCmaf(bytes)
        assertTrue("Composed CMAF has no media fragments", mediaFragments.isNotEmpty())

        val cacheDir = instrumentation.targetContext.cacheDir
        assertTrue(cacheDir.isDirectory || cacheDir.mkdirs())
        val metadataFile = File(cacheDir, "apexview-media3-composed-proof.mp4")
        metadataFile.writeBytes(bytes)

        val extractor = MediaExtractor()
        var playback: HevcComposedPlayback? = null
        var outputReader: ImageReader? = null
        var outputThread: HandlerThread? = null
        try {
            extractor.setDataSource(metadataFile.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ==
                    MediaFormat.MIMETYPE_VIDEO_HEVC
            }
            assertNotNull("Composed CMAF has no HEVC video track", videoTrack)
            val selectedVideoTrack = videoTrack!!
            extractor.selectTrack(selectedVideoTrack)
            val format = extractor.getTrackFormat(selectedVideoTrack)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            assertTrue("Invalid composed video dimensions: ${width}x$height", width > 0 && height > 0)

            var extractedSamples = 0
            while (extractor.sampleTime >= 0L) {
                extractedSamples++
                extractor.advance()
            }
            assertTrue("Composed CMAF contains no video samples", extractedSamples > 0)

            val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_IMAGES)
            outputReader = reader
            val frameThread = HandlerThread("ApexviewComposedMedia3Output").apply { start() }
            outputThread = frameThread
            val renderedFrames = AtomicInteger(0)
            val renderedWidth = AtomicInteger(0)
            val renderedHeight = AtomicInteger(0)
            val frameTimestamps = ConcurrentLinkedQueue<Long>()
            val outputSurfaceAssignments = AtomicInteger(0)
            reader.setOnImageAvailableListener({ availableReader ->
                while (true) {
                    val image = availableReader.acquireNextImage() ?: break
                    try {
                        renderedFrames.incrementAndGet()
                        renderedWidth.set(image.width)
                        renderedHeight.set(image.height)
                        if (image.timestamp > 0L) frameTimestamps.add(image.timestamp)
                    } finally {
                        image.close()
                    }
                }
            }, Handler(frameThread.looper))

            val decoderInitializations = AtomicInteger(0)
            val activeDecoders = AtomicInteger(0)
            val maxActiveDecoders = AtomicInteger(0)
            val decoderName = AtomicReference<String?>(null)
            val videoSize = AtomicReference<VideoSize?>(null)
            val playbackFailure = AtomicReference<String?>(null)
            val playbackEnded = CountDownLatch(1)

            instrumentation.runOnMainSync {
                playback = HevcComposedPlayback(
                    instrumentation.targetContext,
                    initializationSegment,
                    mediaFragments
                )
                val player = playback!!.player
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) playbackEnded.countDown()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playbackFailure.set("${error.errorCodeName}: ${error.message}")
                        playbackEnded.countDown()
                    }

                    override fun onVideoSizeChanged(size: VideoSize) {
                        videoSize.set(size)
                    }
                })
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        name: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        decoderName.set(name)
                        decoderInitializations.incrementAndGet()
                        val active = activeDecoders.incrementAndGet()
                        maxActiveDecoders.updateAndGet { maxOf(it, active) }
                    }

                    override fun onVideoDecoderReleased(
                        eventTime: AnalyticsListener.EventTime,
                        name: String
                    ) {
                        activeDecoders.decrementAndGet()
                    }
                })
                assertTrue("Composed output Surface is invalid", reader.surface.isValid)
                playback!!.setVideoSurface(reader.surface)
                outputSurfaceAssignments.incrementAndGet()
                playback!!.prepare(playWhenReady = true)
            }

            assertTrue(
                "Media3 did not finish the composed CMAF presentation within 90 seconds",
                playbackEnded.await(90, TimeUnit.SECONDS)
            )
            playbackFailure.get()?.let { throw AssertionError("Media3 playback failed: $it") }

            val renderDeadline = SystemClock.elapsedRealtime() + RENDER_WAIT_MS
            while (renderedFrames.get() < extractedSamples &&
                SystemClock.elapsedRealtime() < renderDeadline) {
                SystemClock.sleep(10L)
            }

            val player = playback!!.player
            val selectedDecoder = decoderName.get()
            assertNotNull("Media3 never initialized a video decoder", selectedDecoder)
            val codecInfo = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { it.name == selectedDecoder }
            assertNotNull("Selected decoder is not listed by MediaCodec", codecInfo)
            assertTrue(
                "Media3 selected a non-hardware HEVC decoder: $selectedDecoder",
                codecInfo!!.isHardwareAccelerated
            )
            assertEquals("Media3 must initialize one video decoder", 1, decoderInitializations.get())
            assertEquals("The composed player must never have parallel video decoders", 1, maxActiveDecoders.get())
            assertEquals("The composed player must be assigned one output Surface", 1, outputSurfaceAssignments.get())
            assertEquals("Every CMAF video sample should render", extractedSamples, renderedFrames.get())

            val actualSize = videoSize.get()
            assertNotNull("Media3 did not report the composed video dimensions", actualSize)
            assertEquals(width, actualSize!!.width)
            assertEquals(height, actualSize.height)
            assertEquals(width, renderedWidth.get())
            assertEquals(height, renderedHeight.get())

            val timestamps = frameTimestamps.toList()
            val averageFrameIntervalMs = if (timestamps.size > 1) {
                (timestamps.last() - timestamps.first()).toDouble() /
                    (timestamps.size - 1) / 1_000_000.0
            } else {
                0.0
            }
            assertTrue(
                "Not enough surface timestamps to measure frame cadence",
                timestamps.size == extractedSamples && averageFrameIntervalMs > 0.0
            )
            val droppedFrames = player.videoDecoderCounters?.droppedBufferCount ?: 0
            Log.i(
                LOG_TAG,
                "MEDIA3_COMPOSED_CMAF_PASS decoder=$selectedDecoder hardwareAccelerated=true " +
                    "decoderInitializations=${decoderInitializations.get()} " +
                    "maxActiveVideoDecoders=${maxActiveDecoders.get()} outputSurfaces=1 " +
                    "samples=$extractedSamples renderedFrames=${renderedFrames.get()} " +
                    "dimensions=${width}x$height averageFrameIntervalMs=$averageFrameIntervalMs " +
                    "droppedFrames=$droppedFrames fragments=${mediaFragments.size}"
            )
        } finally {
            instrumentation.runOnMainSync { playback?.release() }
            outputReader?.close()
            outputThread?.quitSafely()
            outputThread?.join(2_000L)
            extractor.release()
            metadataFile.delete()
        }
    }

    private fun splitCmaf(bytes: ByteArray): Pair<ByteArray, List<ByteArray>> {
        val topLevel = mutableListOf<BoxRange>()
        var offset = 0
        while (offset < bytes.size) {
            require(bytes.size - offset >= 8) { "Truncated top-level CMAF box" }
            val size = readUInt32(bytes, offset)
            require(size >= 8L && size <= Int.MAX_VALUE.toLong()) { "Unsupported CMAF box size" }
            val end = Math.addExact(offset, size.toInt())
            require(end <= bytes.size) { "CMAF box exceeds its source buffer" }
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            topLevel += BoxRange(type, offset, end)
            offset = end
        }

        val firstMoofIndex = topLevel.indexOfFirst { it.type == "moof" }
        require(firstMoofIndex > 0) { "Composed CMAF has no moof after its init segment" }
        val initEnd = topLevel[firstMoofIndex].start
        val fragments = mutableListOf<ByteArray>()
        var cursor = firstMoofIndex
        while (cursor < topLevel.size) {
            require(topLevel[cursor].type == "moof") { "Unexpected box before composed media fragment" }
            val fragmentStart = topLevel[cursor].start
            cursor++
            require(cursor < topLevel.size && topLevel[cursor].type == "mdat") {
                "Composed media fragment has no following mdat"
            }
            val fragmentEnd = topLevel[cursor].end
            fragments += bytes.copyOfRange(fragmentStart, fragmentEnd)
            cursor++
        }
        return bytes.copyOfRange(0, initEnd) to fragments
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private data class BoxRange(val type: String, val start: Int, val end: Int)

    private companion object {
        const val LOG_TAG = "ApexviewHevcMedia3"
        const val ASSET_NAME = "hevc_tizen_cmaf_grid.mp4"
        const val MAX_IMAGES = 8
        const val RENDER_WAIT_MS = 15_000L
    }
}
