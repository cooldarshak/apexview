package app.apexview.cast

import app.apexview.NativePositionBatch
import app.apexview.SessionTiming

/** Separate sender prediction clock per step: no seek thrashing in the
 * current-picture cursor. Only measured endpoints cross the wire. */
internal class NativeCastPrediction(timing: SessionTiming) {
    private val slots = timing.tracks.keys.withIndex().associate { it.value to it.index }
    private val batches = Array(6) { NativePositionBatch(timing) }
    fun sample(now: Long, steps: Int): NativeCastPrediction? {
        require(steps in 1..batches.size)
        for (i in 0 until steps) {
            if (!batches[i].sample(now + (i + 1) * CastTimingProtocol.POSITION_STEP_MS,
                    includeMetadata = false)) return null
        }
        return this
    }
    fun x(number: String, step: Int, fallback: Float): Float {
        val slot = slots[number] ?: return fallback
        val batch = batches[step - 1]
        return if (batch.active[slot] == 0) fallback else batch.xy[slot * 2]
    }
    fun y(number: String, step: Int, fallback: Float): Float {
        val slot = slots[number] ?: return fallback
        val batch = batches[step - 1]
        return if (batch.active[slot] == 0) fallback else batch.xy[slot * 2 + 1]
    }
}
