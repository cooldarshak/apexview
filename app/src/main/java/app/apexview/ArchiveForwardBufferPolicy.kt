package app.apexview

/**
 * Finite locally composed archive only. Reduce redundant forward queue residence
 * after both real media sources have demonstrated delivery faster than playback.
 * Unknown tracks, errors, seeks, source/track changes and stale measurements
 * immediately restore the original Media3 policy. No rendition is changed.
 */
internal class ArchiveForwardBufferPolicy {
    enum class Lane { VIDEO, AUDIO }
    private class Samples {
        val load = LongArray(8)
        val media = LongArray(8)
        var count = 0
        var cursor = 0
        var lastMs = -1L
        fun add(loadMs: Long, mediaMs: Long, nowMs: Long) {
            load[cursor] = loadMs
            media[cursor] = mediaMs
            cursor = (cursor + 1) % load.size
            count = minOf(count + 1, load.size)
            lastMs = nowMs
        }
        fun clear() { count = 0; cursor = 0; lastMs = -1 }
        fun ready(now: Long) = count >= 6 && now >= lastMs && now - lastMs <= 120_000
        fun maxMedia(): Long = (0 until count).maxOf { media[it] }
        fun worstLoad(): Long = (0 until count).maxOf { load[it] }
        fun sustainable(speed: Float): Boolean =
            (0 until count).all { load[it] * speed < media[it] }
    }
    private val video = Samples()
    private val audio = Samples()
    private var eligible = false
    private var generation = 0L
    private val pending = LinkedHashMap<Long, Pair<Long, Lane>>()
    @Synchronized fun reset(enabled: Boolean = eligible) {
        eligible = enabled
        generation++
        pending.clear()
        video.clear(); audio.clear()
    }
    @Synchronized fun start(taskId: Long, lane: Lane) {
        if (!eligible) return
        if (pending.size >= 128) pending.remove(pending.keys.first())
        pending[taskId] = generation to lane
    }
    @Synchronized fun cancel(taskId: Long) { pending.remove(taskId) }
    @Synchronized fun complete(taskId: Long, loadMs: Long, mediaMs: Long, nowMs: Long) {
        val owner = pending.remove(taskId) ?: return
        if (!eligible || owner.first != generation) return
        // Only real, complete finite media segment observations qualify.
        if (loadMs !in 0..60_000 || mediaMs !in 500..15_000 || nowMs < 0) {
            reset(); return
        }
        val samples = if (owner.second == Lane.VIDEO) video else audio
        samples.add(loadMs.coerceAtLeast(1), mediaMs, nowMs)
    }
    data class Target(val lowUs: Long, val highUs: Long, val generation: Long)
    @Synchronized fun target(nowMs: Long, speed: Float): Target? {
        if (!eligible || !speed.isFinite() || speed !in 0.5f..1.5f ||
            !video.ready(nowMs) || !audio.ready(nowMs) ||
            !video.sustainable(speed) || !audio.sustainable(speed)) return null
        val segment = maxOf(video.maxMedia(), audio.maxMedia())
        // Three measured segments, plus two full worst-observed delivery
        // intervals at the chosen speed. Never cap at/above the normal 50s.
        val service = kotlin.math.ceil((video.worstLoad() + audio.worstLoad()) * speed.toDouble()).toLong()
        val high = 3 * segment + 2 * service
        if (high >= 50_000) return null
        val low = maxOf(2 * segment + 2 * service, 5_000L)
        return Target(low * 1000, high * 1000, generation)
    }
}
