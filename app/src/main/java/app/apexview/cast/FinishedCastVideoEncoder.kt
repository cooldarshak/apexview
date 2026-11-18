package app.apexview.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.io.Closeable

/**
 * Encodes a clear compositor output into one AVC track. The renderer supplies frame timestamps
 * through EGL. The callback owner packages these samples together with the selected audio.
 *
 * Create and close on the supplied handler's thread. Stop rendering to [inputSurface] before
 * closing. This encoder's surface belongs to a clear composition and must never be attached to
 * a protected scene.
 */
internal class FinishedCastVideoEncoder(
    private val handler: Handler,
    width: Int,
    height: Int,
    frameRate: Int,
    bitRate: Int,
    private val output: Output,
) : Closeable {
    interface Output {
        fun onFormat(format: MediaFormat)
        fun onSample(data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean)
        fun onEnd()
        fun onError(error: Exception)
    }

    private val codec: MediaCodec
    val inputSurface: Surface
    private var closed = false
    private var endRequested = false
    private var failed = false

    init {
        checkOwnerThread()
        require(width > 0 && height > 0 && frameRate > 0 && bitRate > 0)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        val hardware = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format)
        } ?: error("No hardware AVC encoder supports ${width}x${height} at $frameRate fps.")

        codec = MediaCodec.createByCodecName(hardware.name)
        var surface: Surface? = null
        try {
            codec.setCallback(callback(), handler)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = codec.createInputSurface()
            surface = input
            codec.start()
            inputSurface = input
        } catch (error: Exception) {
            try {
                surface?.release()
            } finally {
                codec.release()
            }
            throw error
        }
    }

    private fun callback() = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (!closed && !failed) deliver { output.onFormat(format) }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (closed) return
            deliver {
                val flags = info.flags
                val pts = info.presentationTimeUs
                val sample = try {
                    if (failed || info.size == 0 || flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        null
                    } else {
                        val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                        buffer.clear()
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        ByteArray(info.size).also { buffer.get(it) }
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
                if (sample != null) output.onSample(sample, pts, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                if (!closed && !failed && flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) output.onEnd()
            }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) = reportError(error)
    }

    fun finishInput() {
        checkOwnerThread()
        check(!closed && !failed)
        if (endRequested) return
        codec.signalEndOfInputStream()
        endRequested = true
    }

    override fun close() {
        checkOwnerThread()
        if (closed) return
        closed = true
        try {
            codec.release()
        } finally {
            inputSurface.release()
        }
    }

    private fun checkOwnerThread() {
        check(Looper.myLooper() == handler.looper) { "Cast encoder must run on its codec handler." }
    }

    private inline fun deliver(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            reportError(error)
        }
    }

    private fun reportError(error: Exception) {
        if (closed || failed) return
        failed = true
        output.onError(error)
    }
}
