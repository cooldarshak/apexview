package app.apexview

import kotlin.math.max
import app.apexview.tiled.NativeHotPath

/**
 * Session-scoped, single-owner timing cursors. Shared by map, edge map and
 * Pitwall Cast sender. An immutable List<Car> is published for every new
 * clock sample; no mutable coordinate arrays escape to the UI/receiver.
 */
internal class OverlayPositionCursor(private val track: DriverTrack) {
    private val times = track.timeline
    private val points = track.coordinates
    private val count = track.n
    private var lower = -1
    private var previousTime = Long.MIN_VALUE
    var x = 0f
        private set
    var y = 0f
        private set
    var seekCount = 0L
        private set

    /** Same start/end/gap semantics as DriverTrack.atOrFirst(). */
    fun sample(atMs: Long): Boolean {
        if (count == 0) return false
        if (atMs < times[0]) {
            x = points.xAt(0); y = points.yAt(0); previousTime = atMs; lower = 0
            return true
        }
        if (atMs > times[count - 1]) {
            previousTime = atMs
            lower = (count - 2).coerceAtLeast(0)
            return false
        }
        if (count == 1) {
            x = points.xAt(0); y = points.yAt(0); previousTime = atMs; lower = 0
            return true
        }
        if (lower < 0) {
            lower = findInterval(atMs)
        } else if (atMs < previousTime) {
            seekCount++
            lower = findInterval(atMs)
        } else {
            var advanced = 0
            while (lower < count - 2 && times[lower + 1] <= atMs && advanced < 8) {
                lower++; advanced++
            }
            if (lower < count - 2 && times[lower + 1] <= atMs) {
                seekCount++
                lower = findInterval(atMs)
            }
        }
        previousTime = atMs
        val hi = lower + 1
        val span = times[hi] - times[lower]
        if (span > 5_000L) return false
        val f = if (span == 0L) 0f else (atMs - times[lower]).toFloat() / span
        x = points.xAt(lower) + (points.xAt(hi) - points.xAt(lower)) * f
        y = points.yAt(lower) + (points.yAt(hi) - points.yAt(lower)) * f
        return true
    }

    private fun findInterval(atMs: Long): Int {
        var lo = 0
        var hi = count - 1
        while (hi - lo > 1) {
            val mid = (hi + lo) ushr 1
            if (times[mid] <= atMs) lo = mid else hi = mid
        }
        return lo
    }
}

/**
 * Draw-phase-only mutable projection. Never publish from Compose composition, snapshots,
 * the LAN sender or another thread. The Canvas consumes this object synchronously.
 * Reuses a driver object and one list across every 60Hz motion frame, unlike the
 * immutable 10Hz hit-target / Cast snapshots retained by sample().
 */
internal class OverlayDrawCar(override val number: String) : OverlayMotionCar {
    override var x = 0f
        internal set
    override var y = 0f
        internal set
    override var state = LapState.UNKNOWN
        internal set
}

/** One owner and bounded batched JNI calls per position sample. A completed
 * native archive retains compact Rust timestamp owners, while coordinates and
 * live prefixes remain immutable JVM arrays. Result scratch is thread-local.
 */
internal class NativePositionBatch(timing: SessionTiming) {
    private val tracks = timing.tracks.values
    private val states = timing.tracks.keys.map { timing.states[it] }
    private val laps = timing.tracks.keys.map { timing.laps[it] }
    private val emptyTimes = LongArray(0)
    private val metadataTimes = (states.map { it?.t ?: emptyTimes } +
        laps.map { it?.times ?: emptyTimes }).toTypedArray()
    private val metadataCounts = IntArray(metadataTimes.size) { metadataTimes[it].size }
    private val metadataIndices = IntArray(metadataTimes.size)
    fun state(index: Int): LapState =
        states[index]?.s?.getOrNull(metadataIndices[index]) ?: LapState.UNKNOWN
    fun retired(index: Int): Boolean =
        laps[index]?.states?.getOrNull(metadataIndices[states.size + index])?.retired == true
    private val timeOwners = tracks.map { it.timeline }.toTypedArray()
    private val times = timeOwners.map { it.array }.toTypedArray()
    private val compactHandles = LongArray(timeOwners.size) { timeOwners[it].handle }
    private val pointOwners = tracks.map { it.coordinates }.toTypedArray()
    private val xs = pointOwners.map { it.xArray }.toTypedArray()
    private val ys = pointOwners.map { it.yArray }.toTypedArray()
    private val positionHandles = LongArray(pointOwners.size) { pointOwners[it].handle }
    private val counts = tracks.map { it.n }.toIntArray()
    private val cursors = LongArray(counts.size * 3) { when (it % 3) {
        0 -> -1L
        1 -> Long.MIN_VALUE
        else -> 0L
    } }
    val xy = FloatArray(counts.size * 2)
    val active = IntArray(counts.size)
    private var failed = false
    private var reported = false
    fun sample(now: Long, includeMetadata: Boolean = true): Boolean {
        if (failed) return false
        val positionOk = try {
            NativeHotPath.samplePositionsPacked(times, xs, ys, counts, compactHandles, positionHandles, now, cursors, xy, active)
        } finally {
            java.lang.ref.Reference.reachabilityFence(timeOwners)
            java.lang.ref.Reference.reachabilityFence(pointOwners)
        }
        if (!positionOk || includeMetadata && !NativeHotPath.floorIndices(metadataTimes, metadataCounts, now, metadataIndices)) {
            failed = true
            android.util.Log.i("PitwallNativeHotPath", "MAP_ENGINE requested=RUST actual=JVM reason=NATIVE_BATCH_UNAVAILABLE")
            return false
        }
        if (!reported) {
            reported = true
            android.util.Log.i("PitwallNativeHotPath", "MAP_ENGINE requested=RUST actual=RUST reason=BATCH_SAMPLED")
        }
        return true
    }
    fun seekCount(): Long {
        var result = 0L
        for (i in counts.indices) result += cursors[3 * i + 2]
        return result
    }
}

/** Do not share between threads. Give local map, edge map and Cast separate owners. */
internal class OverlayPositionSampler(
    private val timing: SessionTiming,
    private val probe: OverlayPerformanceProbe? = null,
    nativeRequested: Boolean = false,
) {
    private class Entry(val id: String, val cursor: OverlayPositionCursor) {
        var previousCar: Car? = null
        val drawCar = OverlayDrawCar(id)
    }
    private val entries = timing.tracks.map { (id, track) ->
        Entry(id, OverlayPositionCursor(track))
    }
    private val native = if (nativeRequested) NativePositionBatch(timing) else null
    private var lastMs = Long.MIN_VALUE
    private var lastBeforePositions = false
    private var previous: List<Car> = emptyList()
    private var seeks = 0L
    private val drawCars = ArrayList<OverlayDrawCar>(entries.size)
    private var lastDrawMs = Long.MIN_VALUE
    private var lastDrawBeforePositions = false

    /**
     * Called ONLY from the map's Canvas draw lambda. Entries remain mutable for this
     * synchronous draw; consumers must never retain the returned list or its cars.
     * Independent samplers own the 10Hz immutable hit-target and Cast publication.
     */
    fun sampleForDraw(atMs: Long): List<OverlayDrawCar> {
        // Even while paused, new timing metadata can make positions available
        // without moving the playhead. Follow the immutable sampler's policy.
        val before = timing.beforePositions(atMs)
        if (lastDrawMs == atMs && lastDrawBeforePositions == before) return drawCars
        lastDrawMs = atMs
        lastDrawBeforePositions = before
        drawCars.clear()
        if (before) return drawCars
        val sampledNative = native?.sample(atMs) == true
        for (index in entries.indices) {
            val entry = entries[index]
            if (if (sampledNative) native!!.retired(index) else timing.laps[entry.id]?.retiredAt(atMs) == true) continue
            if (sampledNative) {
                if (native!!.active[index] == 0) continue
            } else if (!entry.cursor.sample(atMs)) continue
            val car = entry.drawCar
            car.x = if (sampledNative) native!!.xy[2 * index] else entry.cursor.x
            car.y = if (sampledNative) native!!.xy[2 * index + 1] else entry.cursor.y
            car.state = if (sampledNative) native!!.state(index) else timing.states[entry.id]?.at(atMs) ?: LapState.UNKNOWN
            drawCars.add(car)
        }
        return drawCars
    }

    fun sample(atMs: Long): List<Car> {
        // Live archive metadata can make previously unavailable positions
        // visible at an unchanged paused playhead. Do not cache them away.
        val before = timing.beforePositions(atMs)
        if (lastMs == atMs && lastBeforePositions == before) return previous
        val started = System.nanoTime()
        lastBeforePositions = before
        if (before) {
            previous = emptyList(); lastMs = atMs
            probe?.record(System.nanoTime() - started, 0, 0)
            return previous
        }
        val next = ArrayList<Car>(entries.size)
        // Channel order remains identical to SessionTiming.tracks iteration order.
        // Reuse unchanged Car objects so a stationary dot is not invalidated.
        var changed = 0
        val sampledNative = native?.sample(atMs) == true
        for (index in entries.indices) {
            val entry = entries[index]
            if (if (sampledNative) native!!.retired(index) else timing.laps[entry.id]?.retiredAt(atMs) == true) continue
            if (sampledNative) {
                if (native!!.active[index] == 0) continue
            } else if (!entry.cursor.sample(atMs)) continue
            val x = if (sampledNative) native!!.xy[2 * index] else entry.cursor.x
            val y = if (sampledNative) native!!.xy[2 * index + 1] else entry.cursor.y
            val state = if (sampledNative) native!!.state(index) else timing.states[entry.id]?.at(atMs) ?: LapState.UNKNOWN
            // The published list is compacted when earlier drivers retire or
            // leave position history, so its indices cannot key source drivers.
            val old = entry.previousCar
            val car = if (old != null && old.x == x && old.y == y && old.state == state)
                old else {
                    changed++
                    Car(entry.id, x, y, state)
                }
            entry.previousCar = car
            next.add(car)
        }
        val currentSeeks = if (sampledNative) native!!.seekCount() else entries.sumOf { it.cursor.seekCount }
        probe?.record(System.nanoTime() - started, next.size, changed,
            max(0L, currentSeeks - seeks))
        seeks = currentSeeks
        previous = next
        lastMs = atMs
        return next
    }
}
