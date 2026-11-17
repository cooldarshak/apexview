package app.apexview.cast.local

/** Controller time continues while every local media track is stopped. Receiver reports correct it. */
internal class RemotePlaybackClock(private val nowMs: () -> Long) {
    private var position = 0L
    private var stamp = 0L
    private var running = false
    private var rate = 1f
    private var duration = Long.MAX_VALUE
    fun positionMs(): Long = (position + if (running) ((nowMs() - stamp).coerceAtLeast(0) * rate).toLong() else 0L).coerceIn(0L, duration)
    fun update(timeMs: Long, playing: Boolean, speed: Float, durationMs: Long = duration) {
        position = timeMs.coerceAtLeast(0L)
        stamp = nowMs()
        running = playing
        rate = speed.takeIf { it.isFinite() && it in 0.25f..4f } ?: 1f
        duration = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
    }
}
