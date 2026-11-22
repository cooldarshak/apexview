package app.apexview.scene

/** 25 fps Cast encoder pacing, independent of the 50 fps HEVC video layer. */
internal object BakedCastCapturePacing {
    private const val FRAME_NS = 40_000_000L
    fun delayMs(requestedAtNs: Long, completedAtNs: Long): Long {
        if (completedAtNs <= requestedAtNs) return 40L
        return ((FRAME_NS - (completedAtNs - requestedAtNs)).coerceAtLeast(0L)) / 1_000_000L
    }
}
