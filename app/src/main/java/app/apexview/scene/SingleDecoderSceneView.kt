package app.apexview.scene

import android.content.Context
import android.hardware.display.DisplayManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.PixelCopy
import android.view.Choreographer
import android.view.Window
import android.view.Surface
import android.view.TextureView
import app.apexview.cast.BakedCastLiveSession
import app.apexview.DisplayCadence
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One codec output texture, one visible output view. No per-window players or decoders.
 * Logical windows sample regions of the already composed frame, with the usual layout/z order.
 * This renderer is used only for verified CLEAR media. Protected video keeps its secure path.
 */
internal class SingleDecoderSceneView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("ApexviewSingleScene").apply { start() }
    private val gl = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var previousModeSignature: String? = null
    @Volatile private var selectedDisplayHz = 0f
    private val modeListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (rootView.display?.displayId == displayId) logDisplayMode("mode-changed")
        }
    }

    private fun logDisplayMode(reason: String) {
        val actualDisplay = rootView.display ?: return
        val mode = actualDisplay.mode
        selectedDisplayHz = mode.refreshRate
        val sourceFps = scene?.sourceFrameRate ?: 0.0
        val signature = "${actualDisplay.displayId}:${mode.modeId}:${mode.refreshRate}:$sourceFps"
        if (signature == previousModeSignature) return
        previousModeSignature = signature
        // An available mode is not a selected mode; setFrameRate() is only advice to SurfaceFlinger.
        val available = actualDisplay.supportedModes.joinToString(",") {
            "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}"
        }
        Log.i("ApexviewSingleDecoder", "SCENE_DISPLAY_MODE reason=$reason displayId=${actualDisplay.displayId} " +
            "activeMode=${mode.modeId} activeHz=${mode.refreshRate} sourceFps=$sourceFps " +
            "integerRefreshMultiple=${DisplayCadence.exactMultiple(mode.refreshRate, sourceFps)} " +
            "supportedModes=$available physicalPresentation=NOT_MEASURED")
    }
    private var player: ExoPlayer? = null
    @Volatile private var scene: VideoScene? = null
    @Volatile private var released = false
    @Volatile private var input: Surface? = null
    var onFailure: (String) -> Unit = {}
    var onFirstFrame: () -> Unit = {}
    private var firstFrameReported = false
    var onOutputSize: (Int, Int) -> Unit = { _, _ -> }
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var contextGl: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputSurface: Surface? = null
    private var hintedFrameRate = -1f
    private var config: EGLConfig? = null
    @Volatile private var clip: ReplayClipEncoder? = null
    private var clipResult: ((String) -> Unit)? = null
    @Volatile private var castEncoder: ReplayClipEncoder? = null
    @Volatile private var castSession: BakedCastLiveSession? = null

    /** Capture the ACTUAL viewer pixels (including timing/map), for a continuous TV stream. */
    fun startBakedCast(source: Window, bounds: Rect, session: BakedCastLiveSession, playbackPositionMs: Long) {
        gl.post {
            if (castSession === session) return@post
            stopBakedCastOnGl()
            try {
                check(clip == null) { "Stop replay recording before casting" }
                check(!released && hasFrame && EGL14.eglMakeCurrent(display, window, window, contextGl)) {
                    "Wait for the single-decoder replay picture before casting"
                }
                check(source.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE == 0) {
                    "Protected video cannot be copied to an unprotected Cast stream"
                }
                val scale = minOf(1.0, 1920.0 / maxOf(bounds.width(), bounds.height()))
                val width = ((bounds.width() * scale).toInt() and -2).coerceAtLeast(2)
                val height = ((bounds.height() * scale).toInt() and -2).coerceAtLeast(2)
                val encoder = ReplayClipEncoder(context.applicationContext, display, contextGl, checkNotNull(config), width, height,
                    castSample = session::videoSample)
                castEncoder = encoder; castSession = session
                session.setOutputSize(width, height, playbackPositionMs)
                copyBakedFrame(source, bounds, encoder, session)
            } catch (error: Exception) {
                session.captureFailed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun copyBakedFrame(source: Window, bounds: Rect, encoder: ReplayClipEncoder, session: BakedCastLiveSession) {
        val requestedAt = System.nanoTime()
        post {
            if (released || castEncoder !== encoder || castSession !== session) return@post
            try {
                check(source.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE == 0)
                PixelCopy.request(source, Rect(bounds), encoder.pixels, { status ->
                    if (castEncoder === encoder && castSession === session && !released) {
                        try {
                            check(status == PixelCopy.SUCCESS) { "PixelCopy $status" }
                            encoder.frame(requestedAt)
                            val delayMs = (40L - (System.nanoTime() - requestedAt) / 1_000_000L).coerceAtLeast(0L)
                            gl.postDelayed({ if (castEncoder === encoder) copyBakedFrame(source, bounds, encoder, session) }, delayMs)
                        } catch (error: Exception) {
                            stopBakedCastOnGl(); session.captureFailed(error.message ?: error.javaClass.simpleName)
                        } finally { EGL14.eglMakeCurrent(display, window, window, contextGl) }
                    }
                }, gl)
            } catch (error: Exception) {
                gl.post { stopBakedCastOnGl(); session.captureFailed(error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    fun stopBakedCast(session: BakedCastLiveSession? = null) {
        gl.post { if (session == null || castSession === session) stopBakedCastOnGl() }
    }

    private fun stopBakedCastOnGl() {
        castEncoder?.stopCast(); castEncoder = null; castSession = null
    }

    fun startReplayClip(source: Window, bounds: Rect, result: (String) -> Unit) {
        gl.post {
            if (clip != null) return@post
            try {
                check(!released && hasFrame && EGL14.eglMakeCurrent(display, window, window, contextGl)) {
                    "Video needs to be playing before making a replay clip"
                }
                val scale = minOf(1.0, 1920.0 / maxOf(bounds.width(), bounds.height()))
                val width = ((bounds.width() * scale).toInt() and -2).coerceAtLeast(2)
                val height = ((bounds.height() * scale).toInt() and -2).coerceAtLeast(2)
                val encoder = ReplayClipEncoder(context.applicationContext, display, contextGl, checkNotNull(config), width, height)
                clip = encoder
                clipResult = result
                copyReplayFrame(source, bounds, encoder)
            } catch (error: Exception) {
                main.post { result("Could not start replay clip: ${error.message ?: error.javaClass.simpleName}") }
            }
        }
    }

    private fun copyReplayFrame(source: Window, bounds: Rect, encoder: ReplayClipEncoder) {
        val requestedAt = System.nanoTime()
        post {
            if (released || clip !== encoder) return@post
            try {
                check(source.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE == 0) {
                    "The viewer window became protected"
                }
                PixelCopy.request(source, Rect(bounds), encoder.pixels, { status ->
                    if (clip === encoder && !released) {
                        try {
                            check(status == PixelCopy.SUCCESS) { "Viewer capture failed (PixelCopy $status)" }
                            encoder.frame(requestedAt)
                            val delayMs = (40L - (System.nanoTime() - requestedAt) / 1_000_000L).coerceAtLeast(0L)
                            gl.postDelayed({ if (clip === encoder) copyReplayFrame(source, bounds, encoder) }, delayMs)
                        } catch (error: Exception) {
                            stopClip("Replay clip failed: ${error.message ?: error.javaClass.simpleName}")
                        } finally {
                            EGL14.eglMakeCurrent(display, window, window, contextGl)
                        }
                    }
                }, gl)
            } catch (error: Exception) {
                gl.post {
                    if (clip === encoder) stopClip("Replay clip failed: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    fun finishReplayClip() {
        gl.post { saveClip() }
    }

    private fun saveClip() {
        val encoder = clip ?: return
        val message = try {
            encoder.finish()
        } catch (error: Exception) {
            "Could not save replay recording: ${error.message ?: error.javaClass.simpleName}"
        }
        stopClip(message)
    }

    private fun stopClip(message: String) {
        clip?.close()
        clip = null
        val result = clipResult
        clipResult = null
        main.post { result?.invoke(message) }
    }

    private var decoderTexture: SurfaceTexture? = null
    private var texture = 0
    private var program = 0
    private var imageUniform = -1
    private var transformUniform = -1
    private var positionAttribute = -1
    private var uvAttribute = -1
    private var hasFrame = false
    private var outputWidth = 0
    private var outputHeight = 0
    private val transform = FloatArray(16)
    private var cachedModel: VideoScene? = null
    private var cachedQuads: List<Pair<java.nio.FloatBuffer, java.nio.FloatBuffer>> = emptyList()
    private var failed = false
    private var rendered = 0L
    private var lastLogNs = 0L
    private val frameDemand = SceneFrameDemand()
    private val pacing = SceneFramePacing()
    private val cadenceGate = SceneFrameCadenceGate()
    private var displayClock: Choreographer? = null
    private val displayFrame: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) = guarded {
            val callbackStarted = System.nanoTime()
            if (frameDemand.hasPendingVideo() && cadenceGate.shouldDefer(
                    frameTimeNs, scene?.sourceFrameRate ?: 0.0, selectedDisplayHz)) {
                pacing.onPacingDeferred()
                Trace.beginSection("PitwallPacingDefer")
                try { displayClock?.postFrameCallback(this) } finally { Trace.endSection() }
                return@guarded
            }
            val newVideo = frameDemand.beginFrame()
            val timing = draw()
            if (timing != null) {
                if (newVideo) cadenceGate.onVideoDraw(frameTimeNs)
                pacing.onDisplayFrame(frameTimeNs, timing.swapNs, newVideo, timing.glDrawNs, callbackStarted)
            }
        }
    }

    /** Swap on display vsync; decoding does not wait for a display callback to release buffers. */
    private fun scheduleDisplayFrame(videoArrived: Boolean = false) {
        val schedule = if (videoArrived) frameDemand.videoArrived() else frameDemand.requestDraw()
        if (schedule) {
            val clock = displayClock ?: Choreographer.getInstance().also { displayClock = it }
            clock.postFrameCallback(displayFrame)
        }
    }

    /** Allow the display to use a seamless 50/100 Hz mode for 50 fps content when supported.
     * Never request a disruptive/non-seamless switch during playback or rotation. */
    private fun applyOutputFrameRateHint() {
        if (Build.VERSION.SDK_INT < 30) return
        val surface = outputSurface ?: return
        val fps = SceneFrameRatePolicy.hint(scene?.sourceFrameRate ?: 0.0)
        if (fps == hintedFrameRate) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            hintedFrameRate = fps
            Log.i("ApexviewSingleDecoder", "SCENE_FRAME_RATE_HINT fps=$fps strategy=SEAMLESS_ONLY " +
                "displayRefreshRateChange=NOT_VERIFIED")
        } catch (error: RuntimeException) {
            // Optional display policy advice must not interrupt the verified decoder path.
            Log.w("ApexviewSingleDecoder", "SCENE_FRAME_RATE_HINT_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    init { surfaceTextureListener = this; isOpaque = true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        displayManager.registerDisplayListener(modeListener, main)
        logDisplayMode("attach")
    }

    override fun onDetachedFromWindow() {
        displayManager.unregisterDisplayListener(modeListener)
        previousModeSignature = null
        super.onDetachedFromWindow()
    }

    fun bind(nextPlayer: ExoPlayer, nextScene: VideoScene) {
        val changed = scene != nextScene
        scene = nextScene
        if (player !== nextPlayer) {
            input?.let { player?.clearVideoSurface(it) }
            player = nextPlayer
            input?.let { nextPlayer.setVideoSurface(it) }
        }
        if (changed) {
            main.post { if (!released) logDisplayMode("source-changed") }
            gl.post { guarded { applyOutputFrameRateHint(); scheduleDisplayFrame() } }
        }
    }

    override fun onSurfaceTextureAvailable(output: SurfaceTexture, width: Int, height: Int) {
        outputWidth = width; outputHeight = height
        onOutputSize(width, height)
        gl.post { guarded { initialize(output); draw() } }
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        outputWidth = width; outputHeight = height
        onOutputSize(width, height)
        gl.post { guarded { scheduleDisplayFrame() } }
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        input?.let { player?.clearVideoSurface(it) }
        // GL owns the Surface reference until queued destruction releases it. Clearing it
        // here lost that reference before destroy() and leaked a codec output surface.
        gl.post { destroy(); surface.release() }
        return false
    }

    fun release() {
        if (released) return
        released = true
        input?.let { player?.clearVideoSurface(it) }
        player = null
        surfaceTextureListener = null
        val output = surfaceTexture
        gl.post { destroy(); output?.release(); thread.quitSafely() }
    }

    private fun initialize(output: SurfaceTexture) {
        destroy()
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "EGL initialization failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8)
        fun choose(extra: IntArray) = EGL14.eglChooseConfig(display,
            attributes + extra + EGL14.EGL_NONE, 0, configs, 0, 1, count, 0) && count[0] > 0
        check(choose(intArrayOf(0x3142, 1)) || choose(intArrayOf())) { "No GLES2 configuration" }
        val selected = checkNotNull(configs[0])
        config = selected
        contextGl = EGL14.eglCreateContext(display, selected, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val displaySurface = Surface(output)
        outputSurface = displaySurface
        window = EGL14.eglCreateWindowSurface(display, selected, displaySurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, window, window, contextGl)) { "Unable to bind scene output" }
        // Display callbacks already limit swaps to one per vsync. A second, blocking
        // EGL swap interval can hold this same GL thread while codec frames wait to be
        // latched. Let the platform reject interval zero if the driver requires one.
        val asynchronousSwap = EGL14.eglSwapInterval(display, 0)
        Log.i("ApexviewSingleDecoder", "SCENE_SWAP_INTERVAL requested=0 accepted=$asynchronousSwap pacing=CHOREOGRAPHER")
        applyOutputFrameRateHint()
        fun shader(type: Int, code: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, code); GLES20.glCompileShader(id)
            val compiled = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { "Scene shader compilation failed" }; return id
        }
        // Matching varying precision is required by some GLES2 linkers. Prefer highp so a
        // wide atlas retains its half-texel camera boundary at 4K; GLES2 may lack fragment highp.
        val precisionBits = IntArray(1)
        GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, IntArray(2), 0, precisionBits, 0)
        val precision = if (precisionBits[0] > 0) "highp" else "mediump"
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 p; attribute vec2 uv; uniform mat4 st; varying $precision vec2 t; void main(){gl_Position=vec4(p,0.,1.);t=(st*vec4(uv,0.,1.)).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision $precision float; uniform samplerExternalOES image; varying $precision vec2 t; void main(){gl_FragColor=texture2D(image,t);}")
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
        val linked = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "Scene shader link failed" }
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        imageUniform = GLES20.glGetUniformLocation(program, "image")
        transformUniform = GLES20.glGetUniformLocation(program, "st")
        positionAttribute = GLES20.glGetAttribLocation(program, "p")
        uvAttribute = GLES20.glGetAttribLocation(program, "uv")
        check(imageUniform >= 0 && transformUniform >= 0 && positionAttribute >= 0 && uvAttribute >= 0) {
            "Scene shader input locations are unavailable"
        }
        val textures = IntArray(1); GLES20.glGenTextures(1, textures, 0); texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val frames = SurfaceTexture(texture)
        decoderTexture = frames
        scene?.let { frames.setDefaultBufferSize(it.atlasWidth, it.atlasHeight) }
        frames.setOnFrameAvailableListener({
            guarded {
                if (decoderTexture === frames && !released) {
                    val callbackStarted = System.nanoTime()
                    Trace.beginSection("PitwallDecoderFrameCallback")
                    try {
                    // A decoder can exhaust its output BufferQueue while the EGL display
                    // swap is blocked. Latch promptly to release the prior codec buffer;
                    // coalesce only the *drawing* onto a Choreographer vsync.
                    val bindStarted = System.nanoTime()
                    check(EGL14.eglMakeCurrent(display, window, window, contextGl)) { "Scene GL context unavailable" }
                    val eglBindNs = System.nanoTime() - bindStarted
                    val latchStarted = System.nanoTime()
                    Trace.beginSection("PitwallTextureLatch")
                    try { frames.updateTexImage() } finally { Trace.endSection() }
                    frames.getTransformMatrix(transform)
                    hasFrame = true
                    rendered++
                    val latchedAt = System.nanoTime()
                    pacing.onTextureLatched(latchedAt, latchedAt - latchStarted, frames.timestamp,
                        eglBindNs, callbackArrivalNs = callbackStarted)
                    scheduleDisplayFrame(videoArrived = true)
                    } finally { Trace.endSection() }
                }
            }
        }, gl)
        val surface = Surface(frames); input = surface
        post { if (!released && input === surface) player?.setVideoSurface(surface) }
    }

    private data class DrawTiming(val glDrawNs: Long, val swapNs: Long)

    private fun draw(): DrawTiming? {
        val model = scene ?: return null
        if (window == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, window, window, contextGl)) return null
        val drawStarted = System.nanoTime()
        Trace.beginSection("PitwallGlDraw")
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        try { if (hasFrame) drawVideo(model) } finally { Trace.endSection() }
        val swapStarted = System.nanoTime()
        Trace.beginSection("PitwallEglSwap")
        try { check(EGL14.eglSwapBuffers(display, window)) { "Scene output swap failed" } }
        finally { Trace.endSection() }
        val swapCompleted = System.nanoTime()
        if (hasFrame && !firstFrameReported) {
            firstFrameReported = true
            post { if (!released) onFirstFrame() }
        }
        val now = System.nanoTime()
        if (now - lastLogNs >= 5_000_000_000L) {
            val metrics = pacing.snapshotAndReset()
            Log.i("ApexviewSingleDecoder", "SINGLE_DECODER_SCENE output=${outputWidth}x$outputHeight inputTextures=1 outputViews=1 sources=${model.sources} windows=${model.windows.size} frames=$rendered " +
                "pacingLatched=${metrics.latched} pacingPresented=${metrics.presented} " +
                "callbackGaps45ms=${metrics.callbackGaps} displayGaps45ms=${metrics.displayGaps} " +
                "slowSwaps18ms=${metrics.slowSwaps} maxCallbackGapMs=${metrics.maxCallbackGapMs} " +
                "maxDisplayGapMs=${metrics.maxDisplayGapMs} maxSwapMs=${metrics.maxSwapMs} " +
                "slowDraws18ms=${metrics.slowDraws} maxGlDrawMs=${metrics.maxDrawMs} " +
                "slowLatches18ms=${metrics.slowLatches} maxLatchMs=${metrics.maxLatchMs} " +
                "maxVsyncCallbackDelayMs=${metrics.maxVsyncCallbackDelayMs} " +
                "surfaceTimestampRegressions=${metrics.surfaceTimestampRegressions} " +
                "drawsWithoutNewVideo=${metrics.noNewVideoDraws} " +
                "videoDrawPacingDeferrals=${metrics.pacingDeferrals} " +
                "callbackIntervalsBins13_19_23_30_45_100=${metrics.callbackIntervals.compact()} " +
                "sourceTimestampBins13_19_23_30_45_100=${metrics.textureTimestampIntervals.compact()} " +
                "videoVsyncBins13_19_23_30_45_100=${metrics.displayIntervals.compact()} " +
                "allVsyncBins13_19_23_30_45_100=${metrics.allVsyncIntervals.compact()} " +
                "eglBindMaxMs=${metrics.eglBindDurations.maxMs.toInt()} " +
                "latchP95UpperMs=${metrics.latchDurations.percentileUpperMs(95)} " +
                "glSubmitP95UpperMs=${metrics.glDrawDurations.percentileUpperMs(95)} " +
                "eglSwapP95UpperMs=${metrics.eglSwapDurations.percentileUpperMs(95)} " +
                "choreographerDelayP95UpperMs=${metrics.callbackDelays.percentileUpperMs(95)} " +
                "gpuSubmitNotGpuCompletion=true physicalPresentation=NOT_MEASURED")
            lastLogNs = now
        }
        return DrawTiming(swapStarted - drawStarted, swapCompleted - swapStarted)
    }

    private fun drawVideo(model: VideoScene) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(imageUniform, 0)
        GLES20.glUniformMatrix4fv(transformUniform, 1, false, transform, 0)
        GLES20.glEnableVertexAttribArray(positionAttribute); GLES20.glEnableVertexAttribArray(uvAttribute)
        if (cachedModel != model) {
            fun buffer(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
            cachedQuads = model.quads().map { buffer(it.positions) to buffer(it.texture) }
            cachedModel = model
        }
        for ((positions, coordinates) in cachedQuads) {
            GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glVertexAttribPointer(uvAttribute, 2, GLES20.GL_FLOAT, false, 0, coordinates)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    private fun guarded(action: () -> Unit) {
        if (released || failed) return
        try { action() } catch (error: Exception) {
            failed = true
            val reason = "Single-decoder scene output: ${error.message ?: error.javaClass.simpleName}".take(200)
            Log.w("ApexviewSingleDecoder", reason)
            post { if (!released) onFailure(reason) }
        }
    }

    private fun destroy() {
        displayClock?.removeFrameCallback(displayFrame)
        frameDemand.reset()
        cadenceGate.reset()
        pacing.reset()
        stopBakedCastOnGl()
        saveClip()
        config = null
        decoderTexture?.setOnFrameAvailableListener(null)
        input?.release(); input = null
        decoderTexture?.release(); decoderTexture = null; hasFrame = false
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (contextGl != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, contextGl)
            EGL14.eglTerminate(display)
        }
        outputSurface?.release(); outputSurface = null
        hintedFrameRate = -1f
        display = EGL14.EGL_NO_DISPLAY; contextGl = EGL14.EGL_NO_CONTEXT; window = EGL14.EGL_NO_SURFACE
        texture = 0; program = 0
        imageUniform = -1; transformUniform = -1; positionAttribute = -1; uvAttribute = -1
    }
}
