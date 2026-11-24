package app.apexview

/**
 * Independently testable frame-drop pressure policy for the EXPLICIT multiple
 * decoder preference. Single-decoder composition always uses its own one-codec
 * policy and cannot be converted into per-feed decoders by this class.
 *
 * The sampling caller runs every 500ms. One dropped-buffer sample is taken
 * every two stable ticks, after 10s of the same ready/playing feed set.
 */
internal class PlaybackStrainMonitor {
    data class Sample(val key: Any, val readyAndPlaying: Boolean, val droppedFrames: Int)

    private val struggledAt = mutableMapOf<Int, Float>()
    private val dropsBySecond = ArrayDeque<Int>()
    private var lastDropTotal = -1
    private var strainTicks = 0
    private var steadyFeedKeys: List<Any> = emptyList()
    private var steadySinceMs = 0L

    fun wasStrainedAt(feedCount: Int, speed: Float): Boolean =
        (struggledAt[feedCount] ?: Float.MAX_VALUE) <= speed

    fun reset() {
        dropsBySecond.clear()
        lastDropTotal = -1
    }

    fun onTick(nowMs: Long, speed: Float, selectedFeedCount: Int, feeds: List<Sample>): Boolean {
        if (speed <= 1f || feeds.size < 2) {
            reset()
            return false
        }
        val keys = feeds.map(Sample::key)
        if (keys != steadyFeedKeys || feeds.any { !it.readyAndPlaying }) {
            steadyFeedKeys = keys
            steadySinceMs = nowMs
            reset()
            return false
        }
        if (nowMs - steadySinceMs < STEADY_MS || ++strainTicks % 2 != 0) return false
        val total = feeds.sumOf(Sample::droppedFrames)
        val previous = lastDropTotal
        lastDropTotal = total
        if (previous < 0) return false
        dropsBySecond.addLast((total - previous).coerceAtLeast(0))
        if (dropsBySecond.size > STRAIN_WINDOW_S) dropsBySecond.removeFirst()
        if (dropsBySecond.count { it >= STRAIN_DROPS } < STRAIN_SECONDS) return false
        struggledAt[selectedFeedCount] = minOf(struggledAt[selectedFeedCount] ?: Float.MAX_VALUE, speed)
        reset()
        return true
    }

    private companion object {
        const val STRAIN_DROPS = 8
        const val STRAIN_SECONDS = 3
        const val STRAIN_WINDOW_S = 5
        const val STEADY_MS = 10_000L
    }
}
