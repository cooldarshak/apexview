package app.apexview.scene

import android.content.Intent
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BakedCastCaptureAndroidTest {
    @Test fun directEglKeepsViewerLayerAlphaAndPositionAfterAnUpdate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, SingleDecoderSceneTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as SingleDecoderSceneTestActivity
        val overlay = BakedCastOverlay({ BakedCastVideoPlacement(0.25f, 0.25f, 0.5f) }, Color.rgb(20, 30, 40))
        val moved = mutableStateOf(false)
        val firstOverlay = CountDownLatch(1)
        val nextOverlay = CountDownLatch(1)
        val thread = HandlerThread("PitwallCastParityTest").apply { start() }
        val gl = Handler(thread.looper)
        val egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        var capture: BakedCastGpuCapture? = null

        fun read(): ByteBuffer {
            val pixels = ByteBuffer.allocateDirect(64 * 64 * 4)
            val done = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            fun failed(failure: Throwable) { error.set(failure); done.countDown() }
            gl.post {
                try {
                    if (capture == null) {
                        val config = arrayOfNulls<EGLConfig>(1)
                        val count = IntArray(1)
                        val attributes = intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                            EGL14.EGL_NONE)
                        check(EGL14.eglInitialize(egl, count, 0, IntArray(1), 0))
                        check(EGL14.eglChooseConfig(egl, attributes, 0, config, 0, 1, count, 0) && count[0] > 0)
                        context = EGL14.eglCreateContext(egl, config[0], EGL14.EGL_NO_CONTEXT,
                            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                        surface = EGL14.eglCreatePbufferSurface(egl, config[0],
                            intArrayOf(EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 64, EGL14.EGL_NONE), 0)
                        check(EGL14.eglMakeCurrent(egl, surface, surface, context))
                        capture = BakedCastGpuCapture(gl, 64, 64, overlay, { target ->
                            val canvas = target.surface.lockCanvas(null)
                            try {
                                canvas.drawColor(Color.RED)
                                canvas.drawRect(0f, 0f, 64f, 8f, android.graphics.Paint().apply { color = Color.BLUE })
                            } finally { target.surface.unlockCanvasAndPost(canvas) }
                        }, {})
                    }
                    capture!!.requestFrame({ placement ->
                        try {
                            capture!!.draw(placement)
                            GLES20.glReadPixels(0, 0, 64, 64, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
                            done.countDown()
                        } catch (failure: Exception) { failed(failure) }
                    }, ::failed)
                } catch (failure: Exception) { failed(failure) }
            }
            assertTrue("No GPU Cast test frame", done.await(10, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("GPU Cast capture failed", it) }
            return pixels
        }

        fun assertPixel(pixels: ByteBuffer, x: Int, y: Int, vararg expected: Int) {
            val start = ((63 - y) * 64 + x) * 4
            val actual = expected.indices.map { pixels.get(start + it).toInt() and 255 }
            if (expected.indices.any { kotlin.math.abs(expected[it] - actual[it]) > 4 }) {
                // Field diagnostics keep the pixel requirement exact and reveal a displaced layer.
                val points = (0 until 64 * 64).filter { (pixels.get(it * 4 + 1).toInt() and 255) > 40 }
                val bounds = if (points.isEmpty()) "absent" else {
                    val xs = points.minOf { it % 64 }..points.maxOf { it % 64 }
                    val ys = points.minOf { 63 - it / 64 }..points.maxOf { 63 - it / 64 }
                    "x=$xs y=$ys"
                }
                throw AssertionError("At ($x,$y) expected ${expected.toList()}, got $actual. Overlay $bounds")
            }
        }

        try {
            instrumentation.runOnMainSync {
                val view = ComposeView(activity).apply {
                    setContent {
                        Canvas(Modifier.fillMaxSize().recordBakedCastOverlay(overlay)) {
                            val isMoved = moved.value
                            drawRect(androidx.compose.ui.graphics.Color.Green.copy(alpha = 0.5f),
                                topLeft = Offset(size.width * if (isMoved) 0.5f else 0.25f,
                                    size.height * if (isMoved) 0.5625f else 0.125f),
                                size = Size(size.width * 0.25f, size.height * 0.25f))
                            if (isMoved) nextOverlay.countDown() else firstOverlay.countDown()
                        }
                    }
                }
                activity.root.addView(view, FrameLayout.LayoutParams(64, 64))
            }
            assertTrue(firstOverlay.await(10, TimeUnit.SECONDS))
            val first = read()
            assertPixel(first, 5, 5, 20, 30, 40)
            assertPixel(first, 36, 18, 0, 0, 255)
            assertPixel(first, 36, 30, 255, 0, 0)
            assertPixel(first, 20, 20, 127, 128, 0)
            assertPixel(first, 20, 10, 10, 143, 20)
            instrumentation.runOnMainSync { moved.value = true }
            assertTrue(nextOverlay.await(10, TimeUnit.SECONDS))
            val next = read()
            assertPixel(next, 20, 20, 255, 0, 0)
            assertPixel(next, 40, 42, 127, 128, 0)
        } finally {
            val closed = CountDownLatch(1)
            gl.post {
                try {
                    capture?.close()
                    EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(egl, context)
                    EGL14.eglTerminate(egl)
                } finally { closed.countDown(); thread.quitSafely() }
            }
            closed.await(5, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
