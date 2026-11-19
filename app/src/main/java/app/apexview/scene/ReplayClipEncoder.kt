package app.apexview.scene

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.provider.MediaStore
import android.util.Log
import app.apexview.SessionTiming
import app.apexview.carsAt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GL-thread only. Encodes a short, video-only snapshot of an already-authorized clear replay. */
internal class ReplayClipEncoder(
    private val context: Context,
    private val display: EGLDisplay,
    private val glContext: EGLContext,
    config: EGLConfig,
    private val timing: SessionTiming,
) {
    private val file = File.createTempFile("pitwall-replay-", ".mp4", context.cacheDir)
    private val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var codec: MediaCodec? = null
    private var input: android.view.Surface? = null
    private var muxer: MediaMuxer? = null
    private var egl = EGL14.EGL_NO_SURFACE
    private var track = -1
    private var texture = 0
    private var program = 0
    private var firstNs = -1L
    private var lastNs = -1L
    private var lastOverlayMs = Long.MIN_VALUE
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f))
        rewind()
    }

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 25)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = encoder.createInputSurface()
            encoder.start()
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            egl = EGL14.eglCreateWindowSurface(display, config, input, intArrayOf(EGL14.EGL_NONE), 0)
            check(egl != EGL14.EGL_NO_SURFACE) { "Could not create the encoder surface" }
            program = GLES20.glCreateProgram()
            fun attach(type: Int, source: String) {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                GLES20.glAttachShader(program, shader)
                GLES20.glDeleteShader(shader)
            }
            attach(GLES20.GL_VERTEX_SHADER, "attribute vec2 p;attribute vec2 uv;varying mediump vec2 t;void main(){gl_Position=vec4(p,0.,1.);t=uv;}")
            attach(GLES20.GL_FRAGMENT_SHADER, "precision mediump float;uniform sampler2D overlay;varying mediump vec2 t;void main(){gl_FragColor=texture2D(overlay,t);}")
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { "Could not link replay overlay shader" }
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    /** Returns true after submitting the last frame. The display is rebound by the caller. */
    fun frame(timestampNs: Long, clockMs: Long, aspect: Float, drawVideo: () -> Unit): Boolean {
        if (firstNs < 0) firstNs = timestampNs
        if (lastNs >= 0 && timestampNs - lastNs < 37_000_000L) return false
        check(EGL14.eglMakeCurrent(display, egl, egl, glContext)) { "Encoder surface unavailable" }
        val elapsedNs = timestampNs - firstNs
        GLES20.glViewport(0, 0, WIDTH, HEIGHT)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val videoWidth = minOf(900, (506 * aspect).toInt().coerceAtLeast(1))
        val videoHeight = minOf(506, (900 / aspect).toInt().coerceAtLeast(1))
        GLES20.glViewport((900 - videoWidth) / 2, 214 + (506 - videoHeight) / 2, videoWidth, videoHeight)
        drawVideo()
        GLES20.glViewport(0, 0, WIDTH, HEIGHT)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (clockMs != lastOverlayMs) {
            drawOverlay(clockMs)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            lastOverlayMs = clockMs
        }
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "overlay"), 0)
        val p = GLES20.glGetAttribLocation(program, "p")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        GLES20.glEnableVertexAttribArray(p)
        GLES20.glEnableVertexAttribArray(uv)
        quad.position(0)
        GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        check(EGLExt.eglPresentationTimeANDROID(display, egl, elapsedNs))
        check(EGL14.eglSwapBuffers(display, egl)) { "Encoder frame submission failed" }
        lastNs = timestampNs
        drain(end = false)
        return elapsedNs >= 10_000_000_000L
    }

    private fun drawOverlay(now: Long) {
        bitmap.eraseColor(Color.TRANSPARENT)
        paint.color = Color.rgb(20, 23, 29)
        canvas.drawRect(900f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        canvas.drawRect(0f, 506f, 900f, HEIGHT.toFloat(), paint)
        paint.typeface = Typeface.MONOSPACE
        paint.textSize = 20f
        paint.color = Color.WHITE
        canvas.drawText("TIMING TOWER", 915f, 29f, paint)
        canvas.drawText("TRACK MAP", 18f, 536f, paint)
        val rows = timing.laps.mapNotNull { (number, laps) -> laps.at(now, includeRunningTime = false)?.let { number to it } }
            .sortedBy { it.second.position ?: Int.MAX_VALUE }
        rows.take(22).forEachIndexed { i, (number, lap) ->
            val y = 56f + 29f * i
            val driver = timing.drivers[number]
            val rank = lap.position?.toString().orEmpty().padStart(2)
            paint.color = driver?.color ?: Color.WHITE
            canvas.drawText("$rank  ${driver?.tla ?: number}", 916f, y, paint)
            paint.color = Color.WHITE
            canvas.drawText(if (lap.inPit) "PIT" else lap.toLeader ?: lap.gap ?: lap.lastLap?.time ?: "", 1055f, y, paint)
        }
        val xs = timing.outline.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = timing.outline.filterIndexed { i, _ -> i % 2 != 0 }
        val minX = xs.minOrNull() ?: return
        val minY = ys.minOrNull() ?: return
        val spanX = (xs.maxOrNull()!! - minX).coerceAtLeast(1f)
        val spanY = (ys.maxOrNull()!! - minY).coerceAtLeast(1f)
        val scale = minOf(660f / spanX, 163f / spanY)
        fun x(value: Float) = 508f + (value - minX - spanX / 2f) * scale
        fun y(value: Float) = 613f - (value - minY - spanY / 2f) * scale
        val path = Path().apply {
            moveTo(x(xs.first()), y(ys.first()))
            for (i in 1 until xs.size) lineTo(x(xs[i]), y(ys[i]))
            close()
        }
        paint.color = Color.LTGRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        carsAt(timing, now).forEach { car ->
            paint.color = timing.drivers[car.number]?.color ?: Color.WHITE
            val carX = x(car.x)
            val carY = y(car.y)
            if (carX in 5f..894f && carY in 511f..714f) {
                canvas.drawCircle(carX, carY, 5f, paint)
                paint.textSize = 11f
                canvas.drawText(timing.drivers[car.number]?.tla ?: car.number, carX + 7f, carY + 4f, paint)
            }
        }
    }

    private fun drain(end: Boolean) {
        val encoder = checkNotNull(codec)
        val output = checkNotNull(muxer)
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (true) {
            when (val index = encoder.dequeueOutputBuffer(info, if (end) 10_000L else 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    check(System.nanoTime() < deadline) { "Encoder did not finish the replay clip" }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = output.addTrack(encoder.outputFormat)
                    output.start()
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(track >= 0)
                            output.writeSampleData(track, checkNotNull(encoder.getOutputBuffer(index)), info)
                        }
                    } finally { encoder.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun finish(): String {
        checkNotNull(codec).signalEndOfInputStream()
        drain(end = true)
        check(track >= 0) { "No video frames were encoded" }
        checkNotNull(muxer).stop()
        track = -1
        val resolver = context.contentResolver
        val name = "Pitwall-replay-${System.currentTimeMillis()}.mp4"
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Pitwall")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        Log.i("ApexviewSingleDecoder", "F1_REPLAY_CLIP_SAVED timing=baked map=baked videoTracks=1")
        return "Saved $name in Movies/Pitwall"
    }

    fun close() {
        if (egl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, egl)
        if (program != 0) GLES20.glDeleteProgram(program)
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        runCatching { codec?.release() }
        input?.release()
        if (track >= 0) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        bitmap.recycle()
        file.delete()
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
    }
}
