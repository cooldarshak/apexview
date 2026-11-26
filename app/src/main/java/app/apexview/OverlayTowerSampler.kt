package app.apexview

import app.apexview.tiled.NativeHotPath

/**
 * Maintains the exact existing TowerSample contract, but keeps each immutable
 * CardState for the full timestamp interval of its underlying lap snapshot.
 * Speed, tyre, weather/flags and every mini-sector remain independently sampled.
 * This is one-owner state, never shared between phone and Cast threads.
 */
internal class OverlayTowerSampler(
    private val timing: SessionTiming,
    private val probe: OverlayPerformanceProbe? = null,
    nativeRequested: Boolean = false,
) {
    private data class LapSnapshot(val source: DriverLaps, val index: Int, val row: Pair<String, CardState>)
    private val rowCache = HashMap<String, LapSnapshot>()
    private val lapEntries = timing.laps.entries.toList()
    private val tyreEntries = timing.tyres.entries.toList()
    private val nativeLookup = if (nativeRequested) NativeTowerLookup(lapEntries.map { it.value },
        tyreEntries.map { it.value }) else null
    private val scratchRows = ArrayList<Pair<String, CardState>>(lapEntries.size)
    private val scratchTyres = HashMap<String, Tyre?>(tyreEntries.size)
    private val scratchSpeeds = HashMap<String, Pair<Int?, Int?>>()
    private val orderedRows = ArrayList<Pair<String, CardState>>(lapEntries.size)
    // Reusable position/row index: the previous O(n²) instrumentation scan
    // allocated closures and repeatedly searched the entire tower on each
    // changed polling epoch. This map never escapes the single owner worker.
    private val previousRowsByNumber = HashMap<String, CardState>()
    private var last: TowerSample? = null
    private var lastMs = Long.MIN_VALUE
    private var lastSpeedSource: Map<String, SpeedLog>? = null

    fun sample(now: Long, speedSource: Map<String, SpeedLog> = timing.speeds): TowerSample {
        // Speeds can finish loading while video is paused at the SAME time.
        // A clock-only cache would freeze newly available gear/speed values.
        if (last != null && now == lastMs && lastSpeedSource === speedSource)
            return requireNotNull(last)
        val started = System.nanoTime()
        val nativeIndices = nativeLookup?.sample(now, speedSource) == true
        scratchRows.clear()
        lapEntries.forEachIndexed { slot, (number, source) ->
            val index = if (nativeIndices) nativeLookup!!.lapIndex(slot) else source.times.floorIndex(now)
            if (index < 0) {
                rowCache.remove(number)
                return@forEachIndexed
            }
            val cached = rowCache[number]
            val row = if (cached?.source === source && cached.index == index) cached.row else {
                val value = requireNotNull(source.atSnapshot(index, now))
                (number to value).also { rowCache[number] = LapSnapshot(source, index, it) }
            }
            scratchRows += row
        }
        val nativeOrder = nativeIndices && nativeLookup!!.order(scratchRows)
        val rows = if (nativeOrder) {
            orderedRows.clear()
            for (i in scratchRows.indices) orderedRows += scratchRows[nativeLookup!!.orderedIndex(i)]
            orderedRows
        } else {
            scratchRows.sortBy { it.second.position ?: 99 }
            scratchRows
        }
        nativeLookup?.report(nativeIndices && nativeOrder)
        val tyres = scratchTyres
        tyres.clear()
        tyreEntries.forEachIndexed { slot, (number, log) ->
            val tyre = if (nativeIndices) log.atSnapshot(nativeLookup!!.tyreIndex(slot)) else log.at(now)
            val old = last?.tyres?.get(number)
            tyres[number] = if (old != null && tyre != null &&
                old.compound == tyre.compound && old.laps == tyre.laps) old else tyre
        }
        val speeds = scratchSpeeds
        speeds.clear()
        if (nativeIndices) {
            nativeLookup!!.speedEntries.forEachIndexed { slot, (number, log) ->
                val speed = log.kmhAtSnapshot(nativeLookup.speedIndex(slot), now)
                val gear = log.gearAtSnapshot(nativeLookup.gearIndex(slot), now)
                val old = last?.speeds?.get(number)
                speeds[number] = if (old != null && old.first == speed && old.second == gear) old else (speed to gear)
            }
        } else speedSource.forEach { (number, log) ->
            val speed = log.kmhAt(now)
            val gear = log.gearAt(now)
            val old = last?.speeds?.get(number)
            speeds[number] = if (old != null && old.first == speed && old.second == gear) old else (speed to gear)
        }
        val header = towerSessionHeader(timing, now)
        // Scratch storage never escapes. Publish only the components that
        // actually changed; reuse previous immutable maps and rows otherwise.
        val previousSample = last
        val candidate = TowerSample(
            previousSample?.rows?.takeIf { it == rows } ?: rows.toList(),
            previousSample?.header?.takeIf { it == header } ?: header,
            previousSample?.tyres?.takeIf { old ->
                old.size == tyres.size && tyres.all { (number, tyre) ->
                    val prior = old[number]
                    old.containsKey(number) && prior?.compound == tyre?.compound && prior?.laps == tyre?.laps
                }
            } ?: HashMap(tyres),
            previousSample?.speeds?.takeIf { it == speeds } ?: HashMap(speeds),
        )
        val previous = last
        val same = previous != null && previous.visuallyEquals(candidate)
        val changedRows = if (same || probe == null) 0 else {
            previousRowsByNumber.clear()
            previous?.rows?.forEach { (number, state) -> previousRowsByNumber[number] = state }
            rows.count { (number, state) ->
                previousRowsByNumber[number] != state ||
                    previous?.tyres?.get(number)?.compound != tyres[number]?.compound ||
                    previous?.tyres?.get(number)?.laps != tyres[number]?.laps ||
                    previous?.speeds?.get(number) != speeds[number]
            }
        }
        val published = if (same) previous!! else candidate
        last = published
        lastMs = now
        lastSpeedSource = speedSource
        probe?.record(System.nanoTime() - started, rows.size, changedRows)
        return published
    }
}

/** Native numeric lookup/sort, with immutable domain objects kept in the
 * existing Android session model. Rebuild speed references only when that
 * published source changes; no complete timing-history copy or per-row JNI.
 */
private class NativeTowerLookup(laps: List<DriverLaps>, tyres: List<TyreLog>) {
    private val lapCount = laps.size
    private val times = (laps.map { it.times } + tyres.map { it.times }).toTypedArray()
    private val counts = times.map { it.size }.toIntArray()
    private val indices = IntArray(times.size)
    private val positions = IntArray(lapCount)
    private val order = IntArray(lapCount)
    private var speedSource: Map<String, SpeedLog>? = null
    var speedEntries: List<Map.Entry<String, SpeedLog>> = emptyList()
        private set
    private var speedTimes: Array<LongArray> = emptyArray()
    private var speedCounts = IntArray(0)
    private var speedIndices = IntArray(0)
    private var failed = false
    private var reported: Boolean? = null

    fun sample(now: Long, source: Map<String, SpeedLog>): Boolean {
        if (failed) return false
        if (source !== speedSource) {
            speedSource = source
            speedEntries = source.entries.toList()
            speedTimes = Array(speedEntries.size * 2) { index ->
                val log = speedEntries[index / 2].value
                if (index % 2 == 0) log.speedTimes else log.gearTimeSeries
            }
            speedCounts = IntArray(speedTimes.size) { index ->
                val log = speedEntries[index / 2].value
                if (index % 2 == 0) log.speedCount else log.validGearCount
            }
            speedIndices = IntArray(speedTimes.size)
        }
        if (!NativeHotPath.floorIndices(times, counts, now, indices) ||
            !NativeHotPath.floorIndices(speedTimes, speedCounts, now, speedIndices)) {
            failed = true
            return false
        }
        return true
    }
    fun lapIndex(i: Int) = indices[i]
    fun tyreIndex(i: Int) = indices[lapCount + i]
    fun speedIndex(i: Int) = speedIndices[2 * i]
    fun gearIndex(i: Int) = speedIndices[2 * i + 1]
    fun order(rows: List<Pair<String, CardState>>): Boolean {
        if (failed) return false
        for (i in rows.indices) positions[i] = rows[i].second.position ?: 99
        if (!NativeHotPath.rowOrder(positions, rows.size, order)) {
            failed = true
            return false
        }
        return true
    }
    fun orderedIndex(i: Int) = order[i]
    fun report(actualNative: Boolean) {
        if (reported != actualNative) {
            reported = actualNative
            android.util.Log.i("PitwallNativeHotPath", "TOWER_LOOKUP_ENGINE requested=RUST actual=" +
                if (actualNative) "RUST reason=BATCH_SAMPLED" else "JVM reason=NATIVE_BATCH_UNAVAILABLE")
        }
    }
}
