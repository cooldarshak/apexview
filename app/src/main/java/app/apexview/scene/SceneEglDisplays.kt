package app.apexview.scene

import android.opengl.EGL14
import android.opengl.EGLDisplay

/** Process-local accounting for the app's own EGL display initializations.
 * Contexts and windows remain independent, including protected contexts.
 * Each successful acquisition has exactly one matching release, even when
 * a replacement viewer and an encoder overlap on different GL loopers. */
internal class DisplayLeaseRegistry<D : Any>(
    private val initialize: (D) -> Boolean,
    private val terminate: (D) -> Boolean,
) {
    private val owners = mutableMapOf<D, Int>()

    @Synchronized fun acquire(display: D): Lease<D> {
        val count = owners[display] ?: 0
        if (count == 0) check(initialize(display)) { "EGL display initialization failed" }
        owners[display] = Math.addExact(count, 1)
        return Lease(display, this)
    }

    @Synchronized private fun release(display: D) {
        val count = checkNotNull(owners[display])
        if (count > 1) owners[display] = count - 1
        else {
            owners.remove(display)
            check(terminate(display)) { "EGL display termination failed" }
        }
    }

    @Synchronized fun count(display: D): Int = owners[display] ?: 0

    class Lease<D : Any> internal constructor(val display: D, private val registry: DisplayLeaseRegistry<D>) : AutoCloseable {
        private var closed = false
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            registry.release(display)
        }
    }
}

internal object SceneEglDisplays {
    private val registry = DisplayLeaseRegistry<EGLDisplay>(
        initialize = { display -> EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0) },
        terminate = EGL14::eglTerminate,
    )
    fun acquire(): DisplayLeaseRegistry.Lease<EGLDisplay> {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        return registry.acquire(display)
    }
}
