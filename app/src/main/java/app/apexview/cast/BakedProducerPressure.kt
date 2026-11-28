package app.apexview.cast

/** Source-clock admission only. Never discards an encoded dependent sample.
 * Caller owns the player and must respect user pause/terminal generations. */
internal class BakedProducerPressure {
    enum class State { RUN, HOLD, EXPIRED }
    private var sinceMs: Long? = null

    fun update(nowMs: Long, high: Boolean, low: Boolean, userPlaying: Boolean = true): State {
        require(nowMs >= 0L)
        // Deliberate pause is not a transport stall. Resume gets a fresh hold deadline.
        if (!userPlaying) { sinceMs = null; return State.RUN }
        val since = sinceMs
        if (since == null) {
            if (!high) return State.RUN
            sinceMs = nowMs
            return State.HOLD
        }
        if (low) {
            sinceMs = null
            return State.RUN
        }
        return if (nowMs < since || nowMs - since >= 10_000L) State.EXPIRED else State.HOLD
    }
}
