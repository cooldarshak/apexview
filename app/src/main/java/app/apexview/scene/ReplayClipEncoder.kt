package app.apexview.scene

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
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
import app.apexview.cast.BakedCastTransportStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GL-thread only. Encodes the actual visible clear viewer, either to a clip or a live Cast sink. */
internal class ReplayClipEncoder(
    private val context: Context,
    private val display: EGLDisplay,
    private val glContext: EGLContext,
    config: EGLConfig,
    private val width: Int,
    private val height: Int,
    private val castSample: ((BakedCastTransportStream.Sample) -> Unit)? = null,
) {
    private val resolver = context.contentResolver
    private val name = "Pitwall-replay-${System.currentTimeMillis()}.mp4"
    private var destination: android.net.Uri? = null
    private var published = false
    private var framesWritten = 0L
    private var lastPresentationUs = 0L
    // PixelCopy may still own the bitmap when a clip is cancelled. Let its callback release
    // the final reference instead of recycling the bitmap while that copy is pending.
    private var bitmap: Bitmap? = null
    val pixels: Bitmap get() = bitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
    private var codec: MediaCodec? = null
    private var input: android.view.Surface? = null
    private var muxer: MediaMuxer? = null
    private var egl = EGL14.EGL_NO_SURFACE
    private var track = -1
    private var codecHeaders = ByteArray(0)
    private var textureAllocated = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var texture = 0
    private var program = 0
    private var overlayUniform = -1
    private var positionAttribute = -1
    private var uvAttribute = -1
    private var closed = false
    private var firstNs = -1L
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f))
        rewind()
    }

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
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
            if (castSample == null) {
                val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Pitwall")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }))
                destination = uri
                muxer = checkNotNull(resolver.openFileDescriptor(uri, "rw")).use {
                    MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }
            }
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
            overlayUniform = GLES20.glGetUniformLocation(program, "overlay")
            positionAttribute = GLES20.glGetAttribLocation(program, "p")
            uvAttribute = GLES20.glGetAttribLocation(program, "uv")
            check(overlayUniform >= 0 && positionAttribute >= 0 && uvAttribute >= 0) {
                "Replay encoder shader inputs are unavailable"
            }
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    /** Returns true after submitting the last frame. The display is rebound by the caller. */
    fun frame(timestampNs: Long) = frame(timestampNs) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (textureAllocated) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, pixels)
        } else {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pixels, 0)
            textureAllocated = true
        }
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(overlayUniform, 0)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glEnableVertexAttribArray(uvAttribute)
        quad.position(0)
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(uvAttribute, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun frame(timestampNs: Long, draw: () -> Unit) {
        if (firstNs < 0) firstNs = timestampNs
        check(EGL14.eglMakeCurrent(display, egl, egl, glContext)) { "Encoder surface unavailable" }
        draw()
        val elapsedNs = timestampNs - firstNs
        check(EGLExt.eglPresentationTimeANDROID(display, egl, elapsedNs))
        check(EGL14.eglSwapBuffers(display, egl)) { "Encoder frame submission failed" }
        drain(end = false)
    }

    private fun drain(end: Boolean) {
        val encoder = checkNotNull(codec)
        val info = bufferInfo
        val deadline = System.nanoTime() + 3_000_000_000L
        while (true) {
            when (val index = encoder.dequeueOutputBuffer(info, if (end) 10_000L else 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    check(System.nanoTime() < deadline) { "Encoder did not finish the replay clip" }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (castSample == null) {
                        track = checkNotNull(muxer).addTrack(encoder.outputFormat)
                        checkNotNull(muxer).start()
                    } else {
                        val format = encoder.outputFormat
                        codecHeaders = listOf("csd-0", "csd-1").mapNotNull { name ->
                            format.getByteBuffer(name)?.let { data ->
                                val duplicate = data.duplicate()
                                ByteArray(duplicate.remaining()).also(duplicate::get)
                            }
                        }.fold(ByteArray(0)) { acc, bytes -> acc + BakedAvcAccessUnit.annexB(bytes) }
                    }
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = checkNotNull(encoder.getOutputBuffer(index))
                            if (castSample == null) {
                                check(track >= 0)
                                checkNotNull(muxer).writeSampleData(track, buffer, info)
                            } else {
                                val bytes = ByteArray(info.size)
                                buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(bytes)
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                val picture = BakedAvcAccessUnit.annexB(bytes)
                                castSample.invoke(BakedCastTransportStream.Sample(info.presentationTimeUs,
                                    if (key) codecHeaders + picture else picture, video = true, keyFrame = key))
                            }
                            framesWritten++
                            lastPresentationUs = maxOf(lastPresentationUs, info.presentationTimeUs)
                        }
                    } finally { encoder.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun finish(): String {
        check(castSample == null) { "Live Cast does not produce a recording" }
        checkNotNull(codec).signalEndOfInputStream()
        drain(end = true)
        check(track >= 0 && framesWritten > 0) { "No video frames were encoded" }
        checkNotNull(muxer).stop()
        track = -1
        checkNotNull(muxer).release()
        muxer = null
        val uri = checkNotNull(destination)
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
        published = true
        Log.i("ApexviewSingleDecoder", "F1_REPLAY_CLIP_SAVED source=viewer_window output=${width}x${height} " +
            "videoTracks=1 frames=$framesWritten durationMs=${lastPresentationUs / 1000}")
        return "Saved $name in Movies/Pitwall"
    }

    fun close() {
        if (closed) return
        closed = true
        if (egl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, egl)
        if (program != 0) GLES20.glDeleteProgram(program)
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        runCatching { codec?.release() }
        input?.release()
        if (track >= 0) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        if (!published) destination?.let { runCatching { resolver.delete(it, null, null) } }
    }

    /** Live sessions never publish a MediaStore file; stop without finalizing an MP4. */
    fun stopCast() {
        check(castSample != null)
        try { close() } finally { bitmap?.recycle(); bitmap = null }
    }

}
