package app.apexview

/** Update progress is sampled, never posted on every 64KB read. */
internal object UpdateProgressPolicy {
    fun shouldPublish(fraction: Float, lastReported: Float, elapsedMs: Long): Boolean {
        if (!fraction.isFinite() || fraction !in 0f..1f) return false
        if (lastReported < 0f) return true
        if (fraction >= 1f && lastReported < 1f) return true
        if (fraction <= lastReported) return false
        return fraction - lastReported >= 0.05f || elapsedMs >= 250L
    }
}
