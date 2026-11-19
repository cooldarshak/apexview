package app.apexview.scene

import android.content.Context
import android.graphics.Rect
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
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Window
import android.view.Surface
import android.view.TextureView
import app.apexview.cast.BakedCastLiveSession
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
    private var hasFrame = false
    private var outputWidth = 0
    private var outputHeight = 0
    private val transform = FloatArray(16)
    private var cachedModel: VideoScene? = null
    private var cachedQuads: List<Pair<java.nio.FloatBuffer, java.nio.FloatBuffer>> = emptyList()
    private var failed = false
    private var rendered = 0L
    private var lastLogNs = 0L

    init { surfaceTextureListener = this; isOpaque = true }

    fun bind(nextPlayer: ExoPlayer, nextScene: VideoScene) {
        val changed = scene != nextScene
        scene = nextScene
        if (player !== nextPlayer) {
            input?.let { player?.clearVideoSurface(it) }
            player = nextPlayer
            input?.let { nextPlayer.setVideoSurface(it) }
        }
        if (changed) gl.post { guarded { draw() } }
    }

    override fun onSurfaceTextureAvailable(output: SurfaceTexture, width: Int, height: Int) {
        outputWidth = width; outputHeight = height
        onOutputSize(width, height)
        gl.post { guarded { initialize(output); draw() } }
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        outputWidth = width; outputHeight = height
        onOutputSize(width, height)
        gl.post { guarded { draw() } }
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
        window = EGL14.eglCreateWindowSurface(display, selected, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, window, window, contextGl)) { "Unable to bind scene output" }
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
                    frames.updateTexImage(); frames.getTransformMatrix(transform); hasFrame = true; rendered++
                    draw()
                }
            }
        }, gl)
        val surface = Surface(frames); input = surface
        post { if (!released && input === surface) player?.setVideoSurface(surface) }
    }

    private fun draw() {
        val model = scene ?: return
        if (window == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, window, window, contextGl)) return
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasFrame) drawVideo(model)
        check(EGL14.eglSwapBuffers(display, window)) { "Scene output swap failed" }
        if (hasFrame && !firstFrameReported) {
            firstFrameReported = true
            post { if (!released) onFirstFrame() }
        }
        val now = System.nanoTime()
        if (now - lastLogNs >= 5_000_000_000L) {
            Log.i("ApexviewSingleDecoder", "SINGLE_DECODER_SCENE output=${outputWidth}x$outputHeight inputTextures=1 outputViews=1 sources=${model.sources} windows=${model.windows.size} frames=$rendered")
            lastLogNs = now
        }
    }

    private fun drawVideo(model: VideoScene) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "st"), 1, false, transform, 0)
        val p = GLES20.glGetAttribLocation(program, "p"); val uv = GLES20.glGetAttribLocation(program, "uv")
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
        display = EGL14.EGL_NO_DISPLAY; contextGl = EGL14.EGL_NO_CONTEXT; window = EGL14.EGL_NO_SURFACE
        texture = 0; program = 0
    }
}
