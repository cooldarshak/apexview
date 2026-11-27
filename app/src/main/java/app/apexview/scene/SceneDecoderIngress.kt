package app.apexview.scene

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * Bounds pending SurfaceTexture notifications even while EGL swap blocks.
 * Only the existing GL owner may latch or sample the texture. Notifications
 * are not decoded frames and coalescence is not a physical-drop measurement.
 */
internal class SceneDecoderIngress(
    private val gl: Handler,
    private val consume: (Long, Long, Long) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("PitwallDecoderIngress", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    val handler = Handler(thread.looper)
    private val lock = Any()
    private var closed = false
    private var count = 0L
    private var first = 0L
    private var last = 0L
    private val drain = Runnable {
        val batch = synchronized(lock) {
            if (closed || count == 0L) null
            else Triple(count, first, last).also { count = 0L }
        }
        if (batch != null) consume(batch.first, batch.second, batch.third)
    }
    fun offer() {
        synchronized(lock) {
            if (closed) return
            val now = System.nanoTime()
            if (count == 0L) first = now
            last = now
            count++
            if (count == 1L && !gl.post(drain)) count = 0L
        }
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            count = 0L
            gl.removeCallbacks(drain)
        }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
