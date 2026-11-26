package app.apexview.scene

/**
 * Stage-level app evidence, NOT a guessed Android BufferQueue occupancy metric.
 * A platform producer with mDequeueTimeout=0 reports immediate nonblocking
 * refusals; counting them as video freezes or network starvation is incorrect.
 * SurfaceFlinger scanout remains independently measured by Perfetto.
 */
internal object SceneBufferQueueEvidence {
    enum class Stage { NO_LATCH_OBSERVED, GL_OUTPUT_UNAVAILABLE, EGL_SWAP_DELAY_OBSERVED,
        CALLBACK_GAP_OBSERVED, GL_FLOW_OBSERVED }

    fun cadenceFields(latched: Long, submitted: Long, windowNs: Long, refreshHz: Float,
                      expectedFps: Float): String {
        fun rate(count: Long): Long = if (windowNs > 0L && count >= 0L)
            (count.toDouble() * 1_000_000_000_000.0 / windowNs).toLong() else -1L
        val multiple = if (refreshHz > 0f && expectedFps > 0f)
            SceneFrameRatePolicy.isIntegerRefreshMultiple(refreshHz, expectedFps.toDouble()).toString()
        else "UNKNOWN"
        return "cadenceWindowNs=$windowNs observedLatchMilliFps=${rate(latched)} " +
            "observedSubmitMilliFps=${rate(submitted)} refreshMultiple=$multiple physicalPresentation=NOT_MEASURED"
    }

    fun stage(latched: Long, submitted: Long, callbackGaps: Long, slowSwaps: Long,
              unavailableDraws: Long): Stage = when {
        unavailableDraws > 0L -> Stage.GL_OUTPUT_UNAVAILABLE
        latched == 0L -> Stage.NO_LATCH_OBSERVED
        slowSwaps > 0L -> Stage.EGL_SWAP_DELAY_OBSERVED
        callbackGaps > 0L -> Stage.CALLBACK_GAP_OBSERVED
        submitted > 0L -> Stage.GL_FLOW_OBSERVED
        else -> Stage.NO_LATCH_OBSERVED
    }
}
