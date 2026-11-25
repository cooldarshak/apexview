package app.apexview.tiled

/** Hysteresis affects optional work and unused backing only. Raw OS admission
 * remains in charge of mandatory playback and native validation. */
internal class MediaPressurePolicy(private val cooldownMs: Long = 30_000,
                                   private val recoveryMs: Long = 10_000) {
    data class Decision(val constrained: Boolean, val trimUnused: Boolean)
    private var constrained = false
    private var recoveryAt: Long? = null
    private var lastTrimAt: Long? = null
    @Synchronized fun observe(h: NativeStitchingDeviceBudget.Headroom, nowMs: Long): Decision {
        val pressure = h.lowMemory || h.available <= h.threshold
        val margin = maxOf(16L * 1024 * 1024, h.threshold.coerceAtLeast(0L) / 4)
        if (pressure) {
            constrained = true
            recoveryAt = null
        } else if (constrained) {
            if (h.available - h.threshold < margin) recoveryAt = null
            else {
                val start = recoveryAt ?: nowMs.also { recoveryAt = it }
                if (nowMs - start >= recoveryMs) { constrained = false; recoveryAt = null }
            }
        }
        val trim = constrained && (lastTrimAt?.let { nowMs - it >= cooldownMs } ?: true)
        if (trim) lastTrimAt = nowMs
        return Decision(constrained, trim)
    }
    companion object { val process = MediaPressurePolicy() }
}
