package app.apexview.scene

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Two bounded GPU inputs. The encoder never owns or latches the Media3 decoder SurfaceTexture. */
internal class BakedCastGpuCapture(
    private val gl: Handler,
    private val width: Int,
    private val height: Int,
    private val overlay: BakedCastOverlay,
    private val drawVideo: (VideoTarget) -> Unit,
    private val detachVideo: (VideoTarget) -> Unit,
) : AutoCloseable {
    internal class VideoTarget(val surface: Surface, val width: Int, val height: Int, val fail: (Exception) -> Unit) {
        private val retirement = ProducerRetirement()
        val closed get() = retirement.closed
        fun attachProducer() = retirement.attach()
        fun producerDetached() = retirement.detach()
        fun retire(release: () -> Unit) = retirement.retire(release)
    }

    private val main = Handler(Looper.getMainLooper())
    private val shader = BakedCastLayerCompositor(overlay.backgroundArgb, width, height)
    private var video: Input? = null
    private var ui: Input? = null
    private var videoTarget: VideoTarget? = null
    @Volatile private var closed = false

    private class Request(val complete: (BakedCastVideoPlacement) -> Unit, val fail: (Exception) -> Unit) {
        val startedNs = System.nanoTime()
        var videoReadyNs = 0L
        var overlayReadyNs = 0L
        var videoReady = false
        var uiReady = false
        var placement: BakedCastVideoPlacement? = null
        lateinit var timeout: Runnable
    }
    private var pending: Request? = null
    private var deliveredFrame = false
    private var completedRequests = 0
    private var maxVideoMs = 0L
    private var maxOverlayMs = 0L

    init {
        try {
            val videoInput = Input(width, height, gl) { pending?.let { it.videoReady = true; it.videoReadyNs = System.nanoTime(); complete(it) } }
            video = videoInput
            videoTarget = VideoTarget(videoInput.surface, width, height) { error ->
                gl.post { pending?.let { fail(it, error) } }
            }
            ui = Input(width, height, gl) { pending?.let { it.uiReady = true; it.overlayReadyNs = System.nanoTime(); complete(it) } }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun requestFrame(complete: (BakedCastVideoPlacement) -> Unit, failed: (Exception) -> Unit) {
        check(!closed && pending == null)
        val request = Request(complete, failed)
        request.timeout = Runnable { fail(request, IllegalStateException("Timed out drawing clear Cast layers")) }
        pending = request
        gl.postDelayed(request.timeout, if (deliveredFrame) 750L else 2_000L)
        drawVideo(checkNotNull(videoTarget))
        val targetSurface = checkNotNull(ui).surface
        main.post {
            if (closed) return@post
            try {
                val placement = overlay.placement()
                if (!overlay.submit(targetSurface, width, height)) {
                    gl.post { fail(request, IllegalStateException("Viewer overlay frame could not be drawn")) }
                } else gl.post { request.placement = placement; complete(request) }
            } catch (error: Exception) {
                gl.post { fail(request, error) }
            }
        }
    }

    private fun complete(request: Request) {
        val placement = request.placement ?: return
        if (pending !== request || !request.videoReady || !request.uiReady) return
        pending = null
        gl.removeCallbacks(request.timeout)
        deliveredFrame = true
        maxVideoMs = maxOf(maxVideoMs, (request.videoReadyNs - request.startedNs) / 1_000_000L)
        maxOverlayMs = maxOf(maxOverlayMs, (request.overlayReadyNs - request.startedNs) / 1_000_000L)
        if (++completedRequests % 100 == 0) {
            android.util.Log.i("PitwallCast", "BAKED_GPU_LAYERS maxVideoMs=$maxVideoMs maxOverlayMs=$maxOverlayMs samples=100")
            maxVideoMs = 0L; maxOverlayMs = 0L
        }
        request.complete(placement)
    }

    private fun fail(request: Request, error: Exception) {
        if (pending !== request) return
        pending = null
        gl.removeCallbacks(request.timeout)
        request.fail(error)
    }

    /** Called with the encoder's EGL surface current on the encoder thread. */
    fun draw(placement: BakedCastVideoPlacement) {
        val video = checkNotNull(video)
        val ui = checkNotNull(ui)
        video.latch()
        ui.latch()
        shader.draw(video.id, video.matrix, ui.id, ui.matrix, placement)
    }

    override fun close() {
        if (closed) return
        closed = true
        pending?.let { gl.removeCallbacks(it.timeout) }
        pending = null
        val target = videoTarget.also { videoTarget = null }
        val overlayInput = ui.also { ui = null }
        val videoInput = video.also { video = null }
        // Retire every independently owned resource even if another owner fails.
        fun release(label: String, block: () -> Unit) {
            try { block() }
            catch (error: RuntimeException) {
                android.util.Log.w("PitwallCast", "BAKED_GPU_RELEASE_FAILED owner=$label", error)
            }
        }
        // Stop listener delivery on this GL context. Actual queue abandonment
        // follows producer retirement, without waiting on either producer.
        videoInput?.let { input ->
            release("video-gl") { input.retireGl() }
            release("video-target") {
                if (target == null) input.releaseQueue()
                else {
                    target.retire { release("video-queue") { input.releaseQueue() } }
                    detachVideo(target)
                }
            }
        }
        overlayInput?.let { input ->
            release("overlay-gl") { input.retireGl() }
            release("overlay-queue") {
                if (!main.post { release("overlay-queue") { input.releaseQueue() } })
                    android.util.Log.w("PitwallCast", "BAKED_GPU_OVERLAY_OWNER_STOPPED")
            }
        }
        release("shader") { shader.close() }
    }

    private class Input(width: Int, height: Int, handler: Handler, frameAvailable: () -> Unit) : AutoCloseable {
        private val ownerContext = EGL14.eglGetCurrentContext().also { check(it != EGL14.EGL_NO_CONTEXT) }
        val id = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }.single()
        val matrix = FloatArray(16)
        private val frames: SurfaceTexture
        val surface: Surface
        private var glRetired = false
        private val queueReleased = java.util.concurrent.atomic.AtomicBoolean()

        init {
            var created: SurfaceTexture? = null
            try {
            check(id != 0)
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val actual = SurfaceTexture(id)
            created = actual
            actual.setDefaultBufferSize(width, height)
            actual.setOnFrameAvailableListener({ frameAvailable() }, handler)
            frames = actual
            surface = Surface(actual)
            } catch (error: Exception) {
                runCatching { created?.release() }
                deleteTextureOnOwner()
                throw error
            }
        }

        fun latch() { frames.updateTexImage(); frames.getTransformMatrix(matrix) }

        private fun deleteTextureOnOwner() {
            if (EGL14.eglGetCurrentContext() == ownerContext) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        }

        fun retireGl() {
            if (glRetired) return
            glRetired = true
            try { frames.setOnFrameAvailableListener(null) }
            finally { deleteTextureOnOwner() }
        }

        // SurfaceTexture.release is allowed off the GL thread. No GLES
        // commands here, and the producer window or Canvas lock is retired.
        fun releaseQueue() {
            if (!queueReleased.compareAndSet(false, true)) return
            try { surface.release() } finally { frames.release() }
        }

        override fun close() {
            try { retireGl() } finally { releaseQueue() }
        }
    }
}

/** Uses producer matrices for orientation/crop and HWUI's premultiplied alpha for the overlays. */
internal class BakedCastLayerCompositor(background: Int, private val width: Int, private val height: Int) : AutoCloseable {
    private val ownerContext = EGL14.eglGetCurrentContext().also { check(it != EGL14.EGL_NO_CONTEXT) }
    private var closed = false
    private val red = Color.red(background) / 255f
    private val green = Color.green(background) / 255f
    private val blue = Color.blue(background) / 255f
    private val quad = ByteBuffer.allocateDirect(16 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f))
        rewind()
    }
    private val program = GLES20.glCreateProgram().also { name ->
        try {
            attach(name, GLES20.GL_VERTEX_SHADER, """
                attribute vec2 p; attribute vec2 uv;
                uniform mat4 transform; uniform vec3 fit; varying vec2 coords;
                void main() { gl_Position=vec4(p * fit.z + fit.xy,0.,1.);
                    coords=(transform*vec4(uv,0.,1.)).xy; }
            """.trimIndent())
            attach(name, GLES20.GL_FRAGMENT_SHADER, """
                #extension GL_OES_EGL_image_external : require
                precision mediump float; uniform samplerExternalOES image; varying vec2 coords;
                void main() { gl_FragColor=texture2D(image,coords); }
            """.trimIndent())
            GLES20.glLinkProgram(name)
            val status = IntArray(1)
            GLES20.glGetProgramiv(name, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Cast compositor: ${GLES20.glGetProgramInfoLog(name)}" }
        } catch (error: Exception) {
            GLES20.glDeleteProgram(name)
            throw error
        }
    }
    private val position = GLES20.glGetAttribLocation(program, "p")
    private val uv = GLES20.glGetAttribLocation(program, "uv")
    private val image = GLES20.glGetUniformLocation(program, "image")
    private val fit = GLES20.glGetUniformLocation(program, "fit")
    private val transform = GLES20.glGetUniformLocation(program, "transform")

    fun draw(video: Int, videoMatrix: FloatArray, overlay: Int, overlayMatrix: FloatArray, placement: BakedCastVideoPlacement) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(red, green, blue, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(image, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(uv)
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glDisable(GLES20.GL_BLEND)
        paint(video, videoMatrix, 2f * placement.left + placement.scale - 1f,
            1f - 2f * placement.top - placement.scale, placement.scale)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        paint(overlay, overlayMatrix, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun paint(texture: Int, matrix: FloatArray, x: Float, y: Float, scale: Float) {
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0)
        GLES20.glUniform3f(fit, x, y, scale)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun attach(program: Int, type: Int, source: String) {
        val shader = GLES20.glCreateShader(type)
        try {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Cast compositor: ${GLES20.glGetShaderInfoLog(shader)}" }
            GLES20.glAttachShader(program, shader)
        } finally { GLES20.glDeleteShader(shader) }
    }

    override fun close() {
        if (closed) return
        closed = true
        // A failed EGL rebind must never delete a name from another context.
        // Destruction of the owning context retires its remaining GL objects.
        if (EGL14.eglGetCurrentContext() == ownerContext) GLES20.glDeleteProgram(program)
    }
}
