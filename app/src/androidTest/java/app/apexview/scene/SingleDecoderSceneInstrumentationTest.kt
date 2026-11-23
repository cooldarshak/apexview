package app.apexview.scene

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodecList
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.apexview.tiled.HevcComposedPlayback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real GPU pixels after layout changes, using the same clear synthetic asset as the codec proof. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SingleDecoderSceneInstrumentationTest {
    @Test fun movesAndOverlapsCameraPixelsWithoutStartingAnotherVideoDecoder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("hevc_tizen_cmaf_grid.mp4").use { it.readBytes() }
        val firstMoof = boxes(bytes).first { it.first == "moof" }.second
        val fragments = mutableListOf<ByteArray>()
        val media = boxes(bytes).filter { it.second >= firstMoof }
        for (pair in media.chunked(2)) {
            require(pair.size == 2 && pair[0].first == "moof" && pair[1].first == "mdat")
            fragments += bytes.copyOfRange(pair[0].second, pair[1].third)
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SingleDecoderSceneTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SingleDecoderSceneTestActivity
        var playback: HevcComposedPlayback? = null
        var view: SingleDecoderSceneView? = null
        val failure = AtomicReference<String?>(null)
        val decoder = AtomicReference<String?>(null)
        val initializations = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val firstFrame = CountDownLatch(1)
        fun scene(windows: List<VideoWindow>) = VideoScene(800f, 600f,
            playback!!.outputWidth, playback!!.outputHeight, 2, windows)
        val a = VideoWindow(10, 0, 0f, 0f, 400f, 225f, 0f)
        val b = VideoWindow(11, 1, 400f, 300f, 400f, 225f, 1f)
        fun captureUntil(predicate: (Bitmap) -> Boolean): Bitmap {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            do {
                failure.get()?.let { throw AssertionError(it) }
                var image: Bitmap? = null
                instrumentation.runOnMainSync { if (view!!.isAvailable) image = view!!.getBitmap(800, 600) }
                image?.let { if (predicate(it)) return it else it.recycle() }
                SystemClock.sleep(40)
            } while (SystemClock.elapsedRealtime() < deadline)
            throw AssertionError("Scene pixels did not reach the requested layout")
        }
        try {
            instrumentation.runOnMainSync {
                playback = HevcComposedPlayback(activity, bytes.copyOfRange(0, firstMoof), fragments)
                playback!!.player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failure.set(error.errorCodeName) ; firstFrame.countDown() }
                })
                playback!!.player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String,
                        initializedTimestampMs: Long, initializationDurationMs: Long) {
                        decoder.set(name); initializations.incrementAndGet()
                        val count = active.incrementAndGet(); maxActive.updateAndGet { maxOf(it, count) }
                    }
                    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, name: String) { active.decrementAndGet() }
                    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                        playback!!.pause(); firstFrame.countDown()
                    }
                })
                view = SingleDecoderSceneView(activity).apply { onFailure = { failure.set(it); firstFrame.countDown() } }
                activity.root.addView(view, FrameLayout.LayoutParams(-1, -1))
                view!!.bind(playback!!.player, scene(listOf(a, b)))
                playback!!.prepare()
            }
            assertTrue("No first frame from the scene decoder", firstFrame.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError(it) }
            // Allow any frame already queued when pause reached the codec to settle before comparing.
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            val original = captureUntil { image ->
                patchBrightness(image, 0, 0) > 30 && patchBrightness(image, 400, 300) > 30 &&
                    Color.red(image.getPixel(600, 100)) < 5
            }
            try {
                assertTrue("The two atlas cameras must show different source pixels", patchDifference(original, 0, 0, original, 400, 300) > 15)
                instrumentation.runOnMainSync {
                    view!!.bind(playback!!.player, scene(listOf(a.copy(x = 400f, y = 300f), b.copy(x = 0f, y = 0f))))
                }
                captureUntil { moved -> patchDifference(original, 0, 0, moved, 400, 300) < 6 &&
                    patchDifference(original, 400, 300, moved, 0, 0) < 6 }.recycle()
                instrumentation.runOnMainSync {
                    view!!.bind(playback!!.player, scene(listOf(a, b.copy(x = 0f, y = 0f, z = 9f))))
                }
                captureUntil { overlap -> patchDifference(original, 400, 300, overlap, 0, 0) < 6 &&
                    patchBrightness(overlap, 400, 300) < 5 }.recycle()
                assertEquals("Moving windows must reuse the initialized video decoder", 1, initializations.get())
                assertEquals("The renderer must not create parallel video decoders", 1, maxActive.get())
                val codec = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == decoder.get() }
                assertNotNull("No observed scene decoder", codec)
                assertTrue("A hardware decoder is required", codec!!.isHardwareAccelerated)
                // Recreate the REAL TextureView output, then seek while the same
                // one-player HEVC archive remains active. This exercises the
                // TextureView/EGL lifecycle rather than only crop geometry.
                val recoveredFrame = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    view!!.release()
                    activity.root.removeView(view)
                    view = SingleDecoderSceneView(activity).apply {
                        onFirstFrame = { recoveredFrame.countDown() }
                        onFailure = { failure.set(it); recoveredFrame.countDown() }
                    }
                    activity.root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    view!!.bind(playback!!.player, scene(listOf(a, b)))
                    playback!!.player.seekTo(0L)
                    playback!!.player.play()
                }
                assertTrue("A real clear TextureView must recover after seek and output recreation",
                    recoveredFrame.await(30, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError(it) }
                captureUntil { image ->
                    patchBrightness(image, 0, 0) > 30 &&
                        patchBrightness(image, 400, 300) > 30
                }.recycle()
                assertEquals("Clear output recreation must not create parallel video decoders",
                    1, maxActive.get())
                Log.i("ApexviewSingleDecoder", "SINGLE_DECODER_SCENE_PIXELS_PASS decoder=${decoder.get()} " +
                    "maxActiveVideoDecoders=${maxActive.get()} move=PASS sourceIdentity=PASS " +
                    "overlap=PASS seek=PASS recreation=PASS outputViews=1")
            } finally { original.recycle() }
        } finally {
            instrumentation.runOnMainSync { view?.release(); playback?.release(); activity.finish() }
        }
    }

    private fun patchBrightness(bitmap: Bitmap, x: Int, y: Int): Double =
        points().map { (dx, dy) -> val c = bitmap.getPixel(x + dx, y + dy)
            (Color.red(c) + Color.green(c) + Color.blue(c)) / 3.0 }.average()

    private fun patchDifference(a: Bitmap, ax: Int, ay: Int, b: Bitmap, bx: Int, by: Int): Double =
        points().map { (dx, dy) -> val c = a.getPixel(ax + dx, ay + dy); val d = b.getPixel(bx + dx, by + dy)
            (kotlin.math.abs(Color.red(c) - Color.red(d)) + kotlin.math.abs(Color.green(c) - Color.green(d)) +
                kotlin.math.abs(Color.blue(c) - Color.blue(d))) / 3.0 }.average()

    private fun points() = (1..4).flatMap { y -> (1..6).map { x -> x * 50 to y * 40 } }

    private fun boxes(bytes: ByteArray): List<Triple<String, Int, Int>> {
        val result = mutableListOf<Triple<String, Int, Int>>()
        var offset = 0
        while (offset < bytes.size) {
            require(offset + 8 <= bytes.size)
            val size = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
            require(size >= 8 && size <= bytes.size - offset)
            result += Triple(String(bytes, offset + 4, 4, Charsets.US_ASCII), offset, offset + size)
            offset += size
        }
        return result
    }
}
