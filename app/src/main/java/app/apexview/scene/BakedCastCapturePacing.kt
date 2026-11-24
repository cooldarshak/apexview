package app.apexview.scene

/** 25 fps Cast encoder pacing, independent of the 50 fps HEVC video layer. */
internal object BakedCastCapturePacing {
    private const val FRAME_NS = 40_000_000L
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
