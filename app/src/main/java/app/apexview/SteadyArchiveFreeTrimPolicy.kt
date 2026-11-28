package app.apexview

/** App-looper scalar admission. Never discards active Media3 samples. */
internal class SteadyArchiveFreeTrimPolicy {
    private var eligibleSince = -1L
    private var excessSince = -1L
    private var lastObservation = -1L
    private var lastTrim = -1L

    fun reset() {
        eligibleSince = -1L
        excessSince = -1L
        lastObservation = -1L
    }

    fun observe(nowMs: Long, eligible: Boolean, freeBytes: Int): Boolean {
        require(nowMs >= 0 && freeBytes >= 0)
        if (!eligible) { reset(); return false }
        if (lastObservation >= 0 && (nowMs < lastObservation || nowMs - lastObservation > 1500L)) reset()
        lastObservation = nowMs
        if (eligibleSince < 0) eligibleSince = nowMs
        if (freeBytes < 8 * 1024 * 1024) { excessSince = -1L; return false }
        if (excessSince < 0) excessSince = nowMs
        if (nowMs - eligibleSince < 5000L || nowMs - excessSince < 2000L ||
            (lastTrim >= 0 && nowMs - lastTrim < 15000L)) return false
        lastTrim = nowMs
        excessSince = -1L
        return true
    }
}
