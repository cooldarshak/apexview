package app.apexview.scene

/**
 * Coalesce decoder notifications into one scene update per display frame. All calls happen
 * on the scene GL looper; this class deliberately does not own a thread or another player.
 */
internal class SceneFrameDemand {
    private var callbackScheduled = false
    private var videoPending = false
    private var pendingSinceNs = 0L
    private var coalescedPending = false
    private var consumedSinceNs = 0L
    private var consumedCoalesced = false
    // Multiple latched images can collapse into one display submission. This
    // measures coalescing, not codec drops or physical SurfaceFlinger loss.
    private var coalescedVideoArrivals = 0L

    fun videoArrived(nowNs: Long = 0L): Boolean {
        if (videoPending) { coalescedVideoArrivals++; coalescedPending = true } else pendingSinceNs = nowNs
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

    fun hasCoalescedPendingVideo(): Boolean = videoPending && coalescedPending

    fun pendingAgeNs(nowNs: Long): Long =
        if (videoPending && pendingSinceNs > 0L && nowNs >= pendingSinceNs) nowNs - pendingSinceNs else 0L

    /** Consume the latest decoder image once, even when several notifications arrived. */
    fun beginFrame(): Boolean {
        check(callbackScheduled) { "Scene frame was not requested" }
        callbackScheduled = false
        consumedSinceNs = pendingSinceNs
        consumedCoalesced = coalescedPending
        return videoPending.also { discardPendingVideo() }
    }

    /**
     * A surface/context transition can temporarily reject a scheduled GL draw.
     * Preserve its decoder-picture demand until the next explicit surface/bind/video
     * notification, rather than silently dropping the last frame during a pause.
     * This does NOT reschedule a failing surface in a hot Choreographer loop.
     */
    fun onDrawUnavailable(hadNewVideo: Boolean) {
        check(!callbackScheduled) { "Unavailable draw must follow beginFrame()" }
        if (hadNewVideo) {
            videoPending = true
            pendingSinceNs = consumedSinceNs
            coalescedPending = consumedCoalesced
        }
    }

    /** Old input pixels cannot satisfy a new atlas epoch; preserve the posted callback. */
    fun discardPendingVideo() { videoPending = false; pendingSinceNs = 0L; coalescedPending = false }

    fun takeCoalescedVideoArrivals(): Long = coalescedVideoArrivals.also { coalescedVideoArrivals = 0L }

    fun reset() {
        callbackScheduled = false
        videoPending = false
        coalescedVideoArrivals = 0L
        discardPendingVideo()
        consumedSinceNs = 0L
        consumedCoalesced = false
    }
}
