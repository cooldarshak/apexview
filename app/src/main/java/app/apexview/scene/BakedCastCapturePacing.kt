package app.apexview.scene

/** 25 fps Cast encoder pacing, independent of the 50 fps HEVC video layer. */
internal object BakedCastCapturePacing {
    private const val FRAME_NS = 40_000_000L
    /** Back off on sustained capture cost; no overlapping requests. */
    class Controller {
        private var averageMs = 40.0
        fun nextDelayMs(requestedAtNs: Long, completedAtNs: Long): Long {
            val elapsed = ((completedAtNs - requestedAtNs).coerceAtLeast(0L) / 1_000_000.0).coerceAtMost(250.0)
            averageMs = averageMs * 0.85 + elapsed * 0.15
            val interval = (averageMs * 1.1).coerceIn(40.0, 67.0)
            return (interval - elapsed).toLong().coerceAtLeast(if (averageMs > 40.0) 4L else 0L)
        }
    }
    fun delayMs(requestedAtNs: Long, completedAtNs: Long): Long {
        if (completedAtNs <= requestedAtNs) return 40L
        return ((FRAME_NS - (completedAtNs - requestedAtNs)).coerceAtLeast(0L)) / 1_000_000L
    }
}

/** Baked video and the unmodified AAC both use source media time at every playback speed. */
internal class BakedCastMediaClock {
    var originMs: Long? = null
        private set
    private var lastPositionMs = -1L

    fun canCapture(positionMs: Long) = positionMs > lastPositionMs

    fun timestampNs(positionMs: Long): Long {
        require(canCapture(positionMs))
        val origin = originMs ?: positionMs
        val timestamp = Math.multiplyExact(positionMs - origin, 1_000_000L)
        originMs = origin
        lastPositionMs = positionMs
        return timestamp
    }
}
