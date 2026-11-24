package app.apexview.scene

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
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
        if (protectedOutput) "PitwallSecureScene" else "PitwallClearSurfaceScene",
        android.os.Process.THREAD_PRIORITY_DISPLAY
    ).apply { start() }
    private val gl = Handler(thread.looper)
    private val displayHandler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var previousModeSignature: String? = null
    @Volatile private var selectedDisplayHz = 0f
    private val outputPower = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    private var lastThermalReadMs = Long.MIN_VALUE
    private var cachedThermal = Int.MAX_VALUE

    /** GL-looper only; refresh at most once per second, never query Binder per frame. */
    private fun thermalForOutput(): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastThermalReadMs == Long.MIN_VALUE || now < lastThermalReadMs ||
            now - lastThermalReadMs >= 1000L) {
            cachedThermal = runCatching { outputPower?.currentThermalStatus }
                .getOrNull() ?: Int.MAX_VALUE
            lastThermalReadMs = now
        }
        return cachedThermal
    }
    private val modeListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (rootView.display?.displayId == displayId) {
                logDisplayMode("mode-changed")
                gl.post { if (!released) frameRateOutput?.let(::updateFrameRateHint) }
            }
        }
    }

    private fun logDisplayMode(reason: String) {
        val current = rootView.display ?: return
        val mode = current.mode
        selectedDisplayHz = current.refreshRate
        onDisplaySelected(current.displayId)
        val sourceFps = scene?.sourceFrameRate ?: 0.0
        val signature = "${current.displayId}:${mode.modeId}:${mode.refreshRate}:$selectedDisplayHz:$sourceFps:$playbackSpeed"
        if (signature == previousModeSignature) return
        previousModeSignature = signature
        val modes = current.supportedModes
        val available = modes.joinToString(",") {
            "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}"
        }
        val active = SceneFrameRatePolicy.Mode(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
        val seamlessMatches = SceneFrameRatePolicy.matchingSeamlessModes(
            active, modes.map { SceneFrameRatePolicy.Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) },
            sourceFps, playbackSpeed
        ).joinToString(",") { "${it.id}@${it.refreshHz}" }.ifBlank { "none" }
        Log.i("ApexviewSingleDecoder", "SURFACE_SCENE_DISPLAY_MODE reason=$reason protected=$protectedOutput " +
            "displayId=${current.displayId} activeMode=${mode.modeId} activeHz=${mode.refreshRate} displayRefreshHz=$selectedDisplayHz " +
            "sourceFps=$sourceFps speed=$playbackSpeed " +
            "effectiveFps=${SceneFrameRatePolicy.hint(sourceFps, playbackSpeed)} " +
            "outputHintFps=${SceneFrameRatePolicy.composedOutputHint(sourceFps, playbackSpeed,
                selectedDisplayHz, runCatching { outputPower?.currentThermalStatus }.getOrNull() ?: Int.MAX_VALUE,
                 pipelineHealthy())} " +
            "integerRefreshMultiple=${SceneFrameRatePolicy.isIntegerRefreshMultiple(mode.refreshRate, sourceFps, playbackSpeed)} " +
            "supportedModes=$available sameResolutionIntegralModes=$seamlessMatches " +
            "modeSwitchIssuedByApp=false physicalPresentation=NOT_MEASURED")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        displayManager.registerDisplayListener(modeListener, displayHandler)
        logDisplayMode("attach")
    }

    override fun onDetachedFromWindow() {
        displayManager.unregisterDisplayListener(modeListener)
        previousModeSignature = null
        onDisplaySelected(null)
        super.onDetachedFromWindow()
    }

    private var player: ExoPlayer? = null
    @Volatile private var playbackSpeed = 1f
    @Volatile private var scene: VideoScene? = null
    @Volatile private var released = false
    @Volatile private var input: Surface? = null

    /** Detach codec producer before releasing its SurfaceTexture consumer. */
    private var attachedCodecPlayer: ExoPlayer? = null
    private var attachedCodecSurface: Surface? = null
    @Volatile private var inputGeneration = -1L

    /** The Media3 application looper owns attach/detach, never the GL looper. */
    private fun detachDecoderInput(reason: String) {
        val old = attachedCodecSurface ?: return
        val owner = attachedCodecPlayer ?: return
        attachedCodecSurface = null
        attachedCodecPlayer = null
        codecCorrelation?.detach(old)
        owner.clearVideoSurface(old)
        Log.i("ApexviewSingleDecoder", "SCENE_CODEC_INPUT_DETACH " +
            "renderer=SurfaceView protected=$protectedOutput reason=$reason surfaceId=${System.identityHashCode(old)} " +
            "inputEpoch=$inputGeneration outputEpoch=${surfaceLifetime.generation()} " +
            "owner=Media3 physicalQueueDepth=NOT_MEASURED")
    }

    private fun attachDecoderInput(surface: Surface, epoch: Long) {
        if (released || input !== surface || inputGeneration != epoch ||
            !surfaceLifetime.matches(epoch) || !surface.isValid) return
        val owner = player ?: return
        if (attachedCodecPlayer === owner && attachedCodecSurface === surface) return
        detachDecoderInput("owner-or-surface-changed")
        owner.setVideoSurface(surface)
        attachedCodecPlayer = owner
        attachedCodecSurface = surface
        codecCorrelation?.attach(surface)
        Log.i("ApexviewSingleDecoder", "SCENE_CODEC_INPUT_ATTACH " +
            "renderer=SurfaceView protected=$protectedOutput surfaceId=${System.identityHashCode(surface)} " +
            "inputEpoch=$epoch owner=Media3")
    }
    var onDisplaySelected: (Int?) -> Unit = {}
    var onFailure: (String) -> Unit = {}
    var onFirstFrame: () -> Unit = {}
    var onOutputLost: () -> Unit = {}
    private val outputReadiness = SceneOutputReadiness()
    // Surface lifetime is not the same as encoded-atlas generation. A quality
    // change must not discard an otherwise valid pending codec attachment.
    private val surfaceLifetime = SceneSurfaceLifetime()
    private var firstFrameReported = false
    // GL-owned epoch; never read the latest UI generation after an in-flight swap.
    private var renderEpoch = -1L
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
    // Shared by clear archive, authorized Widevine and the TV Cast receiver.
    // Reuses small native vertex buffers during dragging and layout transitions.
    private val quadBuffers = SceneQuadBufferCache()
    private var cachedQuads: List<Pair<java.nio.FloatBuffer, java.nio.FloatBuffer>> = emptyList()
    private var failed = false
    private var rendered = 0L
    private var lastLogNs = 0L
    private fun pipelineHealthy(): Boolean =
        codecCorrelation?.allowsAcceleratedOutput(System.nanoTime()) == true
    // A one-second GL-looper scheduling probe distinguishes a delayed callback
    // from a fast GPU swap. It samples once per second, never once per frame.
    private var nextGlProbeExpectedNs = 0L
    private val glQueueProbe = object : Runnable {
        override fun run() {
            if (released) return
            val now = System.nanoTime()
            if (nextGlProbeExpectedNs != 0L)
                pacing.onGlQueueProbe((now - nextGlProbeExpectedNs).coerceAtLeast(0L))
            frameRateOutput?.let(::updateFrameRateHint)
            nextGlProbeExpectedNs = now + 1_000_000_000L
            gl.postDelayed(this, 1_000L)
        }
    }

    private var appliedFrameRateHint = Float.NaN
    // Holder owns this Surface. Only the GL looper owns its vote/cache.
    private var frameRateOutput: Surface? = null
    private var lastPresentedAtNs = 0L
    private var lastSourceTimestampNs = 0L
    // Share the same camera-driven, 50fps-aware scheduling and frame metrics
    // as the clear HEVC archive; the independent SurfaceView remains distinct.
    @Volatile private var codecCorrelation: SceneCodecTimestampCorrelation? = null
    private val frameDemand = SceneFrameDemand()
    private val cadenceGate = SceneFrameCadenceGate()
    private val pacing = SceneFramePacing()
    private var displayClock: Choreographer? = null
    private val displayFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) = guarded {
            val callbackStartNs = System.nanoTime()
            val refreshHz = selectedDisplayHz
            if (frameDemand.hasPendingVideo() && cadenceGate.shouldDefer(
                frameTimeNanos, scene?.sourceFrameRate ?: 0.0, refreshHz,
                playbackSpeed, thermalForOutput(), pipelineHealthy())) {
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
            } else {
                frameDemand.onDrawUnavailable(hasNewVideo)
                pacing.onDrawUnavailable()
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
             speed: Float = 1f, correlation: SceneCodecTimestampCorrelation? = null) {
        if (codecCorrelation !== correlation) {
            attachedCodecSurface?.let { codecCorrelation?.detach(it) }
            codecCorrelation = correlation
            attachedCodecSurface?.let { correlation?.attach(it) }
        }
        val changed = scene != nextScene
        val firstBinding = scene == null
        val atlasChanged = scene?.sameEncodedAtlasAs(nextScene) == false
        val playerChanged = player != null && player !== nextPlayer
        val bindingChanged = atlasChanged || playerChanged
        val invalidatedEpoch = if (bindingChanged) {
            codecCorrelation?.reset(attachedCodecSurface)
            // Layout-only window changes can reuse the already latched atlas;
            // only a different coded picture must await a fresh decoder frame.
            val epoch = outputReadiness.invalidate()
            onOutputLost()
            epoch
        } else null
        val normalizedSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val speedChanged = playbackSpeed != normalizedSpeed
        playbackSpeed = normalizedSpeed
        scene = nextScene
        logDisplayMode("bind")
        if (player !== nextPlayer) {
            detachDecoderInput("player-replaced")
            player = nextPlayer
            input?.let { attachDecoderInput(it, inputGeneration) }
        }
        if (changed || speedChanged || bindingChanged) gl.post { guarded {
            if (invalidatedEpoch != null) {
                renderEpoch = invalidatedEpoch
                cadenceGate.reset()
                // New encoded atlas or crop mapping cannot reuse old input pixels.
                hasFrame = false
                frameDemand.discardPendingVideo()
                firstFrameReported = false
            }
            if (firstBinding || atlasChanged) {
                decoderTexture?.setDefaultBufferSize(nextScene.atlasWidth, nextScene.atlasHeight)
            }
            frameRateOutput?.let(::updateFrameRateHint)
            scheduleVideoFrame()
        } }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        detachDecoderInput("output-replaced")
        val lifetime = surfaceLifetime.advance()
        val readinessEpoch = outputReadiness.invalidate()
        onOutputLost()
        val surface = holder.surface
        gl.post {
            // Android can destroy and recreate a surface before this callback
            // reaches the GL looper. Never initialize an old or invalid output.
            if (!released && surfaceLifetime.matches(lifetime) && surface.isValid) {
                failed = false // Only a genuinely new valid EGL surface can recover output.
                guarded { initialize(surface, readinessEpoch); scheduleVideoFrame() }
            }
        }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        outputWidth = width; outputHeight = height; onOutputSize(width, height)
        gl.post { guarded { scheduleVideoFrame() } }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceLifetime.advance()
        outputReadiness.invalidate()
        onOutputLost()
        detachOutput()
    }
    private fun detachOutput() {
        // All EGL objects are GL-looper-owned. Clear the player's video output
        // on its application thread, then queue GL destruction. Waiting for a
        // 2-second CountDownLatch here froze Compose/TV controls on rotation,
        // stop/start and Cast receiver handoff while eglSwapBuffers was busy.
        detachDecoderInput("output-detached")
        if (!gl.post { destroy() }) {
            Log.w("ApexviewSingleDecoder", "SECURE_EGL_RELEASE_QUEUE_CLOSED")
        }
    }
    fun release() {
        if (released) return
        released = true
        onDisplaySelected(null)
        surfaceLifetime.advance()
        outputReadiness.invalidate()
        holder.removeCallback(this)
        detachOutput()
        codecCorrelation = null
        player = null; gl.removeCallbacks(glQueueProbe); thread.quitSafely()
    }

    private fun initialize(output: Surface, readinessEpoch: Long) {
        destroy()
        renderEpoch = readinessEpoch
        frameRateOutput = output
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
        // Both protected and clear scenes already present on Choreographer's
        // display callback. Ask EGL not to block this same decoder-latch thread
        // on a second swap interval. Unsupported OEM implementations retain
        // their default pacing without bypassing secure output.
        val nonBlockingSwapAccepted = EGL14.eglSwapInterval(display, 0)
        Log.i("ApexviewSingleDecoder", "SURFACE_SCENE_SWAP_INTERVAL requested=0 " +
            "accepted=$nonBlockingSwapAccepted protected=$protectedOutput pacing=CHOREOGRAPHER")
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
                    val callbackStartedNs = System.nanoTime()
                    // Texture latching and drawing use the SAME protected EGL context.
                    // EGL may be unbound after surface/encoder handoffs; never assume
                    // that the previous draw left it current on this GL looper.
                    val bindStartedNs = System.nanoTime()
                    check(window != EGL14.EGL_NO_SURFACE &&
                        EGL14.eglMakeCurrent(display, window, window, contextGl)) {
                        "Protected scene GL context unavailable for decoder latch"
                    }
                    val bindNs = System.nanoTime() - bindStartedNs
                    val latchStartNs = System.nanoTime()
                    Trace.beginSection("PitwallProtectedSceneLatch")
                    try { frames.updateTexImage() } finally { Trace.endSection() }
                    val latchedAtNs = System.nanoTime()
                    val timestamp = frames.timestamp
                    pacing.onTextureLatched(latchedAtNs, latchedAtNs - latchStartNs,
                        timestamp, eglBindNs = bindNs, callbackArrivalNs = callbackStartedNs)
                    codecCorrelation?.onTextureLatched(input, timestamp, latchedAtNs)
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
        val lifetime = surfaceLifetime.generation()
        inputGeneration = lifetime
        post { attachDecoderInput(surface, lifetime) }
    }

    private fun updateFrameRateHint(output: Surface) {
        // A frame-rate hint changes only SurfaceFlinger scheduling. It does not
        // change Widevine, the secure EGL context or protected buffer handling.
        // L1 surfaces deserve the same seamless-only 50 fps pacing hint as
        // authorized L3 scenes; unsupported OEM surfaces fail harmlessly.
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val rate = SceneFrameRatePolicy.composedOutputHint(
            scene?.sourceFrameRate ?: 0.0, playbackSpeed, selectedDisplayHz, thermalForOutput(), pipelineHealthy())
        if (rate == appliedFrameRateHint) return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                output.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                output.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            appliedFrameRateHint = rate
            Log.i("ApexviewSingleDecoder",
                "SURFACE_SCENE_FPS_HINT_APPLIED secure=$protectedOutput fps=$rate")
        }.onFailure {
            Log.w("ApexviewSingleDecoder",
                "SURFACE_SCENE_FPS_HINT_UNAVAILABLE " + it.javaClass.simpleName)
        }
    }

    private data class SurfaceFrameTiming(val drawNs: Long, val swapNs: Long)

    private fun draw(): SurfaceFrameTiming? {
        val surfaceEpoch = renderEpoch
        if (!outputReadiness.matches(surfaceEpoch)) return null
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
                cachedQuads = quadBuffers.update(model)
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
        if (hasFrame) codecCorrelation?.onSwapSubmitted(input, swapEndedNs)
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
            val expectedFps = SceneFrameRatePolicy.composedOutputHint(
                scene?.sourceFrameRate ?: 0.0, playbackSpeed, selectedDisplayHz,
                thermalForOutput(), pipelineHealthy())
            codecCorrelation?.recordPipelineWindow(input, now, now - lastLogNs, expectedFps, metrics)
            val correlation = codecCorrelation?.snapshotAndReset(input)
            // Five-second trace samples of GL stages, not video scanout.
            Trace.setCounter("PitwallSurfaceSceneLatched5s",
                metrics.latched.coerceAtLeast(0L))
            Trace.setCounter("PitwallSurfaceSceneGlSubmits5s",
                metrics.presented.coerceAtLeast(0L))
            Log.i("ApexviewSingleDecoder", "SURFACE_DECODER_SCENE " +
                "protected=$protectedOutput output=${outputWidth}x$outputHeight " +
                "inputTextures=1 outputViews=1 sources=${model.sources} " +
                "windows=${model.windows.size} decoderFrames=$rendered " +
                "sourceFps=${model.sourceFrameRate} speed=$playbackSpeed " +
                "effectiveFps=${model.sourceFrameRate * playbackSpeed} " +
                "outputHintFps=${SceneFrameRatePolicy.composedOutputHint(model.sourceFrameRate, playbackSpeed,
                    selectedDisplayHz, thermalForOutput(), pipelineHealthy())} " +
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
                "coalescedTextureLatches=${frameDemand.takeCoalescedVideoArrivals()} " +
                "unavailableSurfaceDraws=${metrics.unavailableDraws} " +
                "sourceTimestampBins13_19_23_30_45_100=${metrics.textureTimestampIntervals.compact()} " +
                "callbackIntervalsBins13_19_23_30_45_100=${metrics.callbackIntervals.compact()} " +
                "videoVsyncBins13_19_23_30_45_100=${metrics.displayIntervals.compact()} " +
                "eglBindP95UpperMs=${metrics.eglBindDurations.percentileUpperMs(95)} " +
                "latchP95UpperMs=${metrics.latchDurations.percentileUpperMs(95)} " +
                "glSubmitP95UpperMs=${metrics.glDrawDurations.percentileUpperMs(95)} " +
                "eglSwapP95UpperMs=${metrics.eglSwapDurations.percentileUpperMs(95)} " +
                "choreographerDelayP95UpperMs=${metrics.callbackDelays.percentileUpperMs(95)} " +
                "glQueueP95UpperMs=${metrics.glQueueDelays.percentileUpperMs(95)} " +
                "acceleratedPipelineHealthy=${pipelineHealthy()} expectedWindowFps=$expectedFps " +
                "codecCorrelationOwned=${correlation != null} codecMatchSemantics=EXACT_PTS_OR_RELEASE " +
                "codecFrameCallbacks=${correlation?.codecFrames ?: 0} " +
                "codecPtsMatched=${correlation?.matchedLatches ?: 0} " +
                "codecPtsMissingAtLatch=${correlation?.unmatchedLatches ?: 0} " +
                "codecPtsLateMatched=${correlation?.lateMatchedLatches ?: 0} " +
                "codecPtsOutstanding=${correlation?.outstandingLatches ?: 0} " +
                "codecExactPtsOnly=${correlation?.ptsOnlyMatches ?: 0} " +
                "codecExactReleaseOnly=${correlation?.releaseOnlyMatches ?: 0} " +
                "codecExactBothDomains=${correlation?.bothDomainMatches ?: 0} " +
                "codecAmbiguous=${correlation?.ambiguousMatches ?: 0} " +
                "codecDuplicateLatch=${correlation?.duplicateLatches ?: 0} " +
                "plannedCodecReleaseToLatchP95UpperMs=${correlation?.releaseToLatch?.percentileUpperMs(95) ?: 0} " +
                "codecCallbackToLatchP95UpperMs=${correlation?.callbackToLatch?.percentileUpperMs(95) ?: 0} " +
                "latchToSwapP95UpperMs=${correlation?.latchToSwap?.percentileUpperMs(95) ?: 0} " +
                "bufferQueueStage=${SceneBufferQueueEvidence.stage(metrics.latched, metrics.presented, metrics.callbackGaps, metrics.slowSwaps, metrics.unavailableDraws)} " +
                "bufferQueuePlatformDepth=NOT_MEASURED " +
                "swapSubmissionsNotPhysicalPresentation=true physicalPresentation=NOT_MEASURED")
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
            val reason = "${if (protectedOutput) "Protected" else "Clear SurfaceView"} scene output: " +
                (error.message ?: error.javaClass.simpleName).take(200)
            Log.w("ApexviewSingleDecoder", reason)
            post { if (!released) onFailure(reason) }
        }
    }

    private fun destroy() {
        input?.let { codecCorrelation?.detach(it) }
        displayClock?.removeFrameCallback(displayFrame)
        displayClock = null
        frameDemand.reset()
        cadenceGate.reset()
        pacing.reset()
        lastLogNs = 0L
        nextGlProbeExpectedNs = 0L
        decoderTexture?.setOnFrameAvailableListener(null)
        input?.let { Log.i("ApexviewSingleDecoder", "SCENE_CODEC_INPUT_RELEASE " +
            "renderer=SurfaceView protected=$protectedOutput " +
            "surfaceId=${System.identityHashCode(it)} " +
            "outputEpoch=${surfaceLifetime.generation()} thread=GL") }
        inputGeneration = -1L
        input?.release(); input = null
        decoderTexture?.release(); decoderTexture = null; hasFrame = false
        firstFrameReported = false
        renderEpoch = -1L
        lastPresentedAtNs = 0L
        lastSourceTimestampNs = 0L
        if (display != EGL14.EGL_NO_DISPLAY) {
            // Android can have unbound this GL looper after a rotation, stop or
            // SurfaceFlinger output handoff. GL deletion requires this exact
            // context to be current on the owning GL thread.
            val canDeleteGlObjects = contextGl != EGL14.EGL_NO_CONTEXT &&
                window != EGL14.EGL_NO_SURFACE &&
                EGL14.eglMakeCurrent(display, window, window, contextGl)
            if (canDeleteGlObjects) {
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            } else if (program != 0 || texture != 0) {
                // EGL context destruction reclaims its owned GPU objects.
                // Do not issue GLES commands against a stale/foreign context.
                Log.w("ApexviewSingleDecoder",
                    "SCENE_GL_CLEANUP_CONTEXT_UNAVAILABLE release=EGL_CONTEXT_TEARDOWN")
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (contextGl != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, contextGl)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY; contextGl = EGL14.EGL_NO_CONTEXT; window = EGL14.EGL_NO_SURFACE
        texture = 0; program = 0
        appliedFrameRateHint = Float.NaN
        frameRateOutput = null
        imageUniform = -1; textureMatrixUniform = -1
        positionAttribute = -1; texcoordAttribute = -1
    }
}
