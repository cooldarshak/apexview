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
import android.os.Process
import android.os.Trace
import android.util.Log
import android.view.PixelCopy
import android.view.Choreographer
import android.view.Window
import android.view.Surface
import android.view.TextureView
import app.apexview.cast.BakedCastLiveSession
import androidx.media3.exoplayer.ExoPlayer

/**
 * One codec output texture, one visible output view. No per-window players or decoders.
 * Logical windows sample regions of the already composed frame, with the usual layout/z order.
 * This renderer is used only for verified CLEAR media. Protected video keeps its secure path.
 */
internal class SingleDecoderSceneView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    // BufferQueue backpressure can grow while the decoder texture waits for this
    // handler to run. Display priority helps latch callbacks without making the
    // UI thread do any GL work or introducing a second decoder/context.
    private val thread = HandlerThread("ApexviewSingleScene", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val gl = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val glProbePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val glQueueProbe = object : Runnable {
        override fun run() {
            if (released || !isAttachedToWindow) return
            if (glProbePending.compareAndSet(false, true)) {
                val postedAtNs = System.nanoTime()
                if (!gl.post {
                    try {
                        if (!released) {
                            pacing.onGlQueueProbe(System.nanoTime() - postedAtNs)
                            applyOutputFrameRateHint()
                        }
                    } finally { glProbePending.set(false) }
                }) glProbePending.set(false)
            }
            main.postDelayed(this, 250L)
        }
    }
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
        return app.apexview.VideoThermalBudget.effectiveStatus(cachedThermal)
    }
    private val modeListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (rootView.display?.displayId == displayId) {
                logDisplayMode("mode-changed")
                gl.post { if (!released) { cadenceGate.reset(); applyOutputFrameRateHint() } }
            }
        }
    }

    private fun logDisplayMode(reason: String) {
        val actualDisplay = rootView.display ?: return
        val mode = actualDisplay.mode
        selectedDisplayHz = actualDisplay.refreshRate
        onDisplaySelected(actualDisplay.displayId)
        val sourceFps = scene?.sourceFrameRate ?: 0.0
        val signature = "${actualDisplay.displayId}:${mode.modeId}:${mode.refreshRate}:$selectedDisplayHz:$sourceFps:$playbackSpeed"
        if (signature == previousModeSignature) return
        previousModeSignature = signature
        // An available mode is not a selected mode; setFrameRate() is only advice to SurfaceFlinger.
        val modes = actualDisplay.supportedModes
        val available = modes.joinToString(",") {
            "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}"
        }
        val active = SceneFrameRatePolicy.Mode(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
        val actualOutputFps = SceneFrameRatePolicy.composedOutputHint(sourceFps, playbackSpeed,
            selectedDisplayHz, app.apexview.VideoThermalBudget.effectiveStatus(runCatching { outputPower?.currentThermalStatus }.getOrNull() ?: Int.MAX_VALUE),
            pipelineHealthy())
        val seamlessMatches = SceneFrameRatePolicy.matchingSeamlessModes(
            active, modes.map { SceneFrameRatePolicy.Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) },
            actualOutputFps.toDouble(), 1f
        ).joinToString(",") { "${it.id}@${it.refreshHz}" }.ifBlank { "none" }
        Log.i("ApexviewSingleDecoder", "SCENE_DISPLAY_MODE reason=$reason displayId=${actualDisplay.displayId} " +
            "activeMode=${mode.modeId} activeHz=${mode.refreshRate} displayRefreshHz=$selectedDisplayHz sourceFps=$sourceFps speed=$playbackSpeed " +
            "effectiveFps=${SceneFrameRatePolicy.hint(sourceFps, playbackSpeed)} " +
            "outputHintFps=${SceneFrameRatePolicy.composedOutputHint(sourceFps, playbackSpeed,
                selectedDisplayHz, app.apexview.VideoThermalBudget.effectiveStatus(runCatching { outputPower?.currentThermalStatus }.getOrNull() ?: Int.MAX_VALUE),
                 pipelineHealthy())} " +
            "integerRefreshMultiple=${SceneFrameRatePolicy.isIntegerRefreshMultiple(mode.refreshRate, actualOutputFps.toDouble(), 1f)} " +
            "supportedModes=$available sameResolutionIntegralModes=$seamlessMatches " +
            "modeSwitchIssuedByApp=false physicalPresentation=NOT_MEASURED")
    }
    @Volatile private var playbackSpeed = 1f
    private var player: ExoPlayer? = null
    @Volatile private var scene: VideoScene? = null
    @Volatile private var released = false
    @Volatile private var input: Surface? = null

    /** MediaCodec produces into input; the GL SurfaceTexture consumes it.
     * Clear the producer on Media3's application thread BEFORE GL disposes
     * the consumer when the output is replaced, destroyed or released.
     * DequeueTimeout=0 is nonblocking, not proof of a network stall. */
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
            "renderer=TextureView reason=$reason surfaceId=${System.identityHashCode(old)} " +
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
            "renderer=TextureView surfaceId=${System.identityHashCode(surface)} " +
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
    private val graphicsOwnerId = SceneGraphicsOwners.process.newId()
    private var graphicsLifetime = -1L
    private var graphicsAtlasWidth = 0
    private var graphicsAtlasHeight = 0
    private var firstFrameReported = false
    // GL-owned epoch; never read the latest UI generation after an in-flight swap.
    private var renderEpoch = -1L
    var onOutputSize: (Int, Int) -> Unit = { _, _ -> }
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var contextGl: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputSurface: Surface? = null
    private var hintedFrameRate = -1f
    private var config: EGLConfig? = null
    @Volatile private var clip: ReplayClipEncoder? = null
    private var clipResult: ((String) -> Unit)? = null
    @Volatile private var castWorker: BakedCastCaptureWorker? = null
    @Volatile private var castSession: BakedCastLiveSession? = null
    private var castVideoTarget: BakedCastGpuCapture.VideoTarget? = null
    private var castVideoSurface = EGL14.EGL_NO_SURFACE

    /**
     * The independent encoder receives one rendered clear scene on demand.
     * Only this looper may read the decoded HEVC SurfaceTexture.
     */
    fun startBakedCast(source: Window, bounds: Rect, session: BakedCastLiveSession,
                       overlay: BakedCastOverlay, playbackPositionMs: () -> Long?) {
        gl.post {
            if (castSession === session) return@post
            stopBakedCastOnGl()
            try {
                check(clip == null) { "Stop replay recording before casting" }
                check(!released && hasFrame && EGL14.eglMakeCurrent(display, window, window, contextGl)) {
                    "Wait for the single-decoder replay picture before casting"
                }
                check(source.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE == 0) {
                    "Protected video cannot be copied into clear Cast"
                }
                val scale = minOf(1.0, 1920.0 / maxOf(bounds.width(), bounds.height()))
                val width = ((bounds.width() * scale).toInt() and -2).coerceAtLeast(2)
                val height = ((bounds.height() * scale).toInt() and -2).coerceAtLeast(2)
                val worker = BakedCastCaptureWorker(
                    context.applicationContext, source, Rect(bounds), width, height, session, overlay,
                    ::drawBakedCastVideo, ::detachBakedCastVideo, playbackPositionMs,
                )
                castWorker = worker
                castSession = session
                worker.start()
            } catch (error: Exception) {
                session.captureFailed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun stopBakedCast(session: BakedCastLiveSession? = null) {
        gl.post { if (session == null || castSession === session) stopBakedCastOnGl() }
    }

    private fun stopBakedCastOnGl() {
        lastPreviewRequestNs = 0L
        castWorker?.stop(); castWorker = null; castSession = null
        releaseBakedCastVideoSurface()
    }

    private fun drawBakedCastVideo(target: BakedCastGpuCapture.VideoTarget) {
        if (!gl.post {
            if (target.closed) return@post
            try {
                val model = checkNotNull(scene)
                check(!released && hasFrame && window != EGL14.EGL_NO_SURFACE) { "Clear scene output is unavailable" }
                val changed = castVideoTarget !== target
                if (changed) {
                    releaseBakedCastVideoSurface()
                    val selected = checkNotNull(config)
                    castVideoSurface = EGL14.eglCreateWindowSurface(display, selected, target.surface,
                        intArrayOf(EGL14.EGL_NONE), 0)
                    castVideoTarget = target
                    recordGraphicsOwner()
                }
                val output = castVideoSurface
                check(output != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, output, output, contextGl)) {
                    "Unable to bind clear Cast video output"
                }
                if (changed) EGL14.eglSwapInterval(display, 0)
                GLES20.glViewport(0, 0, target.width, target.height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawVideo(model)
                check(EGL14.eglSwapBuffers(display, output)) { "Clear Cast video output swap failed" }
            } catch (error: Exception) {
                if (!target.closed) target.fail(error)
            } finally {
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglMakeCurrent(display, window, window, contextGl)
            }
        }) target.fail(IllegalStateException("Clear scene has already closed"))
    }

    private fun detachBakedCastVideo(target: BakedCastGpuCapture.VideoTarget) {
        gl.post {
            if (castVideoTarget === target) {
                releaseBakedCastVideoSurface()
                lastPreviewRequestNs = 0L
                scheduleDisplayFrame()
            }
        }
    }

    private fun releaseBakedCastVideoSurface() {
        if (castVideoSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, castVideoSurface)
        castVideoTarget = null
        castVideoSurface = EGL14.EGL_NO_SURFACE
        recordGraphicsOwner()
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
    // Shared by clear archive, authorized Widevine and the TV Cast receiver.
    // Reuses small native vertex buffers during dragging and layout transitions.
    private val quadBuffers = SceneQuadBufferCache()
    private var cachedQuads: List<Pair<java.nio.FloatBuffer, java.nio.FloatBuffer>> = emptyList()
    private var failed = false
    private var rendered = 0L
    private var lastLogNs = 0L
    private fun pipelineHealthy(): Boolean =
        codecCorrelation?.allowsAcceleratedOutput(System.nanoTime()) == true
    @Volatile private var codecCorrelation: SceneCodecTimestampCorrelation? = null
    private val frameDemand = SceneFrameDemand()
    private val pacing = SceneFramePacing()
    private val cadenceGate = SceneFrameCadenceGate()
    private var lastPreviewRequestNs = 0L
    private var reducedPreviewInWindow = false
    private fun reduceLocalPreview(): Boolean =
        castSession?.let { it.receiverRendering && it.previewSuppressionRequested } == true &&
            castWorker?.directFrameReady == true
    private var displayClock: Choreographer? = null
    private val displayFrame: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) = guarded {
            val callbackStarted = System.nanoTime()
            if (frameDemand.hasPendingVideo() && cadenceGate.shouldDefer(
                    frameTimeNs, scene?.sourceFrameRate ?: 0.0, selectedDisplayHz,
                    playbackSpeed, thermalForOutput(), pipelineHealthy(),
                pendingAgeNs = frameDemand.pendingAgeNs(callbackStarted),
                coalescedPending = frameDemand.hasCoalescedPendingVideo())) {
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
            } else {
                // Keep the last codec picture pending until a later surface/bind event.
                // Never spin Choreographer while EGL output is unavailable.
                frameDemand.onDrawUnavailable(newVideo)
                pacing.onDrawUnavailable()
            }
        }
    }

    /** Swap on display vsync; decoding does not wait for a display callback to release buffers. */
    private fun scheduleDisplayFrame(videoArrived: Boolean = false) {
        val schedule = if (videoArrived) frameDemand.videoArrived(System.nanoTime()) else frameDemand.requestDraw()
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
        val fps = SceneFrameRatePolicy.composedOutputHint(
            scene?.sourceFrameRate ?: 0.0, playbackSpeed, selectedDisplayHz, thermalForOutput(), pipelineHealthy())
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
        main.removeCallbacks(glQueueProbe)
        main.post(glQueueProbe)
        displayManager.registerDisplayListener(modeListener, main)
        logDisplayMode("attach")
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(glQueueProbe)
        displayManager.unregisterDisplayListener(modeListener)
        previousModeSignature = null
        onDisplaySelected(null)
        super.onDetachedFromWindow()
    }

    fun bind(nextPlayer: ExoPlayer, nextScene: VideoScene, speed: Float = 1f,
             correlation: SceneCodecTimestampCorrelation? = null) {
        // Retire the previous player before changing its correlation owner.
        if (player != null && player !== nextPlayer) detachDecoderInput("player-replaced")
        if (codecCorrelation !== correlation) {
            attachedCodecSurface?.let { codecCorrelation?.detach(it) }
            codecCorrelation = correlation
            attachedCodecSurface?.let { correlation?.attach(it) }
        }
        val normalizedSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val speedChanged = playbackSpeed != normalizedSpeed
        playbackSpeed = normalizedSpeed
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
        scene = nextScene
        if (player !== nextPlayer) {
            detachDecoderInput("player-replaced")
            player = nextPlayer
            input?.let { attachDecoderInput(it, inputGeneration) }
        }
        if (changed || speedChanged || bindingChanged) {
            main.post { if (!released) logDisplayMode(if (speedChanged) "speed-changed" else "source-changed") }
            val lifetime = surfaceLifetime.generation()
            val bindingEpoch = outputReadiness.generation()
            gl.post { guarded(lifetime) {
                if (!outputReadiness.matches(bindingEpoch)) return@guarded
                if (invalidatedEpoch != null) {
                    renderEpoch = invalidatedEpoch
                    hasFrame = false
                    frameDemand.discardPendingVideo()
                    firstFrameReported = false
                    cadenceGate.reset()
                }
                if (speedChanged) cadenceGate.reset()
                if (firstBinding || atlasChanged) {
                    decoderTexture?.let {
                        it.setDefaultBufferSize(nextScene.atlasWidth, nextScene.atlasHeight)
                        graphicsAtlasWidth = nextScene.atlasWidth
                        graphicsAtlasHeight = nextScene.atlasHeight
                    }
                }
                recordGraphicsOwner()
                applyOutputFrameRateHint()
                scheduleDisplayFrame()
            } }
        }
    }

    override fun onSurfaceTextureAvailable(output: SurfaceTexture, width: Int, height: Int) {
        detachDecoderInput("output-replaced")
        val lifetime = surfaceLifetime.advance()
        val readinessEpoch = outputReadiness.invalidate()
        onOutputLost()
        outputWidth = width; outputHeight = height
        onOutputSize(width, height)
        gl.post {
            if (!released && surfaceLifetime.matches(lifetime)) {
                failed = false // New valid EGL output may recover a transient loss.
                guarded(lifetime) { initialize(output, readinessEpoch, lifetime); scheduleDisplayFrame() }
            }
        }
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        outputWidth = width; outputHeight = height
        onOutputSize(width, height)
        gl.post { guarded { recordGraphicsOwner(); scheduleDisplayFrame() } }
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        surfaceLifetime.advance()
        outputReadiness.invalidate()
        onOutputLost()
        detachDecoderInput("output-destroyed")
        // GL owns the Surface reference until queued destruction releases it. Clearing it
        // here lost that reference before destroy() and leaked a codec output surface.
        if (!gl.post { destroy(); surface.release() }) {
            // TextureView returns false: the app owns SurfaceTexture release
            // even when the GL loop has already quit on a late lifecycle event.
            surface.release()
            Log.w("ApexviewSingleDecoder", "SCENE_OUTPUT_RELEASE_AFTER_GL_THREAD_EXIT")
        }
        return false
    }

    fun release() {
        if (released) return
        released = true
        onDisplaySelected(null)
        surfaceLifetime.advance()
        outputReadiness.invalidate()
        main.removeCallbacks(glQueueProbe)
        detachDecoderInput("scene-released")
        player = null
        surfaceTextureListener = null
        codecCorrelation = null
        val output = surfaceTexture
        if (!gl.post { destroy(); output?.release(); thread.quitSafely() }) {
            output?.release()
            thread.quitSafely()
            Log.w("ApexviewSingleDecoder", "SCENE_GL_RELEASE_QUEUE_CLOSED")
        }
    }

    private fun initialize(output: SurfaceTexture, readinessEpoch: Long, lifetime: Long) {
        destroy()
        graphicsLifetime = lifetime
        renderEpoch = readinessEpoch
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
        scene?.let {
            frames.setDefaultBufferSize(it.atlasWidth, it.atlasHeight)
            graphicsAtlasWidth = it.atlasWidth
            graphicsAtlasHeight = it.atlasHeight
        }
        frames.setOnFrameAvailableListener({
            guarded(lifetime) {
                if (decoderTexture === frames && !released) {
                    val callbackStarted = System.nanoTime()
                    Trace.beginSection("PitwallDecoderFrameCallback")
                    try {
                    // A decoder can exhaust its output BufferQueue while the EGL display
                    // swap is blocked. Latch promptly to release the prior codec buffer;
                    // coalesce only the *drawing* onto a Choreographer vsync.
                    val bindStarted = System.nanoTime()
                    check(window != EGL14.EGL_NO_SURFACE &&
                        EGL14.eglMakeCurrent(display, window, window, contextGl)) { "Scene GL context unavailable" }
                    val eglBindNs = System.nanoTime() - bindStarted
                    val latchStarted = System.nanoTime()
                    Trace.beginSection("PitwallTextureLatch")
                    try { frames.updateTexImage() } finally { Trace.endSection() }
                    frames.getTransformMatrix(transform)
                    hasFrame = true
                    rendered++
                    val latchedAt = System.nanoTime()
                    val sourceTimestampNs = frames.timestamp
                    pacing.onTextureLatched(latchedAt, latchedAt - latchStarted, sourceTimestampNs,
                        eglBindNs, callbackArrivalNs = callbackStarted)
                    codecCorrelation?.onTextureLatched(input, sourceTimestampNs, latchedAt)
                    // Always latch the codec buffer. Reduce only the local preview draws.
                    if (reduceLocalPreview()) {
                        reducedPreviewInWindow = true
                        if (lastPreviewRequestNs == 0L || latchedAt - lastPreviewRequestNs >= 100_000_000L) {
                            lastPreviewRequestNs = latchedAt
                            scheduleDisplayFrame(videoArrived = true)
                        }
                    } else {
                        lastPreviewRequestNs = 0L
                        scheduleDisplayFrame(videoArrived = true)
                    }
                    } finally { Trace.endSection() }
                }
            }
        }, gl)
        val surface = Surface(frames); input = surface
        // Never relabel an input created for an obsolete Android surface
        // with the newer UI epoch, even if EGL initialization was slow.
        inputGeneration = lifetime
        recordGraphicsOwner()
        post { attachDecoderInput(surface, lifetime) }
    }

    private data class DrawTiming(val glDrawNs: Long, val swapNs: Long)

    private fun draw(): DrawTiming? {
        val surfaceEpoch = renderEpoch
        if (!outputReadiness.matches(surfaceEpoch)) return null
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
        if (hasFrame) codecCorrelation?.onSwapSubmitted(input, swapCompleted)
        if (hasFrame && !firstFrameReported) {
            firstFrameReported = true
            post {
                if (!released && outputReadiness.acknowledgeSubmittedFrame(surfaceEpoch)) {
                    Log.i("ApexviewSingleDecoder",
                        "SCENE_FIRST_GL_OUTPUT renderer=TextureView epoch=$surfaceEpoch " +
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
            if (!reducedPreviewInWindow) codecCorrelation?.recordPipelineWindow(input, now, now - lastLogNs, expectedFps, metrics)
            else Log.i("ApexviewSingleDecoder", "SCENE_LOCAL_PREVIEW_REDUCED receiver=BAKED_CAST localTargetFps=10")
            reducedPreviewInWindow = false
            val correlation = codecCorrelation?.snapshotAndReset(input)
            // Five-second trace samples of GL stages, not video scanout.
            Trace.setCounter("PitwallTextureSceneLatched5s",
                metrics.latched.coerceAtLeast(0L))
            Trace.setCounter("PitwallTextureSceneGlSubmits5s",
                metrics.presented.coerceAtLeast(0L))
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
                "coalescedTextureLatches=${frameDemand.takeCoalescedVideoArrivals()} " +
                "unavailableSurfaceDraws=${metrics.unavailableDraws} " +
                "callbackIntervalsBins13_19_23_30_45_100=${metrics.callbackIntervals.compact()} " +
                "sourceTimestampBins13_19_23_30_45_100=${metrics.textureTimestampIntervals.compact()} " +
                "videoVsyncBins13_19_23_30_45_100=${metrics.displayIntervals.compact()} " +
                "allVsyncBins13_19_23_30_45_100=${metrics.allVsyncIntervals.compact()} " +
                "eglBindMaxMs=${metrics.eglBindDurations.maxMs.toInt()} " +
                "latchP95UpperMs=${metrics.latchDurations.percentileUpperMs(95)} " +
                "glSubmitP95UpperMs=${metrics.glDrawDurations.percentileUpperMs(95)} " +
                "eglSwapP95UpperMs=${metrics.eglSwapDurations.percentileUpperMs(95)} " +
                "choreographerDelayP95UpperMs=${metrics.callbackDelays.percentileUpperMs(95)} " +
                "glQueueProbeSamples=${metrics.glQueueDelays.count} " +
                "glQueueP95UpperMs=${metrics.glQueueDelays.percentileUpperMs(95)} " +
                "glQueueMaxMs=${metrics.glQueueDelays.maxMs.toInt()} " +
                "glThreadPriority=DISPLAY " +
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
                "decoderInputSurfaceId=${input?.let { System.identityHashCode(it) } ?: 0} " +
                "outputSurfaceId=${outputSurface?.let { System.identityHashCode(it) } ?: 0} outputSize=${outputWidth}x${outputHeight} " +
                "decoderAtlas=${scene?.atlasWidth ?: 0}x${scene?.atlasHeight ?: 0} " +
                SceneBufferQueueEvidence.cadenceFields(metrics.latched, metrics.presented,
                    if (lastLogNs > 0L) now - lastLogNs else 0L, selectedDisplayHz, expectedFps) + " " +
                "bufferQueueStage=${SceneBufferQueueEvidence.stage(metrics.latched, metrics.presented, metrics.callbackGaps, metrics.slowSwaps, metrics.unavailableDraws)} " +
                "bufferQueuePlatformDepth=NOT_MEASURED " +
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
            cachedQuads = quadBuffers.update(model)
            cachedModel = model
        }
        for ((positions, coordinates) in cachedQuads) {
            GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glVertexAttribPointer(uvAttribute, 2, GLES20.GL_FLOAT, false, 0, coordinates)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    private fun recordGraphicsOwner() {
        SceneGraphicsOwners.process.record(graphicsOwnerId, SceneGraphicsOwners.Owner(
            "TextureView", graphicsLifetime, renderEpoch,
            if (contextGl != EGL14.EGL_NO_CONTEXT) 1 else 0,
            (if (window != EGL14.EGL_NO_SURFACE) 1 else 0) + (if (castVideoSurface != EGL14.EGL_NO_SURFACE) 1 else 0),
            if (decoderTexture != null) 1 else 0,
            graphicsAtlasWidth, graphicsAtlasHeight, outputWidth.coerceAtLeast(0), outputHeight.coerceAtLeast(0)))
    }

    private fun guarded(lifetime: Long = surfaceLifetime.generation(), action: () -> Unit) {
        if (released || failed || !surfaceLifetime.matches(lifetime)) return
        try { action() } catch (error: Exception) {
            // A late failure from a destroyed output is not a failure of the
            // replacement scene. Its queued teardown still owns its resources.
            if (released || !surfaceLifetime.matches(lifetime)) return
            recordGraphicsOwner()
            failed = true
            val reason = "Single-decoder scene output: ${error.message ?: error.javaClass.simpleName}".take(200)
            Log.w("ApexviewSingleDecoder", reason)
            post { if (!released && surfaceLifetime.matches(lifetime)) onFailure(reason) }
        }
    }

    private fun destroy() {
        input?.let { codecCorrelation?.detach(it) }
        displayClock?.removeFrameCallback(displayFrame)
        frameDemand.reset()
        cadenceGate.reset()
        pacing.reset()
        lastLogNs = 0L
        val endedCapture = castSession
        stopBakedCastOnGl()
        endedCapture?.captureFailed("The viewer video output was replaced")
        saveClip()
        config = null
        decoderTexture?.setOnFrameAvailableListener(null)
        input?.let { Log.i("ApexviewSingleDecoder", "SCENE_CODEC_INPUT_RELEASE " +
            "renderer=TextureView surfaceId=${System.identityHashCode(it)} " +
            "outputEpoch=${surfaceLifetime.generation()} thread=GL") }
        inputGeneration = -1L
        input?.release(); input = null
        decoderTexture?.release(); decoderTexture = null; hasFrame = false
        firstFrameReported = false
        renderEpoch = -1L
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
        outputSurface?.release(); outputSurface = null
        hintedFrameRate = -1f
        display = EGL14.EGL_NO_DISPLAY; contextGl = EGL14.EGL_NO_CONTEXT; window = EGL14.EGL_NO_SURFACE
        texture = 0; program = 0
        imageUniform = -1; transformUniform = -1; positionAttribute = -1; uvAttribute = -1
        graphicsLifetime = -1L
        graphicsAtlasWidth = 0; graphicsAtlasHeight = 0
        SceneGraphicsOwners.process.remove(graphicsOwnerId)
    }
}
