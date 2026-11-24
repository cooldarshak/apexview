package app.apexview

/** Media3 state episodes, deliberately not a network-starvation classifier. */
internal class PlaybackBufferEpisodes {
    enum class Phase { STARTUP, SEEK, REBUFFER, PAUSED_BUFFERING }
    data class Episode(val phase: Phase, val durationMs: Long)
    private var readyOnce = false
    private var seekAtMs: Long? = null
    private var startedAt: Long? = null
    private var phase: Phase = Phase.STARTUP
    fun onSeek(nowMs: Long) {
        seekAtMs = nowMs
        if (startedAt != null && readyOnce) phase = Phase.SEEK
    }
    fun onState(nowMs: Long, buffering: Boolean, ready: Boolean, playRequested: Boolean): Episode? {
        if (buffering) {
            if (startedAt == null) {
                startedAt = nowMs
                phase = when {
                    !readyOnce -> Phase.STARTUP
                    seekAtMs?.let { nowMs - it in 0L..500L } == true -> Phase.SEEK
                    playRequested -> Phase.REBUFFER
                    else -> Phase.PAUSED_BUFFERING
                }
            }
            return null
        }
        val start = startedAt
        startedAt = null
        if (ready) readyOnce = true
        seekAtMs = null
        return start?.let { Episode(phase, (nowMs - it).coerceAtLeast(0L)) }
    }
}

/** Terminal resource release; never suppresses the first actual failure. */
internal class PlaybackReleaseGate {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    fun beginRelease(): Boolean = released.compareAndSet(false, true)
}
