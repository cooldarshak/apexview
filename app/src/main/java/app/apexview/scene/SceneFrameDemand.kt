package app.apexview.scene

/**
 * Coalesce decoder notifications into one scene update per display frame. All calls happen
 * on the scene GL looper; this class deliberately does not own a thread or another player.
 */
internal class SceneFrameDemand {
    private var callbackScheduled = false
    private var videoPending = false

    fun videoArrived(): Boolean {
        videoPending = true
        return requestDraw()
    }

    /** Returns true only for the first outstanding request. */
    fun requestDraw(): Boolean {
        if (callbackScheduled) return false
        callbackScheduled = true
        return true
    }

    /** Leave both flags unchanged when the vsync pacing gate postpones a draw. */
    fun hasPendingVideo(): Boolean = callbackScheduled && videoPending

    /** Consume the latest decoder image once, even when several notifications arrived. */
    fun beginFrame(): Boolean {
        check(callbackScheduled) { "Scene frame was not requested" }
        callbackScheduled = false
        return videoPending.also { videoPending = false }
    }

    /**
     * A surface/context transition can temporarily reject a scheduled GL draw.
     * Preserve its decoder-picture demand until the next explicit surface/bind/video
     * notification, rather than silently dropping the last frame during a pause.
     * This does NOT reschedule a failing surface in a hot Choreographer loop.
     */
    fun onDrawUnavailable(hadNewVideo: Boolean) {
        check(!callbackScheduled) { "Unavailable draw must follow beginFrame()" }
        videoPending = videoPending || hadNewVideo
    }

    fun reset() {
        callbackScheduled = false
        videoPending = false
    }
}
