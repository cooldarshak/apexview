package app.apexview.scene

/** Two consecutive complete, healthy GL windows permit an accelerated-output trial.
 * Stale or unhealthy observations revoke it. These are submissions, not physical scanout. */
internal class ScenePipelineHealth {
    companion object {
        const val FRESH_NS = 7_000_000_000L
    }
    private var goodWindows = 0
    private var lastSampleNs = Long.MIN_VALUE
    @Volatile private var healthyAtNs = Long.MIN_VALUE

    @Synchronized fun observe(nowNs: Long, durationNs: Long, expectedFps: Float,
                              latched: Long, submitted: Long, callbackGaps: Long,
                              displayGaps: Long, slowSwaps: Long, slowDraws: Long,
                              slowLatches: Long, unavailable: Long) {
        val expected = durationNs / 1_000_000_000.0 * expectedFps
        val healthy = durationNs in 4_000_000_000L..6_500_000_000L &&
            expectedFps.isFinite() && expectedFps >= 20f &&
            latched >= expected * 0.90 && submitted >= expected * 0.88 &&
            callbackGaps <= latched * 0.02 && displayGaps <= submitted * 0.02 &&
            slowSwaps <= submitted * 0.02 && slowDraws <= submitted * 0.02 &&
            slowLatches <= latched * 0.02 && unavailable == 0L
        if (!healthy) { reset(); return }
        if (lastSampleNs != Long.MIN_VALUE && nowNs - lastSampleNs !in 1L..FRESH_NS) goodWindows = 0
        lastSampleNs = nowNs
        goodWindows = (goodWindows + 1).coerceAtMost(2)
        healthyAtNs = if (goodWindows >= 2) nowNs else Long.MIN_VALUE
    }

    fun allows(nowNs: Long): Boolean {
        val sample = healthyAtNs
        return sample != Long.MIN_VALUE && nowNs - sample in 0L..FRESH_NS
    }

    @Synchronized fun reset() {
        goodWindows = 0; lastSampleNs = Long.MIN_VALUE; healthyAtNs = Long.MIN_VALUE
    }
}
