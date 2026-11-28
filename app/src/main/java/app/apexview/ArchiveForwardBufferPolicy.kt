package app.apexview

/** Clear finite local archive only. All observations belong to real selected
 * Media3 child sources. Producer cost includes upstream fetch and composition,
 * even when Media3 subsequently reads a warm cache hit. Never discard samples. */
internal class ArchiveForwardBufferPolicy {
    enum class Lane { VIDEO, AUDIO, CAPTION }
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
        fun sustainable(speed: Float): Boolean = (0 until count).all { load[it] * speed < media[it] }
    }
    // Owned by the archive session, not by a player or a Media3 callback.
    class ProductionWindow {
        private val samples = Samples()
        @Synchronized fun record(loadMs: Long, mediaMs: Long, nowMs: Long) {
            if (loadMs !in 0..60_000 || mediaMs !in 500..15_000) samples.clear()
            else samples.add(loadMs.coerceAtLeast(1), mediaMs, nowMs)
        }
        @Synchronized fun reset() = samples.clear()
        @Synchronized fun cost(now: Long, speed: Float): Long? =
            if (samples.ready(now) && samples.sustainable(speed)) samples.worstLoad() else null
    }
    private val video = Samples()
    private val audio = Samples()
    private val caption = Samples()
    private var eligible = false
    private var audioChild = -1
    private var captionChild = -1
    private var production: ProductionWindow? = null
    private var generation = 0L
    private val pending = LinkedHashMap<Long, Pair<Long, Lane>>()
    @Synchronized fun bindProduction(window: ProductionWindow) {
        production = window
        reset(false)
    }
    /** Retire a session-owned production observer only on source ownership
     * retirement, not when changing its captions or selected audio lane. */
    @Synchronized fun unbindProduction() {
        production = null
        reset(false)
    }
    @Synchronized fun select(enabled: Boolean, audioIndex: Int, captionIndex: Int) {
        if (eligible == enabled && audioChild == audioIndex && captionChild == captionIndex) return
        audioChild = audioIndex
        captionChild = captionIndex
        reset(enabled)
    }
    @Synchronized fun reset(enabled: Boolean = eligible) {
        eligible = enabled
        generation++
        pending.clear()
        video.clear(); audio.clear(); caption.clear()
        if (!enabled) { audioChild = -1; captionChild = -1 }
    }
    @Synchronized fun startChild(taskId: Long, child: Int, retryCount: Int = 0) {
        val lane = when {
            child == 0 -> Lane.VIDEO
            child > 0 && child == audioChild -> Lane.AUDIO
            child > 0 && child == captionChild -> Lane.CAPTION
            else -> return
        }
        if (!eligible) return
        // Media3 1.11.1 reports every retry start. A successful retry is
        // not a clean delivery observation; require fresh selected loads.
        if (retryCount != 0) { reset(); return }
        start(taskId, lane)
    }
    @Synchronized fun start(taskId: Long, lane: Lane) {
        if (!eligible) return
        if (pending.size >= 128) pending.remove(pending.keys.first())
        pending[taskId] = generation to lane
    }
    @Synchronized fun cancel(taskId: Long) { pending.remove(taskId) }
    @Synchronized fun failure(taskId: Long) {
        if (pending.containsKey(taskId)) reset()
    }
    @Synchronized fun complete(taskId: Long, loadMs: Long, mediaMs: Long, nowMs: Long) {
        val owner = pending.remove(taskId) ?: return
        if (!eligible || owner.first != generation) return
        if (loadMs !in 0..60_000 || mediaMs !in 500..15_000 || nowMs < 0) {
            reset(); return
        }
        val samples = when (owner.second) {
            Lane.VIDEO -> video
            Lane.AUDIO -> audio
            Lane.CAPTION -> caption
        }
        samples.add(loadMs.coerceAtLeast(1), mediaMs, nowMs)
    }
    data class Target(val lowUs: Long, val highUs: Long, val generation: Long)
    @Synchronized fun target(nowMs: Long, speed: Float): Target? {
        if (!eligible || !speed.isFinite() || speed !in 0.5f..1.5f ||
            !video.ready(nowMs) || !audio.ready(nowMs) ||
            !video.sustainable(speed) || !audio.sustainable(speed)) return null
        val producer = production?.cost(nowMs, speed) ?: return null
        val hasCaption = captionChild > 0
        if (hasCaption && (!caption.ready(nowMs) || !caption.sustainable(speed))) return null
        val segment = maxOf(video.maxMedia(), audio.maxMedia(), if (hasCaption) caption.maxMedia() else 0L)
        // A cached Media3 read cannot hide slow upstream production.
        // Keep three segments + two worst serial service intervals, never
        // below 25s (observed 5.76s groups). Sparse/unknown captions fail open.
        val service = kotlin.math.ceil((maxOf(producer, video.worstLoad()) + audio.worstLoad() +
            if (hasCaption) caption.worstLoad() else 0L) * speed.toDouble()).toLong()
        val high = maxOf(25_000L, 3 * segment + 2 * service)
        if (high >= 50_000) return null
        val low = maxOf(2 * segment + 2 * service, high - segment, 5_000L)
        return Target(low.coerceAtMost(high - 1) * 1000, high * 1000, generation)
    }
}
