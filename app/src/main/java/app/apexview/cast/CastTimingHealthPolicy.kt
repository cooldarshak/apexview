package app.apexview.cast

/**
 * Interprets receiver telemetry freshness without confusing a paused/buffering video
 * with transport failure. Map packets arrive every 200ms; complete tower snapshots
 * are sent on change and forced again every five seconds.
 */
internal object CastTimingHealthPolicy {
    fun healthy(
        frameAgeMs: Long,
        tableAgeMs: Long,
        mapEnabled: Boolean,
        towerEnabled: Boolean,
        videoAdvancing: Boolean,
    ): Boolean {
        if (!videoAdvancing) return true
        if (mapEnabled && frameAgeMs !in 0..1_500L) return false
        if (towerEnabled && tableAgeMs !in 0..10_000L) return false
        return true
    }
}
