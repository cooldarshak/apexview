package app.apexview

/** Main-looper watchdog for a valid direct target with playable video.
 * Network/DRM buffering or an intentional pause never spend the retry budget.
 * No replacement decoder is opened by the watchdog. */
internal class DirectSurfaceRecovery {
    enum class Action { NONE, REBIND, FAILED }
    private var since = -1L
    private var attempted = false
    private var acknowledged = false
    fun newTarget() { since = -1L; attempted = false; acknowledged = false }
    fun acknowledge() { acknowledged = true; since = -1L }
    fun observe(nowMs: Long, eligible: Boolean): Action {
        if (acknowledged || !eligible) { since = -1L; return Action.NONE }
        if (since < 0L || nowMs < since) { since = nowMs; return Action.NONE }
        if (nowMs - since < 8_000L) return Action.NONE
        since = nowMs
        if (attempted) return Action.FAILED
        attempted = true
        return Action.REBIND
    }
}
