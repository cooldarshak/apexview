package app.apexview.cast

import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import app.apexview.AppState
import app.apexview.scene.SingleDecoderSceneView
import java.io.Closeable

/** Owns the encoder, its clear scene attachment and the resulting video playlist. */
internal class FinishedCastEncodingSession(
    private val scene: SingleDecoderSceneView,
    width: Int,
    height: Int,
    frameRate: Int,
    bitRate: Int,
    private val onFailure: (String) -> Unit,
) : Closeable {
    val video = FinishedCastVideoPlaylist()
    private val overlay = FinishedCastOverlay(width, height)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("ApexViewCastEncoder").apply { start() }
    private val worker = Handler(thread.looper)
    private val fragments = FinishedCastVideoFragments(video)
    private var encoder: FinishedCastVideoEncoder? = null // accessed on worker only
    private var closed = false // accessed on main only
    private var attached = false

    init {
        worker.post {
            try {
                val created = FinishedCastVideoEncoder(worker, width, height, frameRate, bitRate,
                    object : FinishedCastVideoEncoder.Output {
                        override fun onFormat(format: MediaFormat) = fragments.format(format)
                        override fun onSample(data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) =
                            fragments.sample(data, presentationTimeUs, keyFrame)
                        override fun onEnd() {
                            try {
                                fragments.finish(1_000_000L / frameRate)
                            } finally {
                                releaseOnWorker()
                            }
                        }

                        override fun onError(error: Exception) {
                            fail(error.message ?: "Cast encoder failed")
                        }
                    })
                encoder = created
                main.post {
                    if (!closed) {
                        try {
                            scene.attachEncoderOutput(created.inputSurface, width, height,
                                { overlay.draw() }, ::fail, overlay::releaseGl)
                            attached = true
                        } catch (error: Exception) {
                            fail(error.message ?: "Cast renderer is unavailable")
                        }
                    }
                }
            } catch (error: Exception) {
                fail(error.message ?: "Unable to start Cast encoder")
            }
        }
    }

    fun updateOverlays(app: AppState) {
        check(Looper.myLooper() == main.looper)
        if (!closed) overlay.update(app, scene.width.toFloat(), scene.height.toFloat())
    }

    private fun fail(reason: String) {
        main.post {
            if (!closed) {
                close()
                onFailure(reason)
            }
        }
    }

    override fun close() {
        check(Looper.myLooper() == main.looper)
        if (closed) return
        closed = true
        if (attached) scene.detachEncoderOutput(::finishEncoderInput)
        else worker.post { releaseOnWorker() }
    }

    private fun finishEncoderInput() {
        worker.post {
            val current = encoder ?: return@post
            try {
                current.finishInput()
                worker.postDelayed({ releaseOnWorker() }, 2_000L)
            } catch (_: Exception) {
                releaseOnWorker()
            }
        }
    }

    private fun releaseOnWorker() {
        val current = encoder
        encoder = null
        try {
            current?.close()
        } finally {
            video.finish()
            thread.quitSafely()
        }
    }
}
