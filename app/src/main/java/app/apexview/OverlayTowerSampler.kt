package app.apexview

/**
 * Maintains the exact existing TowerSample contract, but keeps each immutable
 * CardState for the full timestamp interval of its underlying lap snapshot.
 * Speed, tyre, weather/flags and every mini-sector remain independently sampled.
 * This is one-owner state, never shared between phone and Cast threads.
 */
internal class OverlayTowerSampler(
    private val timing: SessionTiming,
    private val probe: OverlayPerformanceProbe? = null,
) {
    private data class LapSnapshot(val source: DriverLaps, val index: Int, val state: CardState)
    private val rowCache = HashMap<String, LapSnapshot>()
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
        val rows = ArrayList<Pair<String, CardState>>(timing.laps.size)
        timing.laps.forEach { (number, source) ->
            val index = source.times.floorIndex(now)
            if (index < 0) {
                rowCache.remove(number)
                return@forEach
            }
            val cached = rowCache[number]
            val state = if (cached?.source === source && cached.index == index) cached.state else {
                val value = requireNotNull(source.at(now, includeRunningTime = false))
                rowCache[number] = LapSnapshot(source, index, value)
                value
            }
            rows += number to state
        }
        rows.sortBy { it.second.position ?: 99 }
        val tyres = HashMap<String, Tyre?>(timing.tyres.size)
        timing.tyres.forEach { (number, log) ->
            val tyre = log.at(now)
            val old = last?.tyres?.get(number)
            tyres[number] = if (old != null && tyre != null &&
                old.compound == tyre.compound && old.laps == tyre.laps) old else tyre
        }
        val speeds = HashMap<String, Pair<Int?, Int?>>(speedSource.size)
        speedSource.forEach { (number, log) ->
            val speed = log.kmhAt(now)
            val gear = log.gearAt(now)
            val old = last?.speeds?.get(number)
            speeds[number] = if (old != null && old.first == speed && old.second == gear) old else (speed to gear)
        }
        val candidate = TowerSample(rows, towerSessionHeader(timing, now), tyres, speeds)
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
