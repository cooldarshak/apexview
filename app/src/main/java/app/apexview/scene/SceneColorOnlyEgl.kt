package app.apexview.scene

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log

/** EGL chooses a configuration with minimum requested sizes, not exact ones.
 * These full-frame video shaders use no depth, stencil or multisampling.
 * Prefer an attachment-free equivalent, retaining the original selection if
 * no demonstrably compatible alternative exists. Never alter DRM attributes. */
internal object SceneColorOnlyEgl {
    private const val RECORDABLE_ANDROID = 0x3142
    private val compatibility = intArrayOf(
        EGL14.EGL_RED_SIZE, EGL14.EGL_GREEN_SIZE, EGL14.EGL_BLUE_SIZE, EGL14.EGL_ALPHA_SIZE,
        EGL14.EGL_BUFFER_SIZE, EGL14.EGL_COLOR_BUFFER_TYPE, EGL14.EGL_NATIVE_VISUAL_ID,
        EGL14.EGL_CONFIG_CAVEAT, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_CONFORMANT,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_LEVEL,
    )
    private val attachments = intArrayOf(EGL14.EGL_DEPTH_SIZE, EGL14.EGL_STENCIL_SIZE,
        EGL14.EGL_SAMPLE_BUFFERS, EGL14.EGL_SAMPLES)

    private fun attribute(display: EGLDisplay, config: EGLConfig, key: Int): Int? {
        val out = IntArray(1)
        if (!EGL14.eglGetConfigAttrib(display, config, key, out, 0)) {
            EGL14.eglGetError()
            return null
        }
        return out[0]
    }
    private fun values(display: EGLDisplay, config: EGLConfig, keys: IntArray): List<Int>? =
        keys.map { attribute(display, config, it) ?: return null }

    fun choose(display: EGLDisplay, attributes: IntArray, label: String): EGLConfig? {
        val first = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, first, 0, 1, count, 0) || count[0] == 0) {
            EGL14.eglGetError()
            return null
        }
        val baseline = first[0] ?: return null
        val old = values(display, baseline, attachments)
        val signature = values(display, baseline, compatibility)
        val recordable = attribute(display, baseline, RECORDABLE_ANDROID)
        var selected = baseline
        // Do not scan configurations on every frame. Only do this once at EGL creation,
        // and only when the original selection actually has unnecessary attachments.
        if (old != null && old.any { it != 0 } && signature != null) {
            val candidates = arrayOfNulls<EGLConfig>(128)
            if (EGL14.eglChooseConfig(display, attributes, 0, candidates, 0, candidates.size, count, 0)) {
                for (candidate in candidates.take(count[0].coerceIn(0, candidates.size)).filterNotNull()) {
                    if (values(display, candidate, compatibility) == signature &&
                        attribute(display, candidate, RECORDABLE_ANDROID) == recordable &&
                        values(display, candidate, attachments) == listOf(0, 0, 0, 0)) {
                        selected = candidate
                        break
                    }
                }
            } else EGL14.eglGetError()
        }
        Log.i("ApexviewSingleDecoder", "SCENE_COLOR_CONFIG owner=$label baselineAttachments=$old selectedAttachments=${values(display, selected, attachments)} colorSignature=$signature recordable=$recordable changed=${selected != baseline} bytes=NOT_MEASURED")
        return selected
    }

    /** The video renderer clears every pixel before every swap, so preserved
     * back-buffer contents have no consumer. Ask EGL not to retain them.
     * An unsupported driver keeps its original behavior. */
    fun discardAfterSwap(display: EGLDisplay, surface: EGLSurface, label: String) {
        if (surface == EGL14.EGL_NO_SURFACE) return
        val value = IntArray(1)
        val known = EGL14.eglQuerySurface(display, surface, EGL14.EGL_SWAP_BEHAVIOR, value, 0)
        if (!known) EGL14.eglGetError()
        val before = if (known) value[0] else -1
        val accepted = before == EGL14.EGL_BUFFER_DESTROYED ||
            EGL14.eglSurfaceAttrib(display, surface, EGL14.EGL_SWAP_BEHAVIOR, EGL14.EGL_BUFFER_DESTROYED)
        if (!accepted) EGL14.eglGetError()
        Log.i("ApexviewSingleDecoder", "SCENE_SWAP_RETENTION owner=$label before=$before discardAccepted=$accepted bytes=NOT_MEASURED")
    }
}
