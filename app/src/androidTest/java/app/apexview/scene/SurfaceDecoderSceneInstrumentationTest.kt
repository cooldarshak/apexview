package app.apexview.scene

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.apexview.tiled.HevcComposedPlayback
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The actual L3 rolling scene uses a separate SurfaceFlinger video layer.
 * PixelCopy of its synthetic, unprotected test signal verifies source crops
 * without depending on the Compose/TextureView readback path. It never reads
 * encrypted F1 media or runs against an authenticated stream.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SurfaceDecoderSceneInstrumentationTest {
    @Test fun oneDecoderSurfaceLayerMaintainsCameraIdentityAcrossLayoutReconfiguration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val full = instrumentation.context.assets.open("hevc_tizen_cmaf_grid.mp4").use {
            it.readBytes()
        }
        val fragments = splitBoxes(full)
        val firstMoof = fragments.first { it.first == "moof" }.second
        val media = fragments.filter { it.second >= firstMoof }
        val segmentBytes = media.chunked(2).map {
            require(it.size == 2 && it[0].first == "moof" && it[1].first == "mdat")
            full.copyOfRange(it[0].second, it[1].third)
        }
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, SingleDecoderSceneTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as SingleDecoderSceneTestActivity
        var playback: HevcComposedPlayback? = null
        var surface: ProtectedDecoderSceneView? = null
        val firstFrame = CountDownLatch(1)
        val failures = AtomicReference<String?>(null)
        val initializations = AtomicInteger()
        val activeDecoders = AtomicInteger()
        val maxActiveDecoders = AtomicInteger()
        val correlation = SceneCodecTimestampCorrelation()
        // The renderer owns and periodically drains interval correlation metrics.
        // Capture real callback evidence independently for each surface generation.
        val initialCodecFrame = CountDownLatch(1)
        val codecFrameEvidence = AtomicReference(initialCodecFrame)
        val original = listOf(
            VideoWindow(10, 0, 0f, 0f, 400f, 225f, 0f),
            VideoWindow(11, 1, 400f, 300f, 400f, 225f, 1f)
        )
        fun scene(windows: List<VideoWindow>) = VideoScene(800f, 600f,
            playback!!.outputWidth, playback!!.outputHeight, 2, windows)
        fun capture(): Bitmap {
            val image = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            val result = AtomicInteger(-1)
            instrumentation.runOnMainSync {
                PixelCopy.request(surface!!, image, {
                    result.set(it)
                    copied.countDown()
                }, Handler(Looper.getMainLooper()))
            }
            assertTrue("SurfaceFlinger frame copy timed out", copied.await(5, TimeUnit.SECONDS))
            assertEquals("Scene output was not presented on its independent surface",
                PixelCopy.SUCCESS, result.get())
            return image
        }
        fun mean(image: Bitmap, x: Int, y: Int): Int {
            val pixel = image.getPixel(x, y)
            return (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
        }
        try {
            instrumentation.runOnMainSync {
                playback = HevcComposedPlayback(activity, full.copyOfRange(0, firstMoof),
                    segmentBytes)
                playback!!.player.setVideoFrameMetadataListener(object : VideoFrameMetadataListener {
                    override fun onVideoFrameAboutToBeRendered(
                        presentationTimeUs: Long, releaseTimeNs: Long, format: Format,
                        mediaFormat: MediaFormat?,
                    ) {
                        val evidence = codecFrameEvidence.get()
                        if (correlation.active && presentationTimeUs in 0L..(Long.MAX_VALUE / 1000L)) {
                            correlation.onCodecFrame(presentationTimeUs, releaseTimeNs, System.nanoTime())
                            evidence.countDown()
                        }
                    }
                })
                playback!!.player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        failures.set(error.errorCodeName)
                        firstFrame.countDown()
                    }
                })
                playback!!.player.addAnalyticsListener(
                    object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                        override fun onVideoDecoderInitialized(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            name: String, initializedTimestampMs: Long,
                            initializationDurationMs: Long
                        ) {
                            initializations.incrementAndGet()
                            val current = activeDecoders.incrementAndGet()
                            maxActiveDecoders.updateAndGet { maxOf(it, current) }
                        }
                        override fun onVideoDecoderReleased(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            name: String,
                        ) { activeDecoders.decrementAndGet() }
                    }
                )
                surface = ProtectedDecoderSceneView(activity, protectedOutput = false).apply {
                    onFirstFrame = { firstFrame.countDown() }
                    onFailure = { failures.set(it); firstFrame.countDown() }
                }
                activity.root.addView(surface, FrameLayout.LayoutParams(-1, -1))
                surface!!.bind(playback!!.player, scene(original),
                    correlation = correlation)
                playback!!.prepare()
            }
            assertTrue("No frame reached the independent video SurfaceView",
                firstFrame.await(30, TimeUnit.SECONDS))
            failures.get()?.let { throw AssertionError(it) }
            assertTrue("Codec PTS listener must execute on the initial real video renderer",
                initialCodecFrame.await(5, TimeUnit.SECONDS))
            assertTrue("Initial decoder surface must own timestamp correlation", correlation.active)
            // Capture only clear synthetic GPU pixels: this is not protected
            // F1 output and is never an authorization or DRM-bypass path.
            val originalImage = capture()
            assertTrue("Both original video windows must have visible pixels",
                mean(originalImage, 100, 100) > 20 &&
                mean(originalImage, 500, 400) > 20)
            originalImage.recycle()
            instrumentation.runOnMainSync {
                surface!!.bind(playback!!.player, scene(original.map { window ->
                    window.copy(x = if (window.source == 0) 400f else 0f,
                        y = if (window.source == 0) 300f else 0f)
                }), correlation = correlation)
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var observed = false
            do {
                val bitmap = capture()
                observed = mean(bitmap, 100, 100) > 20 &&
                    mean(bitmap, 500, 400) > 20
                bitmap.recycle()
                if (!observed) SystemClock.sleep(50)
            } while (!observed && SystemClock.elapsedRealtime() < deadline)
            assertTrue("Moving camera windows must reuse the same surface", observed)
            assertTrue("Layout-only updates must preserve timestamp correlation", correlation.active)
            assertEquals("Scene layout updates must not recreate the decoder",
                1, initializations.get())
            assertEquals("Only one decoder may be active during unchanged layout",
                1, maxActiveDecoders.get())

            // The old SurfaceView must release codec ownership before a newly
            // created SurfaceView can present a fresh seeked frame. Repeat on
            // the actual EGL and MediaCodec pipeline, not a mock view.
            val recreated = CountDownLatch(1)
            val recreatedCodecFrame = CountDownLatch(1)
            instrumentation.runOnMainSync {
                surface!!.release()
                assertFalse("Retired surface must release timestamp correlation", correlation.active)
                codecFrameEvidence.set(recreatedCodecFrame)
                activity.root.removeView(surface)
                surface = ProtectedDecoderSceneView(activity, protectedOutput = false).apply {
                    onFirstFrame = { recreated.countDown() }
                    onFailure = { failures.set(it); recreated.countDown() }
                }
                activity.root.addView(surface, FrameLayout.LayoutParams(-1, -1))
                surface!!.bind(playback!!.player, scene(original),
                    correlation = correlation)
                playback!!.player.seekTo(0L)
                playback!!.player.play()
            }
            assertTrue("Recreated video surface must present after seek",
                recreated.await(30, TimeUnit.SECONDS))
            failures.get()?.let { throw AssertionError(it) }
            val recovered = capture()
            try {
                assertTrue("After surface recreation and seek both tiles remain visible",
                    mean(recovered, 100, 100) > 20 && mean(recovered, 500, 400) > 20)
            } finally { recovered.recycle() }
            assertTrue("Surface handoff must not spawn two concurrently active decoders",
                maxActiveDecoders.get() <= 1)
            assertTrue("Codec PTS listener must execute on the recreated real video renderer",
                recreatedCodecFrame.await(5, TimeUnit.SECONDS))
            assertTrue("Recreated decoder surface must own timestamp correlation", correlation.active)
        } finally {
            instrumentation.runOnMainSync {
                surface?.release()
                playback?.release()
                activity.finish()
            }
        }
    }

    private fun splitBoxes(bytes: ByteArray): List<Triple<String, Int, Int>> {
        val result = mutableListOf<Triple<String, Int, Int>>()
        var cursor = 0
        while (cursor < bytes.size) {
            require(cursor + 8 <= bytes.size)
            val size = ByteBuffer.wrap(bytes, cursor, 4).int
            require(size >= 8 && size <= bytes.size - cursor)
            result += Triple(String(bytes, cursor + 4, 4, Charsets.US_ASCII),
                cursor, cursor + size)
            cursor += size
        }
        return result
    }
}
