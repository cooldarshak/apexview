package app.apexview

import org.json.JSONArray
import org.json.JSONObject

/** How a sector or lap compares: the broadcast's yellow, green and purple. */
enum class Pace { NONE, YELLOW, GREEN, PURPLE }

/** A sector time plus the F1 micro-sector status strip and its previous value. */
data class SectorCell(
    val time: String,
    val pace: Pace,
    val segments: List<Int> = emptyList(),
    val previousTime: String = "",
)

/** What a driver's timing card shows at one moment. */
data class CardState(
    val position: Int?,
    /** S1..S3 of the lap in progress; null where not yet set. */
    val sectors: List<SectorCell?>,
    /** S1..S3 of the last full lap, shown with its lap time just after the line. */
    val lapSectors: List<SectorCell?>,
    /** Time into the current lap, ms; null in the pits or before the first crossing. */
    val runningMs: Long?,
    val lastLap: SectorCell?,
    /** Gap to the fastest in qualifying, else the interval to the car ahead. */
    val gap: String?,
    val inPit: Boolean,
    /** Gap to the leader (races) or to the fastest lap (qualifying), for the timing tower. */
    val toLeader: String? = null,
    /** Gap to the car ahead, for the timing tower. */
    val ahead: String? = null,
    val bestLap: SectorCell? = null,
    val retired: Boolean = false,
    /** Positive means gained places since the first position observed by this timing stream. */
    val positionChange: Int? = null,
    val stopped: Boolean = false,
)

/** The tower shows a single lap. A previous sector supplies only the strip's width. */
internal fun CardState.towerSector(index: Int): SectorCell? {
    val current = sectors.getOrNull(index)
    val previousCount = lapSectors.getOrNull(index)?.segments?.size ?: 0
    val currentCount = current?.segments?.size ?: 0
    if (previousCount <= currentCount) return current
    val strip = List(previousCount) { current?.segments?.getOrNull(it) ?: 0 }
    return SectorCell(current?.time.orEmpty(), current?.pace ?: Pace.NONE, strip, current?.previousTime.orEmpty())
}

/** A tower-only owner keeps immutable mini-sector strips across unrelated
 * speed, tyre, position and header updates. The original CardState is never
 * changed. Rust handles bounded numeric padding; the reference path remains
 * available for longer histories or unavailable JNI. */
internal class TowerSectorNormalizer(private val nativeRequested: Boolean) {
    private data class Cached(val current: List<SectorCell?>, val previous: List<SectorCell?>,
        val normalized: List<SectorCell?>)
    private val rows = HashMap<String, Cached>()
    private val counts = IntArray(6)
    private val input = IntArray(192)
    private val output = IntArray(192)
    private var actual: Boolean? = null

    fun sample(number: String, state: CardState): List<SectorCell?> {
        val cached = rows[number]
        if (cached != null && cached.current == state.sectors && cached.previous == state.lapSectors)
            return cached.normalized
        var bounded = true
        var needsPadding = false
        for (i in 0..2) {
            val current = state.sectors.getOrNull(i)?.segments.orEmpty()
            val previous = state.lapSectors.getOrNull(i)?.segments?.size ?: 0
            counts[2*i] = current.size
            counts[2*i+1] = maxOf(current.size, previous)
            needsPadding = needsPadding || previous > current.size
            if (counts[2*i+1] > 64) bounded = false
            else current.forEachIndexed { j, value -> input[64*i+j] = value }
        }
        val native = nativeRequested && bounded && needsPadding &&
            app.apexview.tiled.NativeHotPath.normalizeSectors(input, counts, output)
        if (nativeRequested && needsPadding && actual != native) {
            actual = native
            android.util.Log.i("PitwallNativeHotPath", "TOWER_SECTOR_ENGINE requested=RUST actual=" +
                if (native) "RUST reason=STRIP_NORMALIZED" else "JVM reason=NATIVE_LIMIT_OR_UNAVAILABLE")
        }
        val result = List(3) { i ->
            val current = state.sectors.getOrNull(i)
            if (!native) state.towerSector(i)
            else if (counts[2*i] == counts[2*i+1]) current
            else SectorCell(current?.time.orEmpty(), current?.pace ?: Pace.NONE,
                List(counts[2*i+1]) { j -> output[i*64+j] }, current?.previousTime.orEmpty())
        }
        rows[number] = Cached(state.sectors, state.lapSectors, result)
        return result
    }
    fun retainOnly(numbers: Set<String>) { rows.keys.retainAll(numbers) }
}

/**
 * One driver's timing through the session, as recorded snapshots: each change to the sectors,
 * last lap, gap, position or pit status keeps a full copy of the state, so [at] is a lookup
 * rather than a replay.
 */
/** Last sample at or before the playback clock. Handles repeated timestamps. */
internal fun LongArray.floorIndex(utcMs: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid] <= utcMs) low = mid + 1 else high = mid
    }
    return low - 1
}

/** Exact predecessor plus the covered interval. No speculative samples are
 * manufactured for drivers whose first recorded event is after the window. */
internal fun historyWindowRange(size: Int, from: Long, until: Long, at: (Int) -> Long): IntRange? {
    if (size == 0 || from > until || at(0) > until) return null
    fun floor(time: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (at(middle) <= time) low = middle + 1 else high = middle
        }
        return low - 1
    }
    return floor(from).coerceAtLeast(0)..floor(until)
}

class DriverLaps internal constructor(
    internal val snapshotTimeline: TimingTimestamps,
    internal val states: List<Snapshot>,
    internal val crossingTimeline: TimingTimestamps,
    /** When the driver's completed-lap count changed, and to what. */
    internal val lapTimeline: TimingTimestamps,
    internal val lapCounts: IntArray = IntArray(0),
    internal val initialPosition: Int? = states.firstOrNull()?.position,
) {
    constructor(times: LongArray, states: List<Snapshot>, crossings: LongArray,
                lapTimes: LongArray = LongArray(0), lapCounts: IntArray = IntArray(0),
                initialPosition: Int? = states.firstOrNull()?.position) :
        this(TimingTimestamps.jvm(times, times.size), states,
            TimingTimestamps.jvm(crossings, crossings.size), TimingTimestamps.jvm(lapTimes, lapTimes.size),
            lapCounts, initialPosition)

    internal val times: LongArray get() = snapshotTimeline.legacyArray()
    internal val crossings: LongArray get() = crossingTimeline.legacyArray()
    internal val lapTimes: LongArray get() = lapTimeline.legacyArray()
    internal fun compact(pool: SpeedTimestampPool): DriverLaps =
        DriverLaps(snapshotTimeline.compact(pool), pool.freezeDriverSnapshots(states), crossingTimeline.compact(pool),
            lapTimeline.compact(pool), lapCounts, initialPosition)

    internal fun shiftedBy(offsetMs: Long) = DriverLaps(
        snapshotTimeline.shiftedBy(offsetMs), states, crossingTimeline.shiftedBy(offsetMs), lapTimeline.shiftedBy(offsetMs), lapCounts, initialPosition,
    )

    /** A bounded immutable UI/cache window. Preserve original rank baseline,
     * lap counter, previous crossingTimeline and the actual beginning of an ongoing pit spell. */
    internal fun previewWindow(from: Long, until: Long): DriverLaps? {
        val range = historyWindowRange(snapshotTimeline.size, from, until) { snapshotTimeline[it] } ?: return null
        var first = range.first
        if (states[first].inPit) while (first > 0 && states[first - 1].inPit) first--
        val end = range.last + 1
        val crossingRange = historyWindowRange(crossingTimeline.size, from, until) { crossingTimeline[it] }
        val lapRange = historyWindowRange(lapTimeline.size, from, until) { lapTimeline[it] }
        return DriverLaps(snapshotTimeline.copyOfRange(first, end), states.subList(first, end).toList(),
            crossingRange?.let { crossingTimeline.copyOfRange((it.first - 8).coerceAtLeast(0), it.last + 1) } ?: LongArray(0),
            lapRange?.let { lapTimeline.copyOfRange(it.first, it.last + 1) } ?: LongArray(0),
            lapRange?.let { lapCounts.copyOfRange(it.first, it.last + 1) } ?: IntArray(0),
            initialPosition)
    }

    internal fun withInitialPosition(position: Int?): DriverLaps =
        DriverLaps(snapshotTimeline, states, crossingTimeline, lapTimeline, lapCounts, position)

    class Snapshot(
        val position: Int?,
        val sectors: List<SectorCell?>,
        val lapSectors: List<SectorCell?>,
        val lastLap: SectorCell?,
        val gap: String?,
        val inPit: Boolean,
        val toLeader: String?,
        val ahead: String?,
        /** Genuine retirement; temporary stop is independent. */
        val retired: Boolean = false,
        val bestLap: SectorCell? = null,
        val stopped: Boolean = false,
    )

    /**
     * The lap the driver was on at [utcMs]: one more than F1's count of laps completed then.
     * Null before the first count received (a live feed joined mid-session knows nothing
     * earlier). Counting crossingTimeline instead started every live session at lap 1.
     */
    fun lapAt(utcMs: Long): Int? {
        val i = lapTimeline.floorIndex(utcMs)
        return if (i < 0) null else lapCounts[i] + 1
    }

    /** Each trip down the pit lane that came back out, as (in, out) UTC ms, from F1's InPit/PitOut. */
    val pitStops: List<Pair<Long, Long>> by lazy {
        val stops = mutableListOf<Pair<Long, Long>>()
        var inAt: Long? = null
        states.forEachIndexed { i, s ->
            if (s.inPit && inAt == null) inAt = snapshotTimeline[i]
            if (!s.inPit) inAt?.let { stops += it to snapshotTimeline[i]; inAt = null }
        }
        stops
    }

    /**
     * The driver's pace just before [utcMs]: the median of their last few laps, leaving out any
     * too slow or too fast to be laps run on track (a red flag, a lap counted twice).
     */
    fun typicalLapMs(utcMs: Long): Long? {
        val laps = lapWindows(utcMs).map { (start, end) -> end - start }.takeLast(RECENT_LAPS).sorted()
        return if (laps.isEmpty()) null else laps[laps.size / 2]
    }

    /** Every lap finished by [utcMs] that could have been run on track, as (start, end) UTC ms, oldest first. */
    fun lapWindows(utcMs: Long): List<Pair<Long, Long>> =
        (1 until crossingTimeline.size)
            .filter { crossingTimeline[it] <= utcMs }
            .map { crossingTimeline[it - 1] to crossingTimeline[it] }
            .filter { (start, end) -> end - start in MIN_LAP_MS..MAX_LAP_MS }

    internal fun hasRankAt(utcMs: Long): Boolean =
        states.getOrNull(snapshotTimeline.floorIndex(utcMs))?.position != null

    /** Whether the driver was out of the race at [utcMs]. */
    fun retiredAt(utcMs: Long): Boolean = retiredAtSnapshot(snapshotTimeline.floorIndex(utcMs))
    internal fun retiredAtSnapshot(index: Int): Boolean =
        index in states.indices && ((states as? CompactDriverSnapshots)?.retiredAt(index) ?: states[index].retired)

    /** When the driver's current spell in the pit lane began, if they're in it at [utcMs]. */
    fun inPitSince(utcMs: Long): Long? {
        var i = snapshotTimeline.floorIndex(utcMs)
        if (i < 0 || !states[i].inPit) return null
        while (i > 0 && states[i - 1].inPit) i--
        return snapshotTimeline[i]
    }

    /** When the driver last crossed the line, by [utcMs]. */
    fun lastCrossing(utcMs: Long): Long? = crossingTimeline.getOrNull(crossingTimeline.floorIndex(utcMs))

    fun at(utcMs: Long, includeRunningTime: Boolean = true): CardState? {
        return atSnapshot(snapshotTimeline.floorIndex(utcMs), utcMs, includeRunningTime)
    }

    internal fun atSnapshot(i: Int, utcMs: Long, includeRunningTime: Boolean = false): CardState? {
        if (i !in states.indices || i !in snapshotTimeline.indices) return null
        val s = states[i]
        val lapStart = if (includeRunningTime && !s.inPit) lastCrossing(utcMs) else null
        return CardState(
            position = s.position,
            sectors = s.sectors,
            lapSectors = s.lapSectors,
            runningMs = lapStart?.let { utcMs - it },
            lastLap = s.lastLap,
            gap = s.gap,
            inPit = s.inPit,
            toLeader = s.toLeader,
            ahead = s.ahead,
            bestLap = s.bestLap,
            retired = s.retired,
            stopped = s.stopped,
            positionChange = initialPosition?.let { first -> s.position?.let { first - it } },
        )
    }

    private companion object {
        const val MIN_LAP_MS = 40_000L
        const val MAX_LAP_MS = 240_000L
        const val RECENT_LAPS = 5
    }
}

internal fun LongArray.shiftedBy(offsetMs: Long) = LongArray(size) { this[it] + offsetMs }

/**
 * Builds every driver's [DriverLaps] from F1's TimingData and TimingDataF1 streams. Lines are "HH:MM:SS.mmm" +
 * JSON; the first is a full snapshot, the rest are partial updates under Lines.<number>. Sectors
 * come as a list in the snapshot and as an index-keyed object in updates; a sector's Value goes
 * empty when a new lap starts.
 */
/** Single scalar decision, shared by the archive and rolling live parser.
 * Rust mode owns the decision; the JVM only extracts normalized JSON evidence
 * and preserves timestamped Android domain objects. No per-frame JNI calls. */
internal class RetirementStatusPolicy(private val nativeRequested: Boolean) {
    private var reported: Boolean? = null

    fun decide(flags: Int): Int {
        if (!nativeRequested) return reference(flags)
        val value = app.apexview.tiled.NativeHotPath.retirementState(flags)
        val native = value in 0..3
        if (reported != native) {
            reported = native
            android.util.Log.i("PitwallNativeHotPath", "DRIVER_STATUS_ENGINE requested=RUST actual=" +
                if (native) "RUST reason=RETIREMENT_RECONCILED" else "JVM reason=NATIVE_UNAVAILABLE")
        }
        return if (native) value else reference(flags)
    }

    companion object {
        const val RETIRED = 1
        const val PENDING = 2
        const val ASSERT = 4
        const val CORRECT = 8
        const val FRESH = 16
        const val LAP_OR_PIT = 32
        const val SECTOR = 64

        fun reference(flags: Int): Int {
            if (flags !in 0..127) return -1
            if ((flags and ASSERT) != 0) return RETIRED
            if ((flags and RETIRED) == 0) return 0
            val pending = (flags and PENDING) != 0
            if ((flags and FRESH) == 0) return if (pending) RETIRED or PENDING else RETIRED
            val corrected = pending || (flags and CORRECT) != 0
            // A late final lap or pit marker is not an explicit retirement correction.
            if (corrected && (flags and (LAP_OR_PIT or SECTOR)) != 0) return 0
            return if (corrected) RETIRED or PENDING else RETIRED
        }
    }
}

object DriverTimingParser {
    private const val LATE_SECTOR_MS = 3_000L
    private const val MAX_MINI_SECTORS = 64

    /** Parser-owned primitive storage. Published histories receive exact-length snapshots. */
    private class Timestamps {
        private var values = LongArray(32)
        private var size = 0
        fun lastOrNull(): Long? = if (size == 0) null else values[size - 1]
        operator fun plusAssign(time: Long) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = time
        }
        fun toLongArray(): LongArray = values.copyOf(size)
        fun window(from: Long, until: Long): IntRange? = historyWindowRange(size, from, until) { values[it] }
        fun copyRange(first: Int, end: Int): LongArray = values.copyOfRange(first, end)
    }

    private class Live {
        var position: Int? = null
        val sector = arrayOfNulls<SectorCell>(3)
        val sectorFlags = Array(3) { BooleanArray(2) }   // personal, overall
        val sectorValue = Array(3) { "" }
        val lapSectors = arrayOfNulls<SectorCell>(3)
        var crossedAt: Long? = null
        var lastLap: SectorCell? = null
        var lastLapValue = ""
        val lastLapFlags = BooleanArray(2)
        var bestLap: SectorCell? = null
        var bestLapValue = ""
        val bestLapFlags = BooleanArray(2)
        val sectorSegments = Array(3) { emptyList<Int>() }
        val sectorPrevious = Array(3) { "" }
        var gap: String? = null
        var toLeader: String? = null
        var ahead: String? = null
        var inPit = false
        var retired = false
        var retirementAssertedAt = Long.MIN_VALUE
        var retirementCorrectionPending = false
        var primaryRetiredAt = Long.MIN_VALUE
        var primaryStoppedAt = Long.MIN_VALUE
        var primaryPitAt = Long.MIN_VALUE
        var primaryLapAt = Long.MIN_VALUE
        var stopped = false
        var laps: Int? = null
        val times = Timestamps()
        val snaps = ArrayList<DriverLaps.Snapshot>()
        val crossings = Timestamps()
        val lapTimes = Timestamps()
        val lapCounts = ArrayList<Int>()

        private fun sameCell(a: SectorCell?, b: SectorCell?): Boolean =
            a === b || (a != null && b != null && a.time == b.time && a.pace == b.pace &&
                a.segments == b.segments && a.previousTime == b.previousTime)

        private fun matches(previous: DriverLaps.Snapshot): Boolean =
            previous.position == position && previous.gap == gap && previous.inPit == inPit &&
                previous.toLeader == toLeader && previous.ahead == ahead && previous.retired == retired && previous.stopped == stopped &&
                sameCell(previous.lastLap, lastLap) && sameCell(previous.bestLap, bestLap) &&
                sector.indices.all { sameCell(previous.sectors[it], sector[it]) && sameCell(previous.lapSectors[it], lapSectors[it]) }

        fun snap(t: Long) {
            if (snaps.lastOrNull()?.let(::matches) == true) return
            // Share the immutable lists across gap/position-only snapshots rather
            // than retaining two new list/backing arrays for every such delta.
            val previous = snaps.lastOrNull()
            val currentSectors = previous?.sectors?.takeIf { old ->
                sector.indices.all { sameCell(old[it], sector[it]) }
            } ?: sector.toList()
            val completedSectors = previous?.lapSectors?.takeIf { old ->
                lapSectors.indices.all { sameCell(old[it], lapSectors[it]) }
            } ?: lapSectors.toList()
            val copy = DriverLaps.Snapshot(position, currentSectors, completedSectors, lastLap, gap, inPit, toLeader, ahead, retired, bestLap, stopped)
            if (times.lastOrNull() == t) snaps[snaps.lastIndex] = copy
            else { times += t; snaps += copy }
        }

    }

    /**
     * Parses the normal TimingData stream and the optional TimingDataF1 supplement together.
     * F1 publishes micro-sector segment status on the latter. Events are ordered by UTC so a
     * delayed snapshot from one topic cannot roll back a newer update from the other.
     */
    private data class TimedLine(val time: Long, val source: Int, val line: String, val prefix: Int)

    private fun timedLines(lines: Sequence<String>, source: Int, streamStart: Long): Sequence<TimedLine> =
        lines.mapNotNull { line ->
            val prefix = streamPrefixLength(line)
            if (line.length <= prefix) null
            else runCatching { TimedLine(streamStart + streamOffsetMs(line, prefix), source, line, prefix) }.getOrNull()
        }

    private fun materialize(drivers: Map<String, Live>, copySnapshots: Boolean) =
        drivers.mapValues { (_, driver) ->
            DriverLaps(driver.times.toLongArray(), if (copySnapshots) driver.snaps.toList() else driver.snaps,
                driver.crossings.toLongArray(), driver.lapTimes.toLongArray(), driver.lapCounts.toIntArray())
        }

    /** Copy only the requested preview range, not the entire growing race prefix.
     * Mutable parser-owned storage never crosses the publication boundary. */
    private fun materializePreview(drivers: Map<String, Live>, from: Long, until: Long): Map<String, DriverLaps> =
        drivers.mapNotNull { (number, driver) ->
            val range = driver.times.window(from, until) ?: return@mapNotNull null
            var first = range.first
            if (driver.snaps[first].inPit) while (first > 0 && driver.snaps[first - 1].inPit) first--
            val end = range.last + 1
            val crossings = driver.crossings.window(from, until)
            val laps = driver.lapTimes.window(from, until)
            number to DriverLaps(driver.times.copyRange(first, end), driver.snaps.subList(first, end).toList(),
                crossings?.let { driver.crossings.copyRange((it.first - 8).coerceAtLeast(0), it.last + 1) } ?: LongArray(0),
                laps?.let { driver.lapTimes.copyRange(it.first, it.last + 1) } ?: LongArray(0),
                laps?.let { driver.lapCounts.subList(it.first, it.last + 1).toIntArray() } ?: IntArray(0),
                driver.snaps.firstOrNull()?.position)
        }.toMap()

    /** Publish the playhead's prefix, then keep following it while the archive is loading. */
    internal fun previews(
        lines: Iterator<String>,
        streamStart: Long,
        targetUtc: () -> Long,
        checkActive: () -> Unit,
    ): Sequence<DriverTimingPreview> = previews(lines, streamStart, targetUtc, false, checkActive)

    internal fun previews(
        lines: Iterator<String>,
        streamStart: Long,
        targetUtc: () -> Long,
        nativeRequested: Boolean,
        checkActive: () -> Unit,
    ): Sequence<DriverTimingPreview> = previews(lines, streamStart, targetUtc, nativeRequested, checkActive, {})

    /** Completion is invoked only at ordered EOF. The parser no longer owns
     * or mutates the materialized history, so it can become the final index
     * without a second JSON pass when no supplemental topic is available. */
    internal fun previews(
        lines: Iterator<String>,
        streamStart: Long,
        targetUtc: () -> Long,
        nativeRequested: Boolean,
        checkActive: () -> Unit,
        onComplete: (build: () -> DriverTimingParse) -> Unit,
        windowBackMs: Long? = null,
    ): Sequence<DriverTimingPreview> = sequence {
        require(windowBackMs == null || windowBackMs in 1L..600_000L)
        val statusPolicy = RetirementStatusPolicy(nativeRequested)
        val drivers = mutableMapOf<String, Live>()
        val states = StateHistoryAccumulator()
        var through = Long.MIN_VALUE
        var publishedThrough: Long? = null
        var publishedFrom = Long.MIN_VALUE
        while (lines.hasNext()) {
            checkActive()
            val line = lines.next()
            val prefix = streamPrefixLength(line)
            if (prefix >= line.length) continue
            val time = streamStart + streamOffsetMs(line, prefix)
            if (time < through) return@sequence  // Use the full ordered merge for a disordered archive.
            through = time
            val rows = runCatching { JSONObject(line.substring(prefix)).optJSONObject("Lines") }.getOrNull()
                ?: continue
            states.accept(rows, time)
            rows.keys().forEach { number ->
                val row = rows.optJSONObject(number) ?: return@forEach
                val driver = drivers.getOrPut(number) { Live() }
                apply(driver, row, time, statusPolicy)
                driver.snap(time)
            }
            val target = targetUtc()
            val last = publishedThrough
            // Never knowingly leave the current playback clock outside the published prefix.
            val needsPreview = last == null || target > last || target < publishedFrom ||
                last - target < TOWER_PREVIEW_LOOKAHEAD_MS && time - last >= TOWER_PREVIEW_LOOKAHEAD_MS
            if (time >= target && needsPreview && drivers.values.any { it.position != null }) {
                val from = windowBackMs?.let { target - it } ?: Long.MIN_VALUE
                val until = if (windowBackMs == null) time else minOf(time, target + 2 * TOWER_PREVIEW_LOOKAHEAD_MS)
                publishedThrough = until
                publishedFrom = from
                val history = if (windowBackMs == null) DriverTimingParse(materialize(drivers, copySnapshots = true), states.finish())
                    else DriverTimingParse(materializePreview(drivers, from, until), states.finishWindow(from, until))
                yield(DriverTimingPreview(until, history, from.takeIf { windowBackMs != null }))
            }
        }
        checkActive()
        onComplete { DriverTimingParse(materialize(drivers, copySnapshots = false), states.finish()) }
    }

    fun parse(stream: String, streamStart: Long, timingF1: String = "",
              nativeRequested: Boolean = false): Map<String, DriverLaps> =
        parseWithEngine(stream, streamStart, timingF1, null, nativeRequested)

    internal fun parse(
        stream: String,
        streamStart: Long,
        timingF1: String,
        stateHistory: StateHistoryAccumulator?,
        checkpoint: ((String) -> Unit)? = null,
        checkActive: () -> Unit = {},
        onProgress: ((Long, () -> Map<String, DriverLaps>) -> Unit)? = null,
    ): Map<String, DriverLaps> = parseWithEngine(stream, streamStart, timingF1, stateHistory,
        false, checkpoint, checkActive, onProgress)

    internal fun parseWithEngine(
        stream: String,
        streamStart: Long,
        timingF1: String,
        stateHistory: StateHistoryAccumulator?,
        nativeRequested: Boolean,
        checkpoint: ((String) -> Unit)? = null,
        checkActive: () -> Unit = {},
        onProgress: ((Long, () -> Map<String, DriverLaps>) -> Unit)? = null,
    ): Map<String, DriverLaps> {
        checkpoint?.invoke("checking timestamp order primaryChars=${stream.length} supplementChars=${timingF1.length}")
        return parseArchiveLines(
            stream.lineSequence(), timingF1.lineSequence(), streamStart,
            timingStreamIsChronological(stream, checkActive) && timingStreamIsChronological(timingF1, checkActive),
            stateHistory, nativeRequested, checkpoint, checkActive, onProgress = onProgress,
        )
    }

    internal fun parseArchiveLines(
        primaryLines: Sequence<String>,
        supplementLines: Sequence<String>,
        streamStart: Long,
        chronological: Boolean,
        stateHistory: StateHistoryAccumulator?,
        nativeRequested: Boolean,
        checkpoint: ((String) -> Unit)? = null,
        checkActive: () -> Unit = {},
        progressWindow: ((Long) -> LongRange)? = null,
        onProgress: ((Long, () -> Map<String, DriverLaps>) -> Unit)? = null,
    ): Map<String, DriverLaps> {
        val statusPolicy = RetirementStatusPolicy(nativeRequested)
        val drivers = mutableMapOf<String, Live>()

        var records = 0
        var invalidJson = 0
        var missingLines = 0
        fun consume(event: TimedLine) {
            checkActive()
            records++
            if (records == 1 || records % 1024 == 0) {
                checkpoint?.invoke("merge records=$records invalidJson=$invalidJson missingLines=$missingLines drivers=${drivers.size}")
            }
            val json = runCatching { JSONObject(event.line.substring(event.prefix)) }.getOrNull() ?: run { invalidJson++; return }
            val lines = json.optJSONObject("Lines") ?: run { missingLines++; return }
            if (event.source == 0) stateHistory?.accept(lines, event.time)
            lines.keys().forEach { number ->
                val d = lines.optJSONObject(number) ?: return@forEach
                val live = drivers.getOrPut(number) { Live() }
                apply(live, d, event.time, statusPolicy, event.source)
                live.snap(event.time)
            }
            if (records % 1024 == 0) onProgress?.invoke(event.time) {
                val window = progressWindow?.invoke(event.time)
                if (window == null) materialize(drivers, copySnapshots = true)
                else materializePreview(drivers, window.first, window.last)
            }
        }

        if (chronological) {
            checkpoint?.invoke("merging chronological streams")
            // Merge the two sorted topics with one pending line per source. This avoids creating
            // and sorting a large in-memory Event list on Android TV for multi-megabyte archives.
            val primary = timedLines(primaryLines, 0, streamStart).iterator()
            val supplement = timedLines(supplementLines, 1, streamStart).iterator()
            var a = if (primary.hasNext()) primary.next() else null
            var b = if (supplement.hasNext()) supplement.next() else null
            while (a != null || b != null) {
                val takePrimary = b == null || (a != null &&
                    (a.time < b.time || (a.time == b.time && a.source <= b.source)))
                if (takePrimary) {
                    consume(a!!)
                    a = if (primary.hasNext()) primary.next() else null
                } else {
                    consume(b!!)
                    b = if (supplement.hasNext()) supplement.next() else null
                }
            }
        } else {
            checkpoint?.invoke("sorting out-of-order streams")
            // Preserve the previous defensive behaviour for malformed/out-of-order archives.
            timedLines(primaryLines, 0, streamStart)
                .plus(timedLines(supplementLines, 1, streamStart))
                .sortedWith(compareBy<TimedLine> { it.time }.thenBy { it.source })
                .forEach(::consume)
        }

        checkpoint?.invoke("building history records=$records invalidJson=$invalidJson missingLines=$missingLines drivers=${drivers.size}")
        return materialize(drivers, copySnapshots = false)
    }

    private fun apply(l: Live, d: JSONObject, t: Long, statusPolicy: RetirementStatusPolicy, source: Int = 0) {
        d.optString("Position").toIntOrNull()?.let { l.position = it }
        val rawCompleted = d.optInt("NumberOfLaps", -1).takeIf { it >= 0 }
        val previousLaps = l.laps
        val staleLap = rawCompleted != null && previousLaps != null && rawCompleted < previousLaps
        val primary = source == 0
        val lapAllowed = primary || l.primaryLapAt != t
        if (primary && rawCompleted != null) l.primaryLapAt = t
        val completed = rawCompleted.takeIf { lapAllowed }
        val pitAllowed = !staleLap && (primary || l.primaryPitAt != t)
        val stoppedAllowed = !staleLap && (primary || l.primaryStoppedAt != t)
        val retiredAllowed = !staleLap && (primary || l.primaryRetiredAt != t)
        if (primary && (d.has("InPit") || d.has("PitOut"))) l.primaryPitAt = t
        if (primary && d.has("Stopped")) l.primaryStoppedAt = t
        if (primary && d.has("Retired")) l.primaryRetiredAt = t
        if (pitAllowed && d.has("InPit")) l.inPit = d.optBoolean("InPit")
        val pitExit = pitAllowed && d.optBoolean("PitOut")
        if (pitExit) l.inPit = false
        // Same-timestamp supplemental deltas may supply missing fields, never
        // contradict explicit primary lifecycle evidence for that timestamp.
        if (stoppedAllowed && d.has("Stopped")) l.stopped = d.optBoolean("Stopped")
        val asserted = retiredAllowed && d.optBoolean("Retired")
        if (asserted) l.retirementAssertedAt = t
        // Qualifying gives the gap to the fastest; races the interval to the car ahead.
        d.optString("TimeDiffToFastest").takeIf { d.has("TimeDiffToFastest") }?.let { l.gap = it.ifEmpty { null } }
        d.optJSONObject("IntervalToPositionAhead")?.let { o ->
            if (o.has("Value")) l.gap = o.optString("Value").ifEmpty { null }
        }
        // For the tower: both gaps, under whichever names this session uses.
        d.optJSONObject("GapToLeader")?.let { o ->
            if (o.has("Value")) l.toLeader = o.optString("Value").ifEmpty { null }
        }
        if (d.has("TimeDiffToFastest")) l.toLeader = d.optString("TimeDiffToFastest").ifEmpty { null }
        d.optJSONObject("IntervalToPositionAhead")?.let { o -> if (o.has("Value")) l.ahead = o.optString("Value").ifEmpty { null } }
        if (d.has("TimeDiffToPositionAhead")) l.ahead = d.optString("TimeDiffToPositionAhead").ifEmpty { null }

        val crossing = completed != null && previousLaps != null && completed > previousLaps
        if ((crossing || pitExit) && !d.optBoolean("Stopped") &&
            (primary || l.primaryStoppedAt != t)) l.stopped = false
        val sectors = d.opt("Sectors")
        if (asserted || l.retired) {
            val fresh = !staleLap && t > l.retirementAssertedAt
            val corrected = fresh && retiredAllowed && d.has("Retired") && !d.isNull("Retired") &&
                d.optString("Retired").equals("false", ignoreCase = true)
            var sectorProgress = false
            if (fresh && (corrected || l.retirementCorrectionPending) &&
                !crossing && previousLaps != null && !l.inPit && !l.stopped) {
                // A genuinely new S1/S2 split corroborates an explicit correction.
                // Repeated values, late S3 and unrelated gap changes are insufficient.
                forEachSector(sectors) { i, sector ->
                    val seconds = sector.optString("Value").toDoubleOrNull()
                    if (i < 2 && l.sectorValue[i].isEmpty() &&
                        ((seconds != null && seconds.isFinite() && seconds > 0) ||
                            hasFreshMiniProgress(l.sectorSegments[i], sector.opt("Segments"))))
                        sectorProgress = true
                }
            }
            // Sparse non-events do not cross JNI, including every normal racing row.
            if (asserted || corrected || fresh && (crossing || pitExit || sectorProgress)) {
                var flags = 0
                if (l.retired) flags = flags or RetirementStatusPolicy.RETIRED
                if (l.retirementCorrectionPending) flags = flags or RetirementStatusPolicy.PENDING
                if (asserted) flags = flags or RetirementStatusPolicy.ASSERT
                if (corrected) flags = flags or RetirementStatusPolicy.CORRECT
                if (fresh) flags = flags or RetirementStatusPolicy.FRESH
                if (crossing || pitExit) flags = flags or RetirementStatusPolicy.LAP_OR_PIT
                if (sectorProgress) flags = flags or RetirementStatusPolicy.SECTOR
                val next = statusPolicy.decide(flags)
                l.retired = (next and RetirementStatusPolicy.RETIRED) != 0
                l.retirementCorrectionPending = (next and RetirementStatusPolicy.PENDING) != 0
            }
        }
        if (!staleLap) forEachSector(sectors) { i, sector ->
            // At the line, keep the finished lap intact while applying its final split.
            if (!crossing || sector.optString("Value").isNotEmpty() || i == 2 && !sector.has("Value")) {
                applySector(l, i, sector, t)
            }
        }
        latestObject(d.opt("Stats"))?.let { stats ->
            if (stats.has("TimeDiffToFastest")) l.toLeader = stats.optString("TimeDiffToFastest").ifEmpty { null }
            // F1's field name has a lowercase "t" in "to".
            if (stats.has("TimeDifftoPositionAhead")) l.ahead = stats.optString("TimeDifftoPositionAhead").ifEmpty { null }
        }
        latestObject(d.opt("BestLapTimes"))?.let { o ->
            if (o.has("Value")) l.bestLapValue = o.optString("Value")
            if (o.has("PersonalFastest")) l.bestLapFlags[0] = o.optBoolean("PersonalFastest")
            if (o.has("OverallFastest")) l.bestLapFlags[1] = o.optBoolean("OverallFastest")
            l.bestLap = l.bestLapValue.takeIf { it.isNotEmpty() }?.let { SectorCell(it, pace(l.bestLapFlags)) }
        }
        d.optJSONObject("LastLapTime")?.let { o ->
            if (o.has("Value")) l.lastLapValue = o.optString("Value")
            if (o.has("PersonalFastest")) l.lastLapFlags[0] = o.optBoolean("PersonalFastest")
            if (o.has("OverallFastest")) l.lastLapFlags[1] = o.optBoolean("OverallFastest")
            l.lastLap = l.lastLapValue.takeIf { it.isNotEmpty() }?.let { SectorCell(it, pace(l.lastLapFlags)) }
        }
        d.optJSONObject("BestLapTime")?.let { o ->
            if (o.has("Value")) l.bestLapValue = o.optString("Value")
            if (o.has("PersonalFastest")) l.bestLapFlags[0] = o.optBoolean("PersonalFastest")
            if (o.has("OverallFastest")) l.bestLapFlags[1] = o.optBoolean("OverallFastest")
            l.bestLap = l.bestLapValue.takeIf { it.isNotEmpty() }?.let { SectorCell(it, pace(l.bestLapFlags)) }
        }
        if (crossing) {
            l.crossings += t
            l.crossedAt = t
            l.sector.copyInto(l.lapSectors)
            l.sector.fill(null)
            l.sectorValue.fill("")
            l.sectorPrevious.fill("")
            l.sectorSegments.fill(emptyList())
            l.sectorFlags.forEach { it.fill(false) }
            // A batched update can also contain the beginning of the new lap.
            forEachSector(sectors) { i, sector ->
                if (i < 2 && sector.optString("Value").isEmpty()) applySector(l, i, sector, t)
            }
        }
        if (completed != null && !staleLap && l.lapCounts.lastOrNull() != completed) {
            l.laps = completed
            l.lapTimes += t
            l.lapCounts += completed
        }
    }

    private inline fun forEachSector(sectors: Any?, apply: (Int, JSONObject) -> Unit) {
        when (sectors) {
            is JSONArray -> for (i in 0 until minOf(3, sectors.length())) {
                sectors.optJSONObject(i)?.let { apply(i, it) }
            }
            is JSONObject -> for (i in 0..2) {
                sectors.optJSONObject(i.toString())?.let { apply(i, it) }
            }
        }
    }

    private fun applySector(l: Live, i: Int, o: JSONObject, t: Long) {
        // The last split can arrive after the crossing, even after the new S1 has started.
        val crossed = l.crossedAt
        if (i == 2 && crossed != null && t - crossed in 0..LATE_SECTOR_MS) {
            val previous = l.lapSectors[i]
            val reported = o.optString("Value")
            val existing = previous?.segments.orEmpty()
            val segments = mergeSegments(existing, o.opt("Segments"), keepCleared = false)
            if (reported.isNotEmpty() || segments !== existing) {
                val pace = when {
                    o.optBoolean("OverallFastest") -> Pace.PURPLE
                    o.optBoolean("PersonalFastest") -> Pace.GREEN
                    reported.isEmpty() || !o.has("OverallFastest") && !o.has("PersonalFastest") ->
                        previous?.pace?.takeUnless { it == Pace.NONE } ?: if (reported.isEmpty()) Pace.NONE else Pace.YELLOW
                    else -> Pace.YELLOW
                }
                l.lapSectors[i] = SectorCell(reported.ifEmpty { previous?.time.orEmpty() }, pace, segments,
                    o.optString("PreviousValue").ifEmpty { previous?.previousTime.orEmpty() })
            }
            return
        }
        if (o.has("Value")) {
            val value = o.optString("Value")
            // An empty value also occurs in an in-progress split. Only a completed split
            // changing to empty clears fallback state when no explicit zero statuses arrive.
            if (value.isEmpty() && l.sectorValue[i].isNotEmpty()) {
                l.sectorFlags[i].fill(false)
                l.sectorSegments[i] = emptyList()
                l.sectorPrevious[i] = ""
            }
            l.sectorValue[i] = value
        }
        if (o.has("PreviousValue")) l.sectorPrevious[i] = o.optString("PreviousValue")
        if (o.has("PersonalFastest")) l.sectorFlags[i][0] = o.optBoolean("PersonalFastest")
        if (o.has("OverallFastest")) l.sectorFlags[i][1] = o.optBoolean("OverallFastest")
        val segments = mergeSegments(l.sectorSegments[i], o.opt("Segments"))
        l.sectorSegments[i] = segments
        l.sector[i] = if (l.sectorValue[i].isNotEmpty() || segments.isNotEmpty()) {
            SectorCell(l.sectorValue[i], if (l.sectorValue[i].isEmpty()) Pace.NONE else pace(l.sectorFlags[i]), segments, l.sectorPrevious[i])
        } else null
    }

    /** Only an explicit previously uncompleted slot becoming a measured
     * green/yellow/purple mini-sector beyond the last completed slot counts.
     * Unknown, pit, empty, recoloured or duplicate values are not proof. */
    private fun hasFreshMiniProgress(previous: List<Int>, raw: Any?): Boolean {
        if (previous.isEmpty()) return false
        fun completed(status: Int) = status == 2048 || status == 2049 || status == 2051
        val last = previous.indexOfLast(::completed)
        if (last < 0) return false
        fun fresh(index: Int, value: JSONObject?): Boolean =
            index in (last + 1) until previous.size && previous[index] == 0 &&
                completed(value?.optInt("Status", -1) ?: -1)
        return when (raw) {
            is JSONArray -> (0 until minOf(raw.length(), MAX_MINI_SECTORS)).any { fresh(it, raw.optJSONObject(it)) }
            is JSONObject -> raw.keys().asSequence().any { key ->
                key.toIntOrNull()?.let { fresh(it, raw.optJSONObject(key)) } == true
            }
            else -> false
        }
    }

    private fun mergeSegments(previous: List<Int>, raw: Any?, keepCleared: Boolean = true): List<Int> {
        var changed: MutableList<Int>? = null
        fun set(index: Int, segment: JSONObject?) {
            if (index !in 0 until MAX_MINI_SECTORS) return
            val status = segment?.optInt("Status", -1) ?: return
            if (status !in 0..4096 || status == 0 && !keepCleared) return
            if ((changed ?: previous).getOrNull(index) == status) return
            val next = changed ?: previous.toMutableList().also { changed = it }
            repeat((index + 1 - next.size).coerceAtLeast(0)) { next.add(0) }
            next[index] = status
        }
        when (raw) {
            is JSONArray -> for (i in 0 until minOf(raw.length(), MAX_MINI_SECTORS)) set(i, raw.optJSONObject(i))
            is JSONObject -> raw.keys().forEach { key ->
                key.toIntOrNull()?.let { set(it, raw.optJSONObject(key)) }
            }
        }
        return changed ?: previous
    }

    /** Latest qualifying-part entry in F1's keyed-object or array delta shapes. */
    private fun latestObject(value: Any?): JSONObject? = when (value) {
        is JSONArray -> (value.length() - 1 downTo 0).firstNotNullOfOrNull { value.optJSONObject(it) }
        is JSONObject -> value.keys().asSequence()
            .mapNotNull { key -> key.toIntOrNull()?.let { it to value.optJSONObject(key) } }
            .filter { it.second != null }
            .maxByOrNull { it.first }?.second
        else -> null
    }

    private fun pace(flags: BooleanArray) = when {
        flags[1] -> Pace.PURPLE
        flags[0] -> Pace.GREEN
        else -> Pace.YELLOW
    }

    private fun offsetMs(line: String): Long = streamOffsetMs(line)
}

/** A tyre: its compound (SOFT, MEDIUM, HARD, INTERMEDIATE, WET) and laps done on it. */
class Tyre(val compound: String, val laps: Int)

/** One driver's tyres through the session. */
class TyreLog internal constructor(internal val timeline: TimingTimestamps, private val tyres: List<Tyre>) {
    constructor(times: LongArray, tyres: List<Tyre>) : this(TimingTimestamps.jvm(times, times.size), tyres)
    internal val times: LongArray get() = timeline.legacyArray()
    internal fun compact(pool: SpeedTimestampPool) = TyreLog(timeline.compact(pool), tyres)
    fun at(utcMs: Long): Tyre? = atSnapshot(timeline.floorIndex(utcMs))
    internal fun atSnapshot(index: Int): Tyre? = tyres.getOrNull(index)
}

/**
 * Reads F1's TimingAppData stream: per driver, a list of stints (as a list in the first line,
 * then index-keyed updates). The current tyre is the last stint's compound and its lap count.
 */
object TyreParser {

    fun parse(stream: String, streamStart: Long): Map<String, TyreLog> {
        val stints = mutableMapOf<String, MutableMap<Int, Pair<String, Int>>>()
        val times = mutableMapOf<String, MutableList<Long>>()
        val tyres = mutableMapOf<String, MutableList<Tyre>>()
        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            val lines = runCatching { org.json.JSONObject(line.substring(streamPrefixLength(line))).optJSONObject("Lines") }.getOrNull() ?: return@forEach
            val t = streamStart + offsetMs(line)
            lines.keys().forEach { number ->
                val d = lines.optJSONObject(number) ?: return@forEach
                val list = stints.getOrPut(number) { mutableMapOf() }
                val entries: List<Pair<Int, org.json.JSONObject>> = when (val s = d.opt("Stints")) {
                    is org.json.JSONArray -> (0 until s.length()).mapNotNull { i -> s.optJSONObject(i)?.let { i to it } }
                    is org.json.JSONObject -> s.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { i -> s.optJSONObject(k)?.let { i to it } } }.toList()
                    else -> return@forEach
                }
                entries.forEach { (i, o) ->
                    val (compound, laps) = list[i] ?: ("" to 0)
                    list[i] = o.optString("Compound").ifEmpty { compound } to (if (o.has("TotalLaps")) o.optInt("TotalLaps") else laps)
                }
                val current = list.maxByOrNull { it.key }?.value ?: return@forEach
                if (current.first.isEmpty()) return@forEach
                times.getOrPut(number) { mutableListOf() } += t
                tyres.getOrPut(number) { mutableListOf() } += Tyre(current.first, current.second)
            }
        }
        return times.mapValues { (n, ts) -> TyreLog(ts.toLongArray(), tyres.getValue(n)) }
    }

    private fun offsetMs(line: String): Long = streamOffsetMs(line)
}
