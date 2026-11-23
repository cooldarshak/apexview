package app.apexview

import kotlin.math.max

/**
 * Session-scoped, single-owner timing cursors. Shared by map, edge map and
 * Pitwall Cast sender. An immutable List<Car> is published for every new
 * clock sample; no mutable coordinate arrays escape to the UI/receiver.
 */
internal class OverlayPositionCursor(private val track: DriverTrack) {
    private val times = track.t
    private val xs = track.x
    private val ys = track.y
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
            x = xs[0]; y = ys[0]; previousTime = atMs; lower = 0
            return true
        }
        if (atMs > times[count - 1]) {
            previousTime = atMs
            lower = (count - 2).coerceAtLeast(0)
            return false
        }
        if (count == 1) {
            x = xs[0]; y = ys[0]; previousTime = atMs; lower = 0
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
        x = xs[lower] + (xs[hi] - xs[lower]) * f
        y = ys[lower] + (ys[hi] - ys[lower]) * f
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
internal class OverlayDrawCar(val number: String) {
    var x = 0f
        internal set
    var y = 0f
        internal set
    var state = LapState.UNKNOWN
        internal set
}

/** Do not share between threads. Give local map, edge map and Cast separate owners. */
internal class OverlayPositionSampler(
    private val timing: SessionTiming,
    private val probe: OverlayPerformanceProbe? = null,
) {
    private class Entry(val id: String, val cursor: OverlayPositionCursor) {
        var previousCar: Car? = null
        val drawCar = OverlayDrawCar(id)
    }
    private val entries = timing.tracks.map { (id, track) ->
        Entry(id, OverlayPositionCursor(track))
    }
    private var lastMs = Long.MIN_VALUE
    private var lastBeforePositions = false
    private var previous: List<Car> = emptyList()
    private var seeks = 0L
    private val drawCars = ArrayList<OverlayDrawCar>(entries.size)
    private var lastDrawMs = Long.MIN_VALUE

    /**
     * Called ONLY from the map's Canvas draw lambda. Entries remain mutable for this
     * synchronous draw; consumers must never retain the returned list or its cars.
     * Independent samplers own the 10Hz immutable hit-target and Cast publication.
     */
    fun sampleForDraw(atMs: Long): List<OverlayDrawCar> {
        if (lastDrawMs == atMs) return drawCars
        lastDrawMs = atMs
        drawCars.clear()
        if (timing.beforePositions(atMs)) return drawCars
        for (entry in entries) {
            if (timing.laps[entry.id]?.retiredAt(atMs) == true) continue
            if (!entry.cursor.sample(atMs)) continue
            val car = entry.drawCar
            car.x = entry.cursor.x
            car.y = entry.cursor.y
            car.state = timing.states[entry.id]?.at(atMs) ?: LapState.UNKNOWN
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
        for (entry in entries) {
            if (timing.laps[entry.id]?.retiredAt(atMs) == true) continue
            if (!entry.cursor.sample(atMs)) continue
            val state = timing.states[entry.id]?.at(atMs) ?: LapState.UNKNOWN
            // The published list is compacted when earlier drivers retire or
            // leave position history, so its indices cannot key source drivers.
            val old = entry.previousCar
            val car = if (old != null && old.x == entry.cursor.x && old.y == entry.cursor.y && old.state == state)
                old else {
                    changed++
                    Car(entry.id, entry.cursor.x, entry.cursor.y, state)
                }
            entry.previousCar = car
            next.add(car)
        }
        val currentSeeks = entries.sumOf { it.cursor.seekCount }
        probe?.record(System.nanoTime() - started, next.size, changed,
            max(0L, currentSeeks - seeks))
        seeks = currentSeeks
        previous = next
        lastMs = atMs
        return next
    }
}
