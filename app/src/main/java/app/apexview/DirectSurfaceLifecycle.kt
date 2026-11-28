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
    private val epoch = DirectSurfaceEpoch()
    val tracksSurface get() = holder != null
    val surface get() = if (epoch.available) holder?.surface?.takeIf { it.isValid } else null
    val recovery = DirectSurfaceRecovery()

    fun attach(view: PlayerView) {
        close()
        val next = (view.videoSurfaceView as? SurfaceView)?.holder ?: return
        holder = next
        if (next.surface.isValid) created() else destroyed()
        val listener = object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { if (holder === h && callback === this) created() }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(h: SurfaceHolder) { if (holder === h && callback === this) destroyed() }
        }
        callback = listener
        next.addCallback(listener)
    }
    private fun created() {
        epoch.created(SystemClock.elapsedRealtime())
        recovery.newTarget()
    }
    private fun destroyed() {
        epoch.destroyed()
        recovery.newTarget()
    }
    fun rebindStarted() { epoch.rebind(SystemClock.elapsedRealtime()) }
    fun acknowledge(output: Any, renderTimeMs: Long): Boolean {
        if (!epoch.accepts(surface === output, renderTimeMs)) return false
        recovery.acknowledge()
        return true
    }
    fun close() {
        val old = holder
        val listener = callback
        holder = null; callback = null
        destroyed()
        if (old != null && listener != null) old.removeCallback(listener)
    }
}
