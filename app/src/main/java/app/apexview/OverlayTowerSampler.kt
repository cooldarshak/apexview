package app.apexview

import app.apexview.tiled.NativeHotPath

/**
 * Maintains the exact existing TowerSample contract, but keeps each immutable
 * CardState for the full timestamp interval of its underlying lap snapshot.
 * Speed, tyre, weather/flags and every mini-sector remain independently sampled.
 * This is one-owner state, never shared between phone and Cast threads.
 */
internal class OverlayTowerSampler(
    private var timing: SessionTiming,
    private val probe: OverlayPerformanceProbe? = null,
    nativeRequested: Boolean = false,
) {
    private data class LapSnapshot(val source: DriverLaps, val index: Int, val row: Pair<String, CardState>)
    private val rowCache = HashMap<String, LapSnapshot>()
    private val sectorNormalizer = TowerSectorNormalizer(nativeRequested)
    private val scratchSectors = HashMap<String, List<SectorCell?>>()
    private val lapEntries = timing.laps.entries.toList()
    private val tyreEntries = timing.tyres.entries.toList()
    private val nativeLookup = if (nativeRequested) NativeTowerLookup(lapEntries.map { it.value },
        tyreEntries.map { it.value }, timing.weather) else null
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

    /** Position-only and circuit-only publications can keep the native indices and row cache. */
    fun updateSource(next: SessionTiming): Boolean {
        if (timing.laps !== next.laps || timing.tyres !== next.tyres || timing.weather !== next.weather)
            return false
        if (timing !== next) {
            timing = next
            lastMs = Long.MIN_VALUE
        }
        return true
    }

    fun sample(now: Long, speedSource: Map<String, SpeedLog> = timing.speeds): TowerSample {
        // Speeds can finish loading while video is paused at the SAME time.
        // A clock-only cache would freeze newly available gear/speed values.
        if (last != null && now == lastMs && lastSpeedSource === speedSource)
            return requireNotNull(last)
        val started = System.nanoTime()
        val nativeIndices = nativeLookup?.sample(now, speedSource) == true
        scratchRows.clear()
        lapEntries.forEachIndexed { slot, (number, source) ->
            val index = if (nativeIndices) nativeLookup!!.lapIndex(slot) else source.snapshotTimeline.floorIndex(now)
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
        // Fixed session keys: update existing nodes rather than recreate every poll.
        tyreEntries.forEachIndexed { slot, (number, log) ->
            val tyre = if (nativeIndices) log.atSnapshot(nativeLookup!!.tyreIndex(slot)) else log.at(now)
            val old = last?.tyres?.get(number)
            tyres[number] = if (old != null && tyre != null &&
                old.compound == tyre.compound && old.laps == tyre.laps) old else tyre
        }
        val speeds = scratchSpeeds
        // Immutable source replacements can remove drivers. Keep matching scratch nodes.
        if (lastSpeedSource !== speedSource) speeds.keys.retainAll(speedSource.keys)
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
        val header = towerSessionHeader(timing, now, nativeLookup != null,
            if (nativeIndices) nativeLookup!!.currentLap() else null,
            if (nativeIndices) nativeLookup!!.weather() else null, nativeIndices)
        // Scratch storage never escapes. Publish only the components that
        // actually changed; reuse previous immutable maps and rows otherwise.
        val previousSample = last
        // Seeking before a driver's first snapshot must remove its old sector strips.
        scratchSectors.keys.retainAll(rowCache.keys)
        for ((number, row) in rows) scratchSectors[number] = sectorNormalizer.sample(number, row)
        sectorNormalizer.retainOnly(scratchSectors.keys)
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
            previousSample?.normalizedSectors?.takeIf { it == scratchSectors } ?: HashMap(scratchSectors),
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
private class NativeTowerLookup(private val laps: List<DriverLaps>, tyres: List<TyreLog>, private val weatherLog: WeatherLog) {
    private val lapCount = laps.size
    private val headerOffset = lapCount + tyres.size
    private val baseOwners = (laps.map { it.snapshotTimeline } + tyres.map { it.timeline } +
        laps.map { it.lapTimeline } + listOf(weatherLog.timeline)).toTypedArray()
    private val baseCounts = IntArray(baseOwners.size) { baseOwners[it].size }
    private val speedOffset = baseOwners.size
    private var times: Array<LongArray?> = Array(baseOwners.size) { baseOwners[it].array }
    private var timeOwners: Array<TimingTimestamps?> = Array(baseOwners.size) { baseOwners[it] }
    private var compactHandles = LongArray(baseOwners.size) { baseOwners[it].handle }
    private var counts = baseCounts
    private var indices = IntArray(baseOwners.size) { -1 }
    private val positions = IntArray(lapCount)
    private val order = IntArray(lapCount)
    private var speedSource: Map<String, SpeedLog>? = null
    var speedEntries: List<Map.Entry<String, SpeedLog>> = emptyList()
        private set
    private var failed = false
    private var reported: Boolean? = null

    fun currentLap(): Int? {
        var lap: Int? = null
        for (i in laps.indices) {
            val value = laps[i].lapCounts.getOrNull(indices[headerOffset + i])?.plus(1) ?: continue
            lap = maxOf(lap ?: value, value)
        }
        return lap
    }
    fun weather(): WeatherReading? = weatherLog.atSnapshot(indices[speedOffset - 1])

    fun sample(now: Long, source: Map<String, SpeedLog>): Boolean {
        if (failed) return false
        if (source !== speedSource) {
            speedSource = source
            speedEntries = source.entries.toList()
            val total = speedOffset + speedEntries.size * 2
            val prior = indices
            timeOwners = Array(total) { index ->
                if (index < speedOffset) baseOwners[index] else {
                    val slot = index - speedOffset
                    val log = speedEntries[slot / 2].value
                    if (slot % 2 == 0) log.speedTimeline else log.gearTimeline
                }
            }
            times = Array(total) { index ->
                timeOwners[index]?.array
            }
            compactHandles = LongArray(total) { timeOwners[it]?.handle ?: 0L }
            counts = IntArray(total) { index ->
                if (index < speedOffset) baseCounts[index] else {
                    val slot = index - speedOffset
                    val log = speedEntries[slot / 2].value
                    if (slot % 2 == 0) log.speedCount else log.validGearCount
                }
            }
            // Only unchanged histories keep their prior search hints.
            indices = IntArray(total) { if (it < speedOffset) prior[it] else -1 }
        }
        val found = try {
            NativeHotPath.floorIndicesWithCompactTimes(times, counts, compactHandles, now, indices)
        } finally { java.lang.ref.Reference.reachabilityFence(timeOwners) }
        if (!found) {
            failed = true
            return false
        }
        return true
    }
    fun lapIndex(i: Int) = indices[i]
    fun tyreIndex(i: Int) = indices[lapCount + i]
    fun speedIndex(i: Int) = indices[speedOffset + 2 * i]
    fun gearIndex(i: Int) = indices[speedOffset + 2 * i + 1]
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
