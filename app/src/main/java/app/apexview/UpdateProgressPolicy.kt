package app.apexview

/** Update progress is sampled, never posted on every 64KB read. */
internal object UpdateProgressPolicy {
    fun shouldPublish(fraction: Float, lastReported: Float, elapsedMs: Long): Boolean {
        if (!fraction.isFinite() || fraction !in 0f..1f) return false
        if (lastReported < 0f) return true
        if (fraction >= 1f && lastReported < 1f) return true
        if (fraction <= lastReported) return false
        // 0.06f - 0.01f may round to 0.049999997f. Do not
        // skip exactly-5% updates because IEEE-754 rounded downward.
        return fraction + 0.000001f >= lastReported + 0.05f || elapsedMs >= 250L
    }
}
