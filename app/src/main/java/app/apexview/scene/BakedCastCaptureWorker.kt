package app.apexview.scene

import android.content.Context
import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Window
import android.view.WindowManager
import app.apexview.cast.BakedCastLiveSession

/**
 * Encodes the authorized clear video scene and its live viewer layers on an
 * independent EGL looper. Never copy the controller window into the TV.
 * Failure to provide independent layers ends this Cast route explicitly.
 */
internal class BakedCastCaptureWorker(
    private val context: Context,
    private val window: Window,
    private val bounds: Rect,
    private val width: Int,
    private val height: Int,
    private val session: BakedCastLiveSession,
    private val overlay: BakedCastOverlay,
    private val drawVideo: (BakedCastGpuCapture.VideoTarget) -> Unit,
    private val detachVideo: (BakedCastGpuCapture.VideoTarget) -> Unit,
    private val playbackPositionMs: () -> Long?,
) {
    private val thread = HandlerThread("PitwallBakedCastEncoder",
        Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val gl = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private var cleaned = false
    private val graphicsOwnerId = SceneGraphicsOwners.process.newId()
    private fun recordGraphicsOwner() {
        SceneGraphicsOwners.process.record(graphicsOwnerId, SceneGraphicsOwners.Owner(
            "BakedCastWorker", 0, 0, if (contextGl != EGL14.EGL_NO_CONTEXT) 1 else 0,
            0, 0, 0, 0, 0, 0, pbuffers = if (pbuffer != EGL14.EGL_NO_SURFACE) 1 else 0))
    }
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var displayLease: DisplayLeaseRegistry.Lease<EGLDisplay>? = null
    private var contextGl: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoder: ReplayClipEncoder? = null
    private var directCapture: BakedCastGpuCapture? = null
    private val adaptivePacing = BakedCastCapturePacing.Controller()
    private val mediaClock = BakedCastMediaClock()
    private var frames = 0L
    private var maxCopyMs = 0L
    private var maxEncoderMs = 0L
    private var lastReportNs = 0L

    fun start() {
        gl.post {
            if (stopped) { cleanup(); return@post }
            try {
                require(width > 0 && height > 0 && bounds.width() > 0 && bounds.height() > 0)
                require(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) {
                    "Protected viewer cannot use the clear Cast encoder"
                }
                initialize()
                requestNext()
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    /** Cancel without blocking the main/decoder GL thread on codec teardown. */
    fun stop() {
        if (stopped) return
        stopped = true
        gl.post { cleanup() }
    }

    private fun initialize() {
        val acquired = SceneEglDisplays.acquire()
        displayLease = acquired
        display = acquired.display
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            0x3142, 1, // EGL_RECORDABLE_ANDROID for the MediaCodec input Surface.
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0)
            && count[0] > 0) { "No encoder-compatible EGL config" }
        val config = checkNotNull(configs[0])
        contextGl = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(contextGl != EGL14.EGL_NO_CONTEXT) { "Cannot create Cast EGL context" }
        recordGraphicsOwner()
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(pbuffer != EGL14.EGL_NO_SURFACE &&
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, contextGl)) {
            "Cannot bind Cast GL worker"
        }
        recordGraphicsOwner()
        encoder = ReplayClipEncoder(context, display, contextGl, config, width, height,
            castSample = session::videoSample, nativeRequested = session::nativeProcessingRequested)
        directCapture = BakedCastGpuCapture(gl, width, height, overlay, drawVideo, detachVideo)
        Log.i("PitwallCast", "BAKED_CAST_ENCODER_READY output=${width}x$height " +
            "thread=INDEPENDENT_EGL codec=AVC captureRequestsInFlight=1 timestampDomain=SOURCE_MEDIA")
    }

    private fun requestNext() {
        if (stopped) return
        // Media3's current position belongs to its application looper. The clear
        // commentary remains in source time, including during 1.5× playback.
        main.post {
            if (stopped) return@post
            val position = runCatching { playbackPositionMs() }.getOrNull()
            gl.post capture@ {
                if (stopped) return@capture
                when {
                    position == null -> fail(IllegalStateException("Clear Cast playback ended"))
                    mediaClock.canCapture(position) -> requestFrame(position)
                    else -> gl.postDelayed({ requestNext() }, BakedCastCapturePacing.delayMs(0, 0))
                }
            }
        }
    }

    private fun requestFrame(position: Long) {
        try {
            check(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) {
                "Viewer became protected during clear Cast"
            }
            val requestedAtNs = System.nanoTime()
            val direct = checkNotNull(directCapture) { "Independent Cast capture is unavailable" }
            direct.requestFrame({ placement ->
                submitFrame(position, requestedAtNs) { encoder, timestamp ->
                    encoder.frame(timestamp) { direct.draw(placement) }
                }
            }, { error -> fail(error) })
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun submitFrame(position: Long, requestedAtNs: Long, draw: (ReplayClipEncoder, Long) -> Unit) {
        if (stopped) return
        try {
            val layersReadyNs = System.nanoTime()
            maxCopyMs = maxOf(maxCopyMs, (layersReadyNs - requestedAtNs) / 1_000_000L)
            if (mediaClock.originMs == null) session.setOutputSize(width, height, position)
            draw(checkNotNull(encoder), mediaClock.timestampNs(position))
            frames++
            maxEncoderMs = maxOf(maxEncoderMs, (System.nanoTime() - layersReadyNs) / 1_000_000L)
            if (layersReadyNs - lastReportNs >= 5_000_000_000L) {
                val source = "DIRECT_EGL_WITH_VIEWER_OVERLAYS"
                Log.i("PitwallCast", "BAKED_CAST_CAPTURE_HEALTH source=$source frames=$frames " +
                    "senderVisibleVideoRequested=false maxLayerAcquireMs=$maxCopyMs maxEncodeMs=$maxEncoderMs " +
                    "rendererThread=INDEPENDENT_EGL physicalPresentation=NOT_MEASURED")
                lastReportNs = layersReadyNs
                frames = 0; maxCopyMs = 0; maxEncoderMs = 0
            }
            gl.postDelayed({ requestNext() }, adaptivePacing.nextDelayMs(requestedAtNs, System.nanoTime()))
        } catch (error: Exception) { fail(error) }
    }

    private fun fail(error: Exception) {
        if (stopped) { cleanup(); return }
        stopped = true
        val reason = (error.message ?: error.javaClass.simpleName).take(200)
        Log.w("PitwallCast", "BAKED_CAST_WORKER_FAILURE $reason")
        try { session.captureFailed(reason) } finally { cleanup() }
    }

    private fun cleanup() {
        if (cleaned) return
        cleaned = true
        val steps = SceneReleaseSteps { step, error ->
            Log.w("PitwallCast", "BAKED_CAST_WORKER_CLEANUP step=$step", error)
        }
        val oldCapture = directCapture
        val oldEncoder = encoder
        directCapture = null
        encoder = null
        try {
            steps.run("egl-bind") {
                if (display != EGL14.EGL_NO_DISPLAY && contextGl != EGL14.EGL_NO_CONTEXT &&
                    pbuffer != EGL14.EGL_NO_SURFACE) {
                    check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, contextGl))
                }
            }
            steps.run("capture") { oldCapture?.close() }
            steps.run("encoder") { oldEncoder?.stopCast() }
            if (display != EGL14.EGL_NO_DISPLAY) {
                steps.run("egl-unbind") {
                    check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
                }
                steps.run("egl-pbuffer") {
                    if (pbuffer != EGL14.EGL_NO_SURFACE) check(EGL14.eglDestroySurface(display, pbuffer))
                }
                steps.run("egl-context") {
                    if (contextGl != EGL14.EGL_NO_CONTEXT) check(EGL14.eglDestroyContext(display, contextGl))
                }
                steps.run("egl-display") {
                    val lease = displayLease.also { displayLease = null }
                    lease?.close()
                }
            }
        } finally {
            pbuffer = EGL14.EGL_NO_SURFACE
            contextGl = EGL14.EGL_NO_CONTEXT
            display = EGL14.EGL_NO_DISPLAY
            SceneGraphicsOwners.process.remove(graphicsOwnerId)
            thread.quitSafely()
        }
    }
}
