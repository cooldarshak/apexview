package app.apexview

import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.ui.PlayerView

/** Main-thread SurfaceHolder generation. A Surface object can be reused across
 * destroy/create, so object identity alone cannot acknowledge a new producer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class DirectSurfaceLifecycle {
    private var holder: SurfaceHolder? = null
    private var callback: SurfaceHolder.Callback? = null
    var attachedAtMs = Long.MAX_VALUE
        private set
    val surface get() = holder?.surface?.takeIf { it.isValid }
    val recovery = DirectSurfaceRecovery()

    fun attach(view: PlayerView) {
        close()
        val next = (view.videoSurfaceView as? SurfaceView)?.holder ?: return
        holder = next
        reset()
        val listener = object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { if (holder === h && callback === this) reset() }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(h: SurfaceHolder) { if (holder === h && callback === this) reset() }
        }
        callback = listener
        next.addCallback(listener)
    }
    fun reset() {
        attachedAtMs = SystemClock.elapsedRealtime()
        recovery.newTarget()
    }
    fun rebindStarted() { attachedAtMs = SystemClock.elapsedRealtime() }
    fun acknowledge(output: Any, renderTimeMs: Long): Boolean {
        if (surface !== output || renderTimeMs < attachedAtMs) return false
        recovery.acknowledge()
        return true
    }
    fun close() {
        val old = holder
        val listener = callback
        holder = null; callback = null
        attachedAtMs = Long.MAX_VALUE
        recovery.newTarget()
        if (old != null && listener != null) old.removeCallback(listener)
    }
}
