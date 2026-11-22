package app.apexview.scene

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.Choreographer
import android.os.Trace
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One codec output texture, one visible output view. No per-window players/decoders and no encoder.
 * Logical windows sample regions of the already composed frame, with the usual layout/z order.
 * Protected buffers remain inside a protected EGL context and secure output SurfaceView.
 * No framebuffer readback, screenshot, decrypted samples or insecure output fallback exists.
 */
internal class ProtectedDecoderSceneView(context: Context, private val protectedOutput: Boolean = true) : SurfaceView(context), SurfaceHolder.Callback {
    // Prioritize video surface latching above archive composition and Compose
    // overlay work; this is one GL output thread, never another video decoder.
    private val thread = HandlerThread(
        "PitwallSecureScene", android.os.Process.THREAD_PRIORITY_DISPLAY
    ).apply { start() }
    private val gl = Handler(thread.looper)
    private var player: ExoPlayer? = null
    @Volatile private var playbackSpeed = 1f
    @Volatile private var scene: VideoScene? = null
    @Volatile private var released = false
    @Volatile private var input: Surface? = null
    var onFailure: (String) -> Unit = {}
    var onFirstFrame: () -> Unit = {}
    var onOutputLost: () -> Unit = {}
    private val outputReadiness = SceneOutputReadiness()
    private var firstFrameReported = false
    var onOutputSize: (Int, Int) -> Unit = { _, _ -> }
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var contextGl: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var decoderTexture: SurfaceTexture? = null
    private var texture = 0
    private var program = 0
    private var imageUniform = -1
    private var textureMatrixUniform = -1
    private var positionAttribute = -1
    private var texcoordAttribute = -1
    private var hasFrame = false
    private var outputWidth = 0
    private var outputHeight = 0
    private val transform = FloatArray(16)
    private var cachedModel: VideoScene? = null
    private var cachedQuads: List<Pair<java.nio.FloatBuffer, java.nio.FloatBuffer>> = emptyList()
    private var failed = false
    private var rendered = 0L
    private var lastLogNs = 0L
    // A one-second GL-looper scheduling probe distinguishes a delayed callback
    // from a fast GPU swap. It samples once per second, never once per frame.
    private var nextGlProbeExpectedNs = 0L
    private val glQueueProbe = object : Runnable {
        override fun run() {
            if (released) return
            val now = System.nanoTime()
            if (nextGlProbeExpectedNs != 0L)
                pacing.onGlQueueProbe((now - nextGlProbeExpectedNs).coerceAtLeast(0L))
            nextGlProbeExpectedNs = now + 1_000_000_000L
            gl.postDelayed(this, 1_000L)
        }
    }

    private var appliedFrameRateHint = Float.NaN
    private var lastPresentedAtNs = 0L
    private var lastSourceTimestampNs = 0L
    // Share the same camera-driven, 50fps-aware scheduling and frame metrics
    // as the clear HEVC archive; the independent SurfaceView remains distinct.
    private val frameDemand = SceneFrameDemand()
    private val cadenceGate = SceneFrameCadenceGate()
    private val pacing = SceneFramePacing()
    private var displayClock: Choreographer? = null
    private val displayFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) = guarded {
            val callbackStartNs = System.nanoTime()
            val refreshHz = rootView.display?.refreshRate ?: 60f
            if (frameDemand.hasPendingVideo() && cadenceGate.shouldDefer(
                frameTimeNanos, scene?.sourceFrameRate ?: 0.0, refreshHz, playbackSpeed)) {
                pacing.onPacingDeferred()
                displayClock?.postFrameCallback(this)
                return@guarded
            }
            val hasNewVideo = frameDemand.beginFrame()
            val measurement = draw()
            if (measurement != null) {
                if (hasNewVideo) cadenceGate.onVideoDraw(frameTimeNanos)
                pacing.onDisplayFrame(frameTimeNanos, measurement.swapNs,
                    hasNewVideo, measurement.drawNs, callbackStartNs)
            }
        }
    }
    private fun scheduleVideoFrame(newVideo: Boolean = false) {
        val schedule = if (newVideo) frameDemand.videoArrived() else frameDemand.requestDraw()
        if (schedule) {
            val clock = displayClock ?: Choreographer.getInstance().also { displayClock = it }
            clock.postFrameCallback(displayFrame)
        }
    }
    private var intervalPresented = 0L
    private var intervalLongGaps = 0L
    private var intervalSwapsOver18ms = 0L
    private var intervalLongestGapMs = 0L
    private var intervalLongestSwapMs = 0L
    private var intervalSourceTimestampRegressions = 0L

    init {
        if (protectedOutput) setSecure(true)
        holder.addCallback(this)
        gl.post(glQueueProbe)
    }

    fun bind(nextPlayer: ExoPlayer, nextScene: VideoScene,
             speed: Float = 1f) {
        val changed = scene != nextScene
        val firstBinding = scene == null
        val atlasChanged = scene?.sameEncodedAtlasAs(nextScene) == false
        if (atlasChanged) {
            // Layout-only window changes can reuse the already latched atlas;
            // only a different coded picture must await a fresh decoder frame.
            outputReadiness.invalidate()
            onOutputLost()
        }
        playbackSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        scene = nextScene
        if (!protectedOutput && holder.surface.isValid) updateFrameRateHint(holder.surface)
        if (player !== nextPlayer) {
            input?.let { player?.clearVideoSurface(it) }
            player = nextPlayer
            input?.let { nextPlayer.setVideoSurface(it) }
        }
        if (changed) gl.post { guarded {
            if (atlasChanged) {
                // New encoded atlas or crop mapping cannot reuse old input pixels.
                hasFrame = false
                firstFrameReported = false
            }
            if (firstBinding || atlasChanged) {
                decoderTexture?.setDefaultBufferSize(nextScene.atlasWidth, nextScene.atlasHeight)
            }
            scheduleVideoFrame()
        } }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val outputEpoch = outputReadiness.invalidate()
        onOutputLost()
        val surface = holder.surface
        gl.post {
            // Android can destroy and recreate a surface before this callback
            // reaches the GL looper. Never initialize an old or invalid output.
            if (!released && outputReadiness.generation() == outputEpoch && surface.isValid) {
                guarded { initialize(surface); scheduleVideoFrame() }
            }
        }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        outputWidth = width; outputHeight = height; onOutputSize(width, height)
        gl.post { guarded { scheduleVideoFrame() } }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        outputReadiness.invalidate()
        onOutputLost()
        detachOutput()
    }
    private fun detachOutput() {
        // All EGL objects are GL-looper-owned. Clear the player's video output
        // on its application thread, then queue GL destruction. Waiting for a
        // 2-second CountDownLatch here froze Compose/TV controls on rotation,
        // stop/start and Cast receiver handoff while eglSwapBuffers was busy.
        input?.let { player?.clearVideoSurface(it) }
        if (!gl.post { destroy() }) {
            Log.w("ApexviewSingleDecoder", "SECURE_EGL_RELEASE_QUEUE_CLOSED")
        }
    }
    fun release() {
        if (released) return
        released = true
        outputReadiness.invalidate()
        holder.removeCallback(this)
        detachOutput(); player = null; gl.removeCallbacks(glQueueProbe); thread.quitSafely()
    }

    private fun initialize(output: Surface) {
        destroy()
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "EGL initialization failed" }
        if (protectedOutput) check(EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
            ?.split(' ')?.contains("EGL_EXT_protected_content") == true) {
            "This device does not support protected GPU layout rendering at its active Widevine level"
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0) { "No GLES2 configuration" }
        val eglContextAttributes = if (protectedOutput) intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, 0x32C0, EGL14.EGL_TRUE,
            EGL14.EGL_NONE
        ) else intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val eglSurfaceAttributes = if (protectedOutput) intArrayOf(
            0x32C0, EGL14.EGL_TRUE, EGL14.EGL_NONE
        ) else intArrayOf(EGL14.EGL_NONE)
        contextGl = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            eglContextAttributes, 0)
        window = EGL14.eglCreateWindowSurface(display, configs[0], output,
            eglSurfaceAttributes, 0)
        check(EGL14.eglMakeCurrent(display, window, window, contextGl)) {
            "Unable to bind scene output"
        }
        if (protectedOutput) check(GLES20.glGetString(GLES20.GL_EXTENSIONS)
            ?.split(' ')?.contains("GL_EXT_protected_textures") == true) {
            "Protected texture sampling is unavailable; no insecure output fallback is permitted"
        }
        // SurfaceView has a SurfaceFlinger layer independent of the app's HWUI
        // RenderThread. Compose tower/map jank must never stall video presentation.
        // EGL swaps are paced by the native surface instead of a second
        // Choreographer+TextureView pass through the app window.
        // A 50fps source played at 1.5x delivers 75fps. Do not retain a stale
        // 50Hz Surface hint while the playback rate has changed.
        updateFrameRateHint(output)
        Log.i("ApexviewSingleDecoder", "SURFACE_SCENE_EGL_READY protected=$protectedOutput " +
            "outputLayer=SurfaceView compositor=SurfaceFlinger layoutFramesIndependentOfHWUI=true")
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
        textureMatrixUniform = GLES20.glGetUniformLocation(program, "st")
        positionAttribute = GLES20.glGetAttribLocation(program, "p")
        texcoordAttribute = GLES20.glGetAttribLocation(program, "uv")
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
                    val latchStartNs = System.nanoTime()
                    Trace.beginSection("PitwallProtectedSceneLatch")
                    try { frames.updateTexImage() } finally { Trace.endSection() }
                    val timestamp = frames.timestamp
                    pacing.onTextureLatched(System.nanoTime(), System.nanoTime() - latchStartNs,
                        timestamp, callbackArrivalNs = latchStartNs)
                    if (lastSourceTimestampNs > 0L && timestamp > 0L &&
                        timestamp < lastSourceTimestampNs) {
                        intervalSourceTimestampRegressions++
                    }
                    if (timestamp > 0L) lastSourceTimestampNs = timestamp
                    frames.getTransformMatrix(transform)
                    hasFrame = true
                    rendered++
                    scheduleVideoFrame(newVideo = true)
                }
            }
        }, gl)
        val surface = Surface(frames); input = surface
        post { if (!released && input === surface) player?.setVideoSurface(surface) }
    }

    private fun updateFrameRateHint(output: Surface) {
        if (protectedOutput || android.os.Build.VERSION.SDK_INT < 30) return
        val rate = SceneFrameRatePolicy.hint(scene?.sourceFrameRate ?: 0.0, playbackSpeed)
        if (rate == appliedFrameRateHint) return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                output.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                output.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            appliedFrameRateHint = rate
        }.onFailure {
            Log.w("ApexviewSingleDecoder",
                "SURFACE_SCENE_FPS_HINT_UNAVAILABLE " + it.javaClass.simpleName)
        }
    }

    private data class SurfaceFrameTiming(val drawNs: Long, val swapNs: Long)

    private fun draw(): SurfaceFrameTiming? {
        val model = scene ?: return null
        if (window == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, window, window, contextGl)) return null
        val drawStartNs = System.nanoTime()
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasFrame) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(imageUniform, 0)
            GLES20.glUniformMatrix4fv(textureMatrixUniform, 1, false, transform, 0)
            val p = positionAttribute; val uv = texcoordAttribute
            GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(uv)
            if (cachedModel != model) {
                fun buffer(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
                cachedQuads = model.quads().map { buffer(it.positions) to buffer(it.texture) }
                cachedModel = model
            }
            for ((positions, coordinates) in cachedQuads) {
                GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, positions)
                GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coordinates)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        }
        val swapStartedNs = System.nanoTime()
        Trace.beginSection("PitwallSurfaceSceneSwap")
        try { check(EGL14.eglSwapBuffers(display, window)) { "Scene output swap failed" } }
        finally { Trace.endSection() }
        val swapEndedNs = System.nanoTime()
        if (hasFrame) {
            val swapMs = (swapEndedNs - swapStartedNs) / 1_000_000L
            intervalLongestSwapMs = maxOf(intervalLongestSwapMs, swapMs)
            if (swapMs > 18L) intervalSwapsOver18ms++
            if (lastPresentedAtNs > 0L) {
                val gapMs = (swapEndedNs - lastPresentedAtNs) / 1_000_000L
                intervalLongestGapMs = maxOf(intervalLongestGapMs, gapMs)
                if (gapMs > 45L) intervalLongGaps++
            }
            lastPresentedAtNs = swapEndedNs
            intervalPresented++
        }
        if (hasFrame && !firstFrameReported) {
            firstFrameReported = true
            val surfaceEpoch = outputReadiness.generation()
            post {
                if (!released && outputReadiness.acknowledgeSubmittedFrame(surfaceEpoch)) {
                    Log.i("ApexviewSingleDecoder",
                        "SCENE_FIRST_GL_OUTPUT renderer=SurfaceView epoch=$surfaceEpoch " +
                            "physicalPresentation=NOT_MEASURED")
                    onFirstFrame()
                }
            }
        }
        val now = System.nanoTime()
        if (now - lastLogNs >= 5_000_000_000L) {
            val metrics = pacing.snapshotAndReset()
            Log.i("ApexviewSingleDecoder", "SURFACE_DECODER_SCENE " +
                "protected=$protectedOutput output=${outputWidth}x$outputHeight " +
                "inputTextures=1 outputViews=1 sources=${model.sources} " +
                "windows=${model.windows.size} decoderFrames=$rendered " +
                "sourceFps=${model.sourceFrameRate} speed=$playbackSpeed " +
                "effectiveFps=${model.sourceFrameRate * playbackSpeed} " +
                "intervalPresented=$intervalPresented intervalGaps45ms=$intervalLongGaps " +
                "intervalSwapsOver18ms=$intervalSwapsOver18ms " +
                "maxGapMs=$intervalLongestGapMs maxSwapMs=$intervalLongestSwapMs " +
                "sourceTimestampRegressions=$intervalSourceTimestampRegressions " +
                "pacingLatched=${metrics.latched} pacingPresented=${metrics.presented} " +
                "callbackGaps45ms=${metrics.callbackGaps} displayGaps45ms=${metrics.displayGaps} " +
                "slowSwaps18ms=${metrics.slowSwaps} slowDraws18ms=${metrics.slowDraws} " +
                "slowLatches18ms=${metrics.slowLatches} maxLatchMs=${metrics.maxLatchMs} " +
                "maxDrawMs=${metrics.maxDrawMs} maxVsyncDelayMs=${metrics.maxVsyncCallbackDelayMs} " +
                "glQueueDelayBins=${metrics.glQueueDelays.compact()} " +
                "maxCallbackGapMs=${metrics.maxCallbackGapMs} " +
                "maxDisplayGapMs=${metrics.maxDisplayGapMs} maxSwapMs=${metrics.maxSwapMs} " +
                "videoDrawPacingDeferrals=${metrics.pacingDeferrals} " +
                "videoVsyncBins13_19_23_30_45_100=${metrics.displayIntervals.compact()} " +
                "physicalPresentation=NOT_MEASURED")
            lastLogNs = now
            intervalPresented = 0
            intervalLongGaps = 0
            intervalSwapsOver18ms = 0
            intervalLongestGapMs = 0
            intervalLongestSwapMs = 0
            intervalSourceTimestampRegressions = 0
        }
        return SurfaceFrameTiming(swapStartedNs - drawStartNs,
            swapEndedNs - swapStartedNs)
    }

    private fun guarded(action: () -> Unit) {
        if (released || failed) return
        try { action() } catch (error: Exception) {
            failed = true
            val reason = "Protected scene output: ${error.message ?: error.javaClass.simpleName}".take(200)
            Log.w("ApexviewSingleDecoder", reason)
            post { if (!released) onFailure(reason) }
        }
    }

    private fun destroy() {
        displayClock?.removeFrameCallback(displayFrame)
        displayClock = null
        frameDemand.reset()
        cadenceGate.reset()
        pacing.reset()
        nextGlProbeExpectedNs = 0L
        decoderTexture?.setOnFrameAvailableListener(null)
        input?.release(); input = null
        decoderTexture?.release(); decoderTexture = null; hasFrame = false
        firstFrameReported = false
        lastPresentedAtNs = 0L
        lastSourceTimestampNs = 0L
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (contextGl != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, contextGl)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY; contextGl = EGL14.EGL_NO_CONTEXT; window = EGL14.EGL_NO_SURFACE
        texture = 0; program = 0
        appliedFrameRateHint = Float.NaN
        imageUniform = -1; textureMatrixUniform = -1
        positionAttribute = -1; texcoordAttribute = -1
    }
}
