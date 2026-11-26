package app.apexview

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Which archived session a replay belongs to, from F1 TV's video metadata. */
data class SessionRef(
    val year: Int,
    val meetingKey: Int?,
    val sessionKey: Int?,
    val circuitKey: Int?,
    val meetingName: String,
    val title: String,
    /** F1 catalog's scheduled session start, used when a TV stream exposes no program clock. */
    val sessionStartUtcMs: Long? = null,
)

/** Prefer the video's exact clock, but never lose the catalog's session-start fallback. */
internal fun archiveLookupTime(watchedUtc: Long?, sessionStartUtcMs: Long?): Long? =
    watchedUtc ?: sessionStartUtcMs

internal data class ReplayPlayhead(
    val positionMs: Long,
    val videoUtcMs: Long?,
    val offsetMs: Long,
    val cachedVideoClock: Boolean = false,
) {
    fun utcAt(archiveStart: Long): Long = (videoUtcMs ?: archiveStart + positionMs) + offsetMs
}

/** Keeps a streaming preview centered when the manifest clock corrects a replay estimate. */
internal class ReplayPositionWindow(initialTargetMs: Long) {
    var targetMs = initialTargetMs.coerceAtLeast(0L)
        private set
    val firstMs get() = (targetMs - 90_000L).coerceAtLeast(0L)
    val lastMs get() = targetMs + 120_000L

    fun retarget(currentMs: Long): Boolean {
        val next = currentMs.coerceAtLeast(0L)
        if (abs(next - targetMs) < 30_000L) return false
        targetMs = next
        return true
    }
}

enum class LapState { UNKNOWN, PIT, OUT_LAP, PUSH, COOL_DOWN, KNOCKED_OUT }

class DriverInfo(
    val number: String,
    val tla: String,
    val color: Int,
    val firstName: String = "",
    val lastName: String = "",
    val team: String = "",
)

/** A team radio recording: when it was published, whose it is, and its file under the session. */
class RadioClip(val utc: Long, val number: String, val path: String)

/** A numbered corner, in archive coordinates. */
class Corner(val number: Int, val x: Float, val y: Float)

/** A circuit from MultiViewer: its outline, numbered corners, and where each marshal sector starts. */
class Circuit(val outline: FloatArray, val corners: List<Corner>, val sectors: List<Corner>)

/** A yellow (level 1), double yellow (2) or clear (0) in one marshal sector. Sector 0 is "track clear". */
class SectorFlag(val utc: Long, val sector: Int, val level: Int)

/** What the whole track is under, with the words the map shows for it. */
enum class TrackCondition(val label: String?) {
    CLEAR(null), SAFETY_CAR("Safety car"), SAFETY_CAR_ENDING("Safety car in this lap"),
    VSC("Virtual safety car"), VSC_ENDING("VSC ending"), RED("Red flag");

    /** About to go green: the yellow pulses until it does. */
    val ending get() = this == SAFETY_CAR_ENDING || this == VSC_ENDING
    val safetyCar get() = this == SAFETY_CAR || this == SAFETY_CAR_ENDING
}

/** A time race control has announced ("Q3 WILL RESUME AT 17:04"): what, and when (UTC ms). */
class Resumption(val announcedUtc: Long, val atUtc: Long, val what: String)

/**
 * The flags at one moment: the whole track's condition, the yellow sectors (number to level),
 * and the next announced restart, if one is coming.
 */
class FlagPicture(val track: TrackCondition, val yellow: Map<Int, Int>, val resumption: Resumption? = null) {
    /** The line at the foot of the map: "Red flag · Q3 resumes in 4:12", "Safety car", or null. */
    fun caption(t: Long): String? = listOfNotNull(
        track.label,
        resumption?.let { "${it.what} in ${countdown(it.atUtc - t)}" },
    ).joinToString(" · ").ifEmpty { null }

    private fun countdown(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * Race control's flags through a session: sector yellows from its messages, the whole track's
 * condition from F1's track status (4 safety car, 5 red, 6 VSC, 7 VSC ending), when the safety
 * car was called in, and announced restart times. All oldest first.
 */
class TrackFlags(
    val sectors: List<SectorFlag>,
    val status: List<Pair<Long, Int>>,
    val safetyCarIn: List<Long> = emptyList(),
    val resumptions: List<Resumption> = emptyList(),
    /** Red flags from race control, start to green (UTC ms): track status doesn't always mark them. */
    val redFlags: List<Pair<Long, Long>> = emptyList(),
) {
    // Compact immutable flag history, shared by map/tower owners. Created
    // only when Rust is requested; no copying of per-car race histories.
    private val nativeHistory: java.nio.ByteBuffer? by lazy {
        val lengths = longArrayOf(status.size * 2L, sectors.size * 3L, safetyCarIn.size.toLong(),
            redFlags.size * 2L, resumptions.size * 2L)
        if (lengths.any { it > 8192L }) null else try {
            val buffer = java.nio.ByteBuffer.allocateDirect(((5 + lengths.sum()) * 8).toInt())
                .order(java.nio.ByteOrder.nativeOrder())
            lengths.forEach { buffer.putLong(it) }
            status.forEach { (utc, code) -> buffer.putLong(utc).putLong(code.toLong()) }
            sectors.forEach { buffer.putLong(it.utc).putLong(it.sector.toLong()).putLong(it.level.toLong()) }
            safetyCarIn.forEach { buffer.putLong(it) }
            redFlags.forEach { (from, to) -> buffer.putLong(from).putLong(to) }
            resumptions.forEach { buffer.putLong(it.announcedUtc).putLong(it.atUtc) }
            buffer.asReadOnlyBuffer()
        } catch (_: OutOfMemoryError) { null }
    }
    // One bounded output buffer, guarded because local tower and map may
    // sample on different threads. No mutable result leaves the lock.
    private val nativeResult by lazy { LongArray(67) }
    private var nativeFailed = false
    private var nativeReported = false
    private var lastNativePicture: FlagPicture? = null
    @Synchronized private fun nativeAt(t: Long): FlagPicture? {
        if (nativeFailed) return null
        val history = nativeHistory
        if (history == null || !app.apexview.tiled.NativeHotPath.flags(history, t, nativeResult)) {
            nativeFailed = true
            android.util.Log.i("PitwallNativeHotPath", "FLAG_ENGINE requested=RUST actual=JVM reason=NATIVE_FLAG_LIMIT_OR_UNAVAILABLE")
            return null
        }
        if (!nativeReported) {
            nativeReported = true
            android.util.Log.i("PitwallNativeHotPath", "FLAG_ENGINE requested=RUST actual=RUST reason=FLAGS_SAMPLED")
        }
        val track = when (nativeResult[0].toInt()) {
            4 -> TrackCondition.SAFETY_CAR
            5 -> TrackCondition.RED
            6 -> TrackCondition.VSC
            7 -> TrackCondition.VSC_ENDING
            8 -> TrackCondition.SAFETY_CAR_ENDING
            else -> TrackCondition.CLEAR
        }
        val resume = resumptions.getOrNull(nativeResult[1].toInt())
        val previous = lastNativePicture
        var count = 0
        var same = previous != null && previous.track == track && previous.resumption === resume
        for (id in 1..64) {
            val level = nativeResult[id + 2].toInt()
            if (level != 0) {
                count++
                if (previous?.yellow?.get(id) != level) same = false
            }
        }
        if (same && previous!!.yellow.size == count) return previous
        val yellow = HashMap<Int, Int>(count)
        for (id in 1..64) {
            val level = nativeResult[id + 2].toInt()
            if (level != 0) yellow[id] = level
        }
        return FlagPicture(track, yellow, resume).also { lastNativePicture = it }
    }

    fun at(t: Long, nativeRequested: Boolean = false): FlagPicture {
        if (nativeRequested) nativeAt(t)?.let { return it }
        var code = 1
        var since = 0L
        for ((utc, c) in status) {
            if (utc > t) break
            if (c != code) since = utc
            code = c
        }
        val track = if (redFlags.any { (from, to) -> t in from until to }) TrackCondition.RED else when (code) {
            // "Safety car in this lap" during this safety car period: it's coming in.
            4 -> if (safetyCarIn.any { it in since..t }) TrackCondition.SAFETY_CAR_ENDING else TrackCondition.SAFETY_CAR
            5 -> TrackCondition.RED
            6 -> TrackCondition.VSC
            7 -> TrackCondition.VSC_ENDING
            else -> TrackCondition.CLEAR
        }
        val yellow = HashMap<Int, Int>()
        for (f in sectors) {
            if (f.utc > t) break
            when {
                f.sector == 0 -> yellow.clear()
                f.level == 0 -> yellow.remove(f.sector)
                else -> yellow[f.sector] = f.level
            }
        }
        // The latest announcement still ahead; announced within the hour, so a stale one never lingers.
        val resumption = resumptions.lastOrNull { it.announcedUtc <= t && t < it.atUtc && t - it.announcedUtc < 3_600_000 }
        return FlagPicture(track, yellow, resumption)
    }

    companion object {
        val NONE = TrackFlags(emptyList(), emptyList())
    }
}

/**
 * The circuit as a closed loop, with distance along it. [progress] turns a car's position into
 * how far round the lap it is (0..1), measured from the loop's first point.
 */
class TrackGeometry(val outline: FloatArray) {
    private val n = outline.size / 2
    private val along = FloatArray(n)
    // Exact nearest-point queries now use a retained balanced spatial index.
    // The result is identical to the prior linear scan, including vertex ties.
    private val vertexIndex = OverlayVertexIndex(outline)
    val length: Float

    /**
     * True when the lap runs counter-clockwise as the track map draws it. The map keeps the
     * archive's y-up orientation (rotation only), so the sign of the outline's area decides.
     */
    val counterClockwise: Boolean

    init {
        for (i in 1 until n) along[i] = along[i - 1] + dist(i - 1, i)
        length = if (n > 1) along[n - 1] + dist(n - 1, 0) else 0f
        var area = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += outline[i * 2].toDouble() * outline[j * 2 + 1] - outline[j * 2].toDouble() * outline[i * 2 + 1]
        }
        counterClockwise = area > 0
    }

    private fun dist(a: Int, b: Int) =
        hypot(outline[a * 2] - outline[b * 2], outline[a * 2 + 1] - outline[b * 2 + 1])

    fun nearestIndex(x: Float, y: Float): Int = vertexIndex.nearest(x, y)

    /**
     * The point [f] of the way round the lap (0..1 from the loop's first point) and the unit
     * direction of travel there, as x, y, dx, dy.
     */
    fun pointAt(f: Float): FloatArray {
        if (n < 2 || length == 0f) return floatArrayOf(0f, 0f, 1f, 0f)
        val target = f.mod(1f) * length
        // First segment whose ending distance >= target, including exact vertex
        // boundaries. Preserve the old normal/direction at corners.
        var low = 1
        var high = n
        while (low < high) {
            val mid = (low + high) ushr 1
            val end = if (mid == n) length else along[mid]
            if (end < target) low = mid + 1 else high = mid
        }
        val i = (low - 1).coerceIn(0, n - 1)
        val j = (i + 1) % n
        val end = if (j == 0) length else along[j]
        val span = end - along[i]
        val k = if (span == 0f) 0f else ((target - along[i]) / span).coerceIn(0f, 1f)
        val ax = outline[i * 2]; val ay = outline[i * 2 + 1]
        val bx = outline[j * 2]; val by = outline[j * 2 + 1]
        val len = hypot(bx - ax, by - ay).coerceAtLeast(0.001f)
        return floatArrayOf(ax + (bx - ax) * k, ay + (by - ay) * k, (bx - ax) / len, (by - ay) / len)
    }

    /** Projected onto the nearest segment, so the value moves smoothly between outline points. */
    fun progress(x: Float, y: Float): Float {
        if (n < 2 || length == 0f) return 0f
        val i = nearestIndex(x, y)
        var best = along[i]
        var bestD = Float.MAX_VALUE
        // Avoid allocating a two-element list for every car-position projection.
        for (side in 0..1) {
            val j = if (side == 0) (i - 1 + n) % n else i
            val k = (j + 1) % n
            val ax = outline[j * 2]; val ay = outline[j * 2 + 1]
            val bx = outline[k * 2]; val by = outline[k * 2 + 1]
            val vx = bx - ax; val vy = by - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 == 0f) 0f else (((x - ax) * vx + (y - ay) * vy) / len2).coerceIn(0f, 1f)
            val px = ax + vx * t; val py = ay + vy * t
            val d = (px - x) * (px - x) + (py - y) * (py - y)
            if (d < bestD) {
                bestD = d
                val start = along[j]
                val segment = hypot(vx, vy)
                best = if (k == 0) start + segment * t else start + (along[k] - start) * t
            }
        }
        return (best / length).mod(1f)
    }
}

/** One car's positions over the session, in archive coordinates (1/10 m). */
class DriverTrack(
    internal val t: LongArray,
    internal val x: FloatArray,
    internal val y: FloatArray,
    internal val n: Int,
) {
    val startMs get() = if (n > 0) t[0] else 0L
    val endMs get() = if (n > 0) t[n - 1] else 0L

    /** Shared exact interval lookup; readiness checks never allocate marker coordinates. */
    private fun validBracketStart(utcMs: Long): Int {
        if (n == 0 || utcMs < t[0] || utcMs > t[n - 1]) return -1
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= utcMs) lo = mid else hi = mid
        }
        return if (t[hi] - t[lo] > MAX_GAP_MS) -1 else lo
    }

    /** True exactly when at(time) can render a marker, without allocating a Pair. */
    fun hasPositionAt(utcMs: Long): Boolean = validBracketStart(utcMs) >= 0

    /** Position at [utcMs], interpolated between samples. Null outside the data or across gaps. */
    fun at(utcMs: Long): Pair<Float, Float>? {
        val lo = validBracketStart(utcMs)
        if (lo < 0) return null
        val hi = if (lo + 1 < n) lo + 1 else lo
        val span = t[hi] - t[lo]
        val f = if (span == 0L) 0f else (utcMs - t[lo]).toFloat() / span
        return Pair(x[lo] + (x[hi] - x[lo]) * f, y[lo] + (y[hi] - y[lo]) * f)
    }

    /** As [at], but a car not heard from yet waits where it was first seen. For showing on the map. */
    fun atOrFirst(utcMs: Long): Pair<Float, Float>? =
        if (n > 0 && utcMs < t[0]) Pair(x[0], y[0]) else at(utcMs)

    companion object {
        private const val MAX_GAP_MS = 5_000L
    }
}

/** A driver's lap states as they changed through the session. */
class StateLog(internal val t: LongArray, internal val s: Array<LapState>) {
    fun at(utcMs: Long): LapState = s.getOrNull(t.floorIndex(utcMs)) ?: LapState.UNKNOWN
    internal fun shiftedBy(offsetMs: Long) = StateLog(t.shiftedBy(offsetMs), s)
}

/** A car's speed and gear through a session, from F1 CarData (speed in km/h). */
class SpeedLog(
    private val t: LongArray,
    private val v: ShortArray,
    private val n: Int,
    private val gears: ShortArray = ShortArray(0),
    private val gearTimes: LongArray? = null,
    private val gearCount: Int = gears.size,
) {
    private fun indexAt(utcMs: Long, times: LongArray, count: Int): Int {
        if (count == 0 || utcMs < times[0]) return -1
        var lo = 0
        var hi = count
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= utcMs) lo = mid else hi = mid
        }
        return if (utcMs - times[lo] > STALE_MS) -1 else lo
    }

    internal val speedTimes: LongArray get() = t
    internal val speedCount: Int get() = n
    internal val gearTimeSeries: LongArray get() = gearTimes ?: t
    internal val validGearCount: Int get() = if (gearTimes == null) minOf(n, gears.size) else gearCount
    internal fun kmhAtSnapshot(index: Int, now: Long): Int? =
        if (index !in 0 until n || now - t[index] > STALE_MS) null else v[index].toInt()
    internal fun gearAtSnapshot(index: Int, now: Long): Int? =
        if (index !in 0 until validGearCount || now - gearTimeSeries[index] > STALE_MS ||
            gears[index].toInt() < 0) null else gears[index].toInt()

    /** The latest speed at [utcMs], null before the first reading or when it goes stale. */
    fun kmhAt(utcMs: Long): Int? = kmhAtSnapshot(indexAt(utcMs, t, n), utcMs)

    /** F1 car-data channel 3: 0 is neutral, 1–8 are gears. */
    fun gearAt(utcMs: Long): Int? {
        val times = gearTimes ?: t
        val count = if (gearTimes == null) minOf(n, gears.size) else gearCount
        val index = indexAt(utcMs, times, count)
        return if (index < 0 || gears[index].toInt() < 0) null else gears[index].toInt()
    }

    private companion object {
        const val STALE_MS = 5_000L
    }
}

class SpeedBuilder {
    private var t = LongArray(4096)
    private var v = ShortArray(4096)
    private var gearTimes = LongArray(4096)
    private var gears = ShortArray(4096)
    private var size = 0
    private var gearSize = 0

    fun add(time: Long, kmh: Int?, gear: Int? = null) {
        if (kmh != null && (size == 0 || time > t[size - 1])) {
            if (size == t.size) {
                t = t.copyOf(size * 2)
                v = v.copyOf(size * 2)
            }
            t[size] = time
            v[size++] = kmh.toShort()
        }
        if (gear != null && (gearSize == 0 || time > gearTimes[gearSize - 1])) {
            if (gearSize == gearTimes.size) {
                gearTimes = gearTimes.copyOf(gearSize * 2)
                gears = gears.copyOf(gearSize * 2)
            }
            gearTimes[gearSize] = time
            gears[gearSize++] = gear.toShort()
        }
    }

    fun build() = SpeedLog(t, v, size, gears, gearTimes, gearSize)
}

/** A speed in the unit chosen in Settings. */
internal fun speedIn(kmh: Int, unit: String) = if (unit == Settings.KMH) kmh else (kmh * 0.621371).roundToInt()

class SessionTiming(
    val type: String,
    val drivers: Map<String, DriverInfo>,
    val tracks: Map<String, DriverTrack>,
    val states: Map<String, StateLog>,
    /** The circuit as x0,y0,x1,y1,... traced from one clean lap, starting at the line when known. */
    val outline: FloatArray,
    /** True when [outline] starts at the start/finish line. */
    val hasStartFinish: Boolean,
    val corners: List<Corner>,
    val startUtcMs: Long,
    val circuitKey: Int? = null,
    /**
     * When the session got going (UTC ms): lights out for a race, the first green otherwise.
     * Null when the archive doesn't say.
     */
    val startedUtc: Long? = null,
    /** The (last) chequered flag, UTC ms. */
    val finishedUtc: Long? = null,
    /** Each driver's sectors and lap times through the session, for the onboard timing card. */
    val laps: Map<String, DriverLaps> = emptyMap(),
    /** Team radio clips, oldest first. */
    val radio: List<RadioClip> = emptyList(),
    /** Each driver's tyres, for the timing tower. */
    val tyres: Map<String, TyreLog> = emptyMap(),
    /** Where the session's files live; a clip's address is this plus its path. Public, no login. */
    val radioBase: String = "",
    /** Where each marshal sector starts, for yellow flags in one sector. */
    val marshalSectors: List<Corner> = emptyList(),
    val flags: TrackFlags = TrackFlags.NONE,
    /** "Race", "Sprint", "Qualifying"...: [type] says "Race" for a sprint too. */
    val name: String = type,
    /** Live only: the leader's lap and the race distance, from F1's lap counter. */
    val currentLap: Int? = null,
    val totalLaps: Int? = null,
    /** The session's countdown, for sessions that run on time. */
    val clock: SessionClock = SessionClock.NONE,
    /** Live: the feed brings the cars' speeds. A replay's come from the archive when first wanted. */
    val live: Boolean = false,
    /** Session weather sampled from F1's WeatherData topic/archive. */
    val weather: WeatherLog = WeatherLog.NONE,
) {
    val geometry = TrackGeometry(outline)
    val firstTrackUtc: Long? = tracks.values.minOfOrNull { it.startMs }
    val lastTrackUtc: Long? = tracks.values.maxOfOrNull { it.endMs }
    var archivePartial = false


    /** Each car's speed, by racing number. */
    var speeds by mutableStateOf<Map<String, SpeedLog>>(emptyMap())

    /**
     * Where the race starts, as the fraction of a lap after the finish line (the outline's first
     * point). Found from a race's grid; for other sessions it comes from a race seen earlier at
     * the same circuit, or stays null.
     */
    var startLine: Float? = null

    /**
     * Live: the earliest moment there are car positions for, which is when Apexview joined live
     * timing, unless it kept a recording from earlier or F1 has since published the archive.
     */
    var positionsFromUtc: Long? = null

    /** Live, and the picture is from before there are positions: nothing to show the cars from. */
    fun beforePositions(now: Long): Boolean = live && positionsFromUtc?.let { now < it - POSITIONS_SLACK_MS } == true

    val isRace get() = type.contains("Race", ignoreCase = true)

    /** The Grand Prix itself: Driver of the Day is voted on this one, not the sprint. */
    val isGrandPrix get() = isRace && !name.contains("Sprint", ignoreCase = true)

    /** Flying-lap highlights only mean something in qualifying. */
    val showsPushLaps get() = type.contains("Qualifying", ignoreCase = true)
}

/** Weather published by F1's WeatherData topic, sampled against the session clock. */
class WeatherReading(
    val windSpeedMps: Double?,
    val windDirectionDegrees: Double?,
    val trackTempC: Double?,
    val airTempC: Double?,
    val humidityPct: Double?,
    val pressureHpa: Double?,
    val raining: Boolean?,
)

class WeatherLog(internal val times: LongArray, private val readings: List<WeatherReading>) {
    internal fun atSnapshot(index: Int): WeatherReading? = readings.getOrNull(index)
    fun at(utcMs: Long): WeatherReading? {
        if (readings.isEmpty() || utcMs < times.first()) return null
        val index = times.floorIndex(utcMs)
        return index.takeIf { it >= 0 }?.let(readings::get)
    }

    companion object {
        val NONE = WeatherLog(LongArray(0), emptyList())
    }
}

/** Parses F1's timestamped WeatherData snapshots, including the archived jsonStream format. */
object WeatherParser {
    fun parse(stream: String, streamStart: Long): WeatherLog {
        val times = mutableListOf<Long>()
        val readings = mutableListOf<WeatherReading>()
        stream.lineSequence().forEach { line ->
            val prefix = streamPrefixLength(line)
            if (line.length <= prefix) return@forEach
            val json = runCatching { JSONObject(line.substring(prefix)) }.getOrNull() ?: return@forEach
            val rain = json.optString("Rainfall").let {
                when (it.trim().lowercase()) {
                    "1", "true", "yes" -> true
                    "0", "false", "no" -> false
                    else -> null
                }
            }
            val reading = WeatherReading(
                windSpeedMps = json.optString("WindSpeed").toDoubleOrNull(),
                windDirectionDegrees = json.optString("WindDirection").toDoubleOrNull(),
                trackTempC = json.optString("TrackTemp").toDoubleOrNull(),
                airTempC = json.optString("AirTemp").toDoubleOrNull(),
                humidityPct = json.optString("Humidity").toDoubleOrNull(),
                pressureHpa = json.optString("Pressure").toDoubleOrNull(),
                raining = rain,
            )
            val utc = runCatching { streamStart + streamOffsetMs(line) }.getOrNull() ?: return@forEach
            if (times.isNotEmpty() && utc < times.last()) return@forEach
            if (times.isNotEmpty() && utc == times.last()) {
                readings[readings.lastIndex] = reading
            } else {
                times += utc
                readings += reading
            }
        }
        return WeatherLog(times.toLongArray(), readings)
    }
}

/** The countdown at one moment: what's left, whether it's running, and the part of qualifying. */
class ClockReading(val remainingMs: Long, val running: Boolean, val part: Int?)

/**
 * F1's session clock. It only says what the clock read at a moment and whether it was running from
 * there; red flags stop it, and each part of qualifying sets it again. Read at the picture's time,
 * it matches what's on screen rather than the timing that runs ahead of it.
 */
class SessionClock(private val marks: List<Mark>, private val parts: List<Pair<Long, Int>> = emptyList()) {
    class Mark(val utc: Long, val remainingMs: Long, val running: Boolean)

    fun at(utc: Long): ClockReading? {
        if (marks.isEmpty()) return null
        // Before the first mark (a live picture behind it), it's run back from that mark.
        val mark = marks.lastOrNull { it.utc <= utc } ?: marks.first()
        val left = if (mark.running) mark.remainingMs - (utc - mark.utc) else mark.remainingMs
        return ClockReading(left.coerceAtLeast(0), mark.running, (parts.lastOrNull { it.first <= utc } ?: parts.firstOrNull())?.second)
    }

    companion object {
        val NONE = SessionClock(emptyList())
    }
}

/** Lap states per driver, plus every moment a car crossed the line (to locate it). */
class StateParse(val states: Map<String, StateLog>, val crossings: List<Pair<String, Long>>)

/** Keeps the tower and lap-state histories from one JSON parse of the large TimingData stream. */
internal class StateHistoryAccumulator {
    private val crossings = mutableListOf<Pair<String, Long>>()
    private val times = mutableMapOf<String, MutableList<Long>>()
    private val values = mutableMapOf<String, MutableList<LapState>>()
    private val current = mutableMapOf<String, LapState>()
    private val laps = mutableMapOf<String, Int>()

    fun accept(lines: JSONObject, time: Long) {
        lines.keys().forEach { number ->
            val driver = lines.optJSONObject(number) ?: return@forEach
            if (!driver.has("InPit") && !driver.has("PitOut") && !driver.has("NumberOfLaps") && !driver.has("KnockedOut")) return@forEach
            var state = current[number] ?: LapState.UNKNOWN
            if (driver.optBoolean("InPit")) state = LapState.PIT
            if (driver.optBoolean("PitOut") || (driver.has("InPit") && !driver.optBoolean("InPit") && state == LapState.PIT)) {
                state = LapState.OUT_LAP
            }
            if (driver.has("NumberOfLaps")) {
                val count = driver.optInt("NumberOfLaps", -1)
                val previous = laps[number]
                if (count >= (previous ?: 0)) laps[number] = count
                if (previous != null && count > previous) {
                    crossings += number to time
                    state = when (state) {
                        LapState.OUT_LAP, LapState.COOL_DOWN -> LapState.PUSH
                        LapState.PUSH -> LapState.COOL_DOWN
                        else -> state
                    }
                }
            }
            if (driver.optBoolean("KnockedOut")) state = LapState.KNOCKED_OUT
            if (state != current[number]) {
                current[number] = state
                times.getOrPut(number) { mutableListOf() }.add(time)
                values.getOrPut(number) { mutableListOf() }.add(state)
            }
        }
    }

    fun finish() = StateParse(
        states = times.mapValues { (number, t) -> StateLog(t.toLongArray(), values.getValue(number).toTypedArray()) },
        crossings = crossings.toList(),
    )
}

internal data class DriverTimingParse(val laps: Map<String, DriverLaps>, val states: StateParse)
internal data class DriverTimingPreview(val throughUtcMs: Long, val history: DriverTimingParse)
internal const val TOWER_PREVIEW_LOOKAHEAD_MS = 60_000L

/**
 * Reads a past session from F1's live timing archive (livetiming.formula1.com/static). The archive
 * now needs the F1 TV subscription token as a Bearer header, the way FastF1 sends it.
 * File formats follow FastF1's parser: jsonStream lines are "HH:MM:SS.mmm" + JSON, and the ".z"
 * streams hold base64 raw-deflate JSON.
 */
/**
 * A jsonStream line's prefix: its offset from the stream's start, "HH:MM:SS.mmm", with "d." in
 * front once F1's recorder has been running more than a day (it can run across a weekend; the
 * 2026 Mexico race's radio was all "1.02:32:50.914"). Read as a fixed 12 characters, every line
 * from that point on was dropped, and the radio stopped there.
 */

/** How many characters the prefix takes, so the payload starts there; the whole line if it has none. */
/** A picture this close before the first positions still shows the cars where they first appear. */
internal const val POSITIONS_SLACK_MS = 5_000L

/** A jsonStream line: the offset from the stream's start as "HH:MM:SS.mmm", then the JSON. */
internal fun streamLine(offsetMs: Long, json: String): String =
    "%02d:%02d:%02d.%03d".format(offsetMs / 3_600_000, offsetMs / 60_000 % 60, offsetMs / 1000 % 60, offsetMs % 1000) + json + '\n'

/**
 * An archive stream's lines from before [untilUtc], re-counted from [newStart] instead of the
 * archive's own start [archiveStart], to go in front of a live session's.
 */
internal fun rebaseStream(stream: String, archiveStart: Long, newStart: Long, untilUtc: Long): String {
    val out = StringBuilder()
    for (line in stream.lineSequence()) {
        val prefix = streamPrefixLength(line)
        if (line.length <= prefix) continue
        val utc = archiveStart + streamOffsetMs(line)
        if (utc >= untilUtc) break
        if (utc < newStart) continue
        out.append(streamLine(utc - newStart, line.substring(prefix)))
    }
    return out.toString()
}

// The archive prefixes are fixed ASCII fields. Avoid creating native regex matchers per record.
private fun archivePrefixEnd(line: String, from: Int = 0, until: Int = line.length): Int {
    val start = if (from < until && line[from] == '\uFEFF') from + 1 else from
    var hour = start
    while (hour < until && line[hour] in '0'..'9') hour++
    if (hour < until && line[hour] == '.') hour++ else hour = start
    val end = hour + 12
    if (end > until) return -1
    for (i in 0 until 12) {
        val ch = line[hour + i]
        if (when (i) { 2, 5 -> ch != ':'; 8 -> ch != '.'; else -> ch !in '0'..'9' }) return -1
    }
    return end
}

internal fun streamPrefixLength(line: String): Int = archivePrefixEnd(line).takeIf { it >= 0 } ?: line.length

/** The line's offset from the stream's start, in ms. */
internal fun streamOffsetMs(line: String): Long {
    val end = archivePrefixEnd(line)
    require(end >= 0) { "no stream offset" }
    return streamOffsetMs(line, end)
}

internal fun streamOffsetMs(line: String, end: Int): Long = streamOffsetMs(line, 0, end)

private fun streamOffsetMs(line: String, from: Int, end: Int): Long {
    val hour = end - 12
    val start = if (line[from] == '\uFEFF') from + 1 else from
    var days = 0L
    for (i in start until hour - 1) days = days * 10 + (line[i] - '0')
    fun pair(i: Int) = (line[i] - '0') * 10 + (line[i + 1] - '0')
    return (((days * 24 + pair(hour)) * 60 + pair(hour + 3)) * 60 + pair(hour + 6)) * 1000 +
        pair(end - 3) * 10 + (line[end - 1] - '0')
}

/** Check ordering without allocating a substring for every line of the archive. */
internal fun timingStreamIsChronological(text: String, checkActive: () -> Unit): Boolean {
    var start = 0
    var previous = Long.MIN_VALUE
    var records = 0
    while (start < text.length) {
        if (records++ % 256 == 0) checkActive()
        var end = start
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        val prefix = archivePrefixEnd(text, start, end)
        if (prefix in 0 until end) {
            val offset = streamOffsetMs(text, start, prefix)
            if (offset < previous) return false
            previous = offset
        }
        start = end + 1
    }
    return true
}

/** Main-looper publication order for independently parsed track and timing previews. */
internal class TimingPreviewEpochs {
    private var latest = 0L
    @Synchronized fun next(): Long = ++latest
    @Synchronized fun isCurrent(epoch: Long): Boolean = epoch == latest
}

private data class TimingPreviewSnapshot(
    val epoch: Long,
    val tracks: Map<String, DriverTrack>,
    val outline: FloatArray,
    val states: Map<String, StateLog>,
    val laps: Map<String, DriverLaps>,
    val startUtcMs: Long,
)

class LiveTimingArchive(private val token: String, private val userAgent: String) {

    /** [watchedUtc]: when the video on screen was broadcast, if known; finds the session by time. */
    internal suspend fun load(
        ref: SessionRef,
        watchedUtc: Long? = null,
        replayPositionMs: Long = 0,
        positionScratchDir: java.io.File? = null,
        replayCache: ReplayCoreCache? = null,
        timingOffsetMs: Long = 0,
        playhead: () -> ReplayPlayhead? = { null },
        traceId: Long = 0,
        onProgress: suspend (String) -> Unit = {},
        onPartial: suspend (SessionTiming) -> Unit = {},
    ): SessionTiming = withContext(Dispatchers.IO) {
        suspend fun progress(status: String) {
            withContext(Dispatchers.Main) { onProgress(status) }
        }
        suspend fun publish(session: SessionTiming) {
            withContext(Dispatchers.Main) { onPartial(session) }
        }
        suspend fun fetchStage(path: String, label: String = path.substringAfterLast('/')): String {
            progress("GET $label…")
            val started = android.os.SystemClock.elapsedRealtime()
            return try {
                val body = fetch(path)
                progress("OK $label · ${body.length} chars · ${android.os.SystemClock.elapsedRealtime() - started}ms")
                body
            } catch (e: Exception) {
                progress("FAIL $label · ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                throw e
            }
        }
        suspend fun fetchOptionalStage(path: String, label: String = path.substringAfterLast('/')): String? =
            try {
                fetchStage(path, label)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        val availableCircuit = java.util.concurrent.atomic.AtomicReference<Circuit?>()
        val circuitTask = async {
            ref.circuitKey?.let { runCatching { fetchCircuit(it, ref.year) }.getOrNull() }
                .also(availableCircuit::set)
        }
        fun targetUtc(startUtc: Long) =
            (playhead() ?: ReplayPlayhead(replayPositionMs, watchedUtc, timingOffsetMs)).utcAt(startUtc)
        suspend fun archiveSession(): Pair<String, String> {
            val cachedIndex = positionScratchDir?.let { java.io.File(it, "timing-season-${ref.year}.json") }
            if (ref.sessionKey != null && cachedIndex != null &&
                cachedIndex.length() in 1..MAX_SEASON_INDEX_BYTES &&
                System.currentTimeMillis() - cachedIndex.lastModified() in 0..SEASON_INDEX_MAX_AGE_MS
            ) {
                val cached = runCatching {
                    exactArchiveSession(ref.sessionKey, JSONObject(cachedIndex.readText()))
                }.getOrNull()
                if (cached != null) {
                    progress("Timing season index hit · ${ref.year}")
                    return cached
                }
            }
            val raw = fetchStage("${ref.year}/Index.json")
            val index = JSONObject(raw)
            val session = findSession(ref, watchedUtc, index)
            if (cachedIndex != null && raw.length <= MAX_SEASON_INDEX_BYTES) runCatching {
                val temporary = java.io.File.createTempFile("timing-season-", ".tmp", positionScratchDir)
                try {
                    temporary.writeText(raw)
                    temporary.renameTo(cachedIndex)
                } finally {
                    temporary.delete()
                }
            }
            return session
        }
        suspend fun loadCore(): ReplayCore = coroutineScope {
            val (path, type) = archiveSession()
            progress("Archive session resolved: $path · type=$type")
            val driverTask = async { parseDrivers(JSONObject(fetchStage(path + "DriverList.json"))) }
            val heartbeatTask = async { parseStreamStart(fetchStage(path + "Heartbeat.jsonStream")) }
            val positionClock = CompletableDeferred<Long?>()
            var partialClock: Long? = null
            var partialTracks: Map<String, DriverTrack> = emptyMap()
            var partialOutline = FloatArray(0)
            var partialHistory = DriverTimingParse(emptyMap(), StateParse(emptyMap(), emptyList()))
            fun checkpoint(stage: String, detail: String) {
                val heap = Runtime.getRuntime()
                android.util.Log.i("ApexviewTiming", "trace=$traceId archive=$path stage=$stage $detail " +
                    "heapUsedMiB=${(heap.totalMemory() - heap.freeMemory()) / 1048576} " +
                    "heapMaxMiB=${heap.maxMemory() / 1048576}")
            }
            suspend fun <T> traceStage(stage: String, block: suspend ((String) -> Unit) -> T): T = coroutineScope {
                val started = android.os.SystemClock.elapsedRealtime()
                val last = java.util.concurrent.atomic.AtomicReference("starting")
                val report: (String) -> Unit = { detail ->
                    last.set(detail)
                    checkpoint(stage, detail)
                }
                val heartbeatJob = launch(Dispatchers.IO) {
                    while (true) {
                        delay(10_000)
                        val seconds = (android.os.SystemClock.elapsedRealtime() - started) / 1000
                        checkpoint(stage, "still running after ${seconds}s · last=${last.get()}")
                    }
                }
                report("begin")
                try {
                    block(report).also { report("complete elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}") }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    report("cancelled")
                    throw e
                } catch (e: Exception) {
                    report("failed type=${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull()}")
                    throw e
                } finally {
                    heartbeatJob.cancel()
                }
            }
            // Map/track geometry index initialization must never block TV's UI
            // looper. Drop old previews when positions and tower arrive out of order.
            val previewEpochs = TimingPreviewEpochs()
            // Position parser can enqueue several partial updates while the
            // previous Compose/RenderNode publication is suspended. Latest
            // complete preview wins; an older map cannot replace a newer one.
            val positionPreviewEpochs = TimingPreviewEpochs()
            suspend fun publishTopics() {
                val drivers = driverTask.await()
                val initialStart = heartbeatTask.await()
                val snapshot = withContext(Dispatchers.Main.immediate) {
                    TimingPreviewSnapshot(
                        epoch = previewEpochs.next(),
                        tracks = partialTracks,
                        outline = partialOutline,
                        states = partialHistory.states.states,
                        laps = partialHistory.laps,
                        startUtcMs = partialClock ?: initialStart,
                    )
                }
                checkpoint("publish-overlays",
                    "towerDrivers=${snapshot.laps.size} cars=${snapshot.tracks.size} outlinePoints=${snapshot.outline.size / 2}")
                val preview = withContext(Dispatchers.Default) {
                    SessionTiming(
                        type = type, drivers = drivers, tracks = snapshot.tracks,
                        states = snapshot.states, outline = snapshot.outline,
                        hasStartFinish = false, corners = emptyList(), startUtcMs = snapshot.startUtcMs,
                        circuitKey = ref.circuitKey, laps = snapshot.laps, radioBase = BASE + path,
                    ).apply { archivePartial = true }
                }
                withContext(Dispatchers.Main.immediate) {
                    if (previewEpochs.isCurrent(snapshot.epoch)) {
                        onPartial(preview)
                    } else {
                        checkpoint("publish-overlays", "dropped outdated preview epoch=${snapshot.epoch}")
                    }
                }
            }
            val positionsTask = async(Dispatchers.IO) {
                progress("Streaming and parsing Position.z.jsonStream…")
                val job = coroutineContext
                try {
                    traceStage("map-position-stream") { report ->
                        report("opening HTTP stream")
                        val topic = path + "Position.z.jsonStream"
                        suspend fun publishTracks(snapshot: Map<String, DriverTrack>, outline: FloatArray,
                                                  start: Long, previewEpoch: Long) {
                            withContext(Dispatchers.Main.immediate) {
                                if (!positionPreviewEpochs.isCurrent(previewEpoch)) {
                                    checkpoint("map-position-preview", "discarded stale preview epoch=$previewEpoch")
                                    return@withContext
                                }
                                val target = targetUtc(start)
                                val covered = partialTracks.values.any { it.hasPositionAt(target) }
                                if (!covered || snapshot.values.any { it.hasPositionAt(target) }) {
                                    partialTracks = snapshot
                                    partialOutline = outline
                                    publishTopics()
                                }
                            }
                        }
                        open(topic).bufferedReader().use { reader ->
                            val heartbeatStart = heartbeatTask.await()
                            val firstLines = reader.lineSequence().take(100).onEach { job.ensureActive() }.toList()
                            val positionStart = positionsStreamStart(firstLines.joinToString("\n"))
                            positionClock.complete(positionStart)
                            val initialStart = positionStart
                                ?.takeIf { abs(it - heartbeatStart) > STREAM_START_SLACK_MS } ?: heartbeatStart
                            fun parseFull(lines: Sequence<String>): PositionParseReport {
                                var lastPreviewAt = -1_000L
                                var previewOutline = FloatArray(0)
                                var lastTraceAt = -1_000L
                                var publishedThrough: Long? = null
                                val thread = android.os.Process.myTid()
                                val previousPriority = android.os.Process.getThreadPriority(thread)
                                try {
                                    android.os.Process.setThreadPriority(thread, android.os.Process.THREAD_PRIORITY_BACKGROUND)
                                    return parsePositionLines(lines, { job.ensureActive() }, report) { builders ->
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        val target = targetUtc(initialStart)
                                        if (now - lastPreviewAt < 400 || publishedThrough?.let { target <= it - 10_000 } == true) {
                                            return@parsePositionLines
                                        }
                                        val snapshot = builders.mapValues { it.value.build() }
                                        val covering = snapshot.values.filter { it.hasPositionAt(target) }
                                        if (covering.isEmpty()) return@parsePositionLines
                                        if (previewOutline.isEmpty()) {
                                            previewOutline = availableCircuit.get()?.outline ?: FloatArray(0)
                                            if (previewOutline.isEmpty() && now - lastTraceAt >= 750) {
                                                previewOutline = traceOutline(builders.values.toList())
                                                lastTraceAt = now
                                            }
                                        }
                                        if (previewOutline.size < 8) return@parsePositionLines
                                        lastPreviewAt = now
                                        publishedThrough = covering.maxOf { it.endMs }
                                        val outline = previewOutline
                                        report("position preview targetUtcMs=$target cars=${covering.size} throughUtcMs=$publishedThrough")
                                        val previewEpoch = positionPreviewEpochs.next()
                                        launch { publishTracks(snapshot, outline, initialStart, previewEpoch) }
                                    }
                                } finally {
                                    android.os.Process.setThreadPriority(thread, previousPriority)
                                }
                            }
                            val offset = targetUtc(initialStart) - initialStart
                            if (positionScratchDir == null || offset < 180_000L) {
                                report("single-pass position decode offsetMs=$offset")
                                return@traceStage parseFull(firstLines.asSequence() + reader.lineSequence())
                            }
                            val spool = java.io.File.createTempFile("replay-positions-", ".stream", positionScratchDir)
                            try {
                                spool.bufferedWriter().use { writer ->
                                    var chars = 0L
                                    fun copy(line: String): String {
                                        job.ensureActive()
                                        chars += line.length + 1L
                                        if (chars > 64L * 1024 * 1024) throw IOException("Position archive exceeds 64 MiB")
                                        writer.write(line)
                                        writer.newLine()
                                        return line
                                    }
                                    val wanted = ReplayPositionWindow(targetUtc(initialStart) - initialStart)
                                    // Bound startup work to a few minutes of positions near the replay clock.
                                    // Copy the remaining data through to disk for seeks and full history.
                                    report("streaming position window targetMs=${wanted.targetMs}")
                                    val incoming = (firstLines.asSequence() + reader.lineSequence()).map(::copy)
                                    val records = incoming.iterator()
                                    val window = sequence {
                                        while (records.hasNext()) {
                                            val line = records.next()
                                            val prefix = streamPrefixLength(line)
                                            if (prefix >= line.length) continue
                                            val offset = streamOffsetMs(line, prefix)
                                            if (wanted.retarget(targetUtc(initialStart) - initialStart)) {
                                                report("retargeted position window offsetMs=$offset targetMs=${wanted.targetMs}")
                                            }
                                            if (offset > wanted.lastMs) break
                                            if (offset >= wanted.firstMs) yield(line)
                                        }
                                    }
                                    var previewSent = false
                                    var lastPreviewCheckMs = -1_000L
                                    val initial = parsePositionLines(window, { job.ensureActive() }) { builders ->
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (!previewSent && now - lastPreviewCheckMs >= 250) {
                                            lastPreviewCheckMs = now
                                            val currentUtc = targetUtc(initialStart)
                                            val snapshot = builders.mapValues { it.value.build() }
                                            val positioned = snapshot.values.count { it.hasPositionAt(currentUtc) }
                                            if (positioned > 0) {
                                                val remoteShape = availableCircuit.get()?.outline
                                                val shape = remoteShape?.takeIf { it.size >= 8 }
                                                    ?: traceOutline(builders.values.toList())
                                                if (shape.size >= 8) {
                                                    previewSent = true
                                                    report("position window ready before lookahead targetUtcMs=$currentUtc cars=$positioned")
                                                    val previewEpoch = positionPreviewEpochs.next()
                                                    launch { publishTracks(snapshot, shape, initialStart, previewEpoch) }
                                                }
                                            }
                                        }
                                    }
                                    val traced = traceOutline(initial.tracks.values.toList())
                                    val outline = when {
                                        traced.isNotEmpty() -> traced
                                        circuitTask.isCompleted -> circuitTask.await()?.outline ?: traced
                                        else -> traced
                                    }
                                    if (outline.size >= 8 && initial.tracks.isNotEmpty()) {
                                        val previewEpoch = positionPreviewEpochs.next()
                                        publishTracks(initial.tracks.mapValues { it.value.build() }, outline,
                                            initialStart, previewEpoch)
                                    }
                                    report("position window available cars=${initial.tracks.size} outlinePoints=${outline.size / 2}")
                                    while (records.hasNext()) records.next()
                                }
                                spool.bufferedReader().useLines { parseFull(it) }
                            } finally {
                                spool.delete()
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    progress("PARSE FAIL positions · ${e.javaClass.simpleName}: ${e.message}")
                    PositionParseReport(emptyMap(), 0, 0, 0, 0)
                } finally {
                    positionClock.complete(null)
                }
            }
            val historyTask = async(Dispatchers.IO) {
                val primaryPreview = CompletableDeferred<Unit>()
                val heartbeatStart = heartbeatTask.await()
                val initialStart = withTimeoutOrNull(4_000L) { positionClock.await() }
                    ?.takeIf { abs(it - heartbeatStart) > STREAM_START_SLACK_MS } ?: heartbeatStart
                if (initialStart != heartbeatStart) {
                    checkpoint("tower-clock", "position clock correctionMs=${initialStart - heartbeatStart}")
                }
                val timingTask = async {
                    val topic = path + "TimingData.jsonStream"
                    val started = android.os.SystemClock.elapsedRealtime()
                    val job = coroutineContext
                    checkpoint("tower-download", "opening HTTP stream")
                    try {
                        open(topic).bufferedReader().use { reader ->
                            val start = initialStart
                            val text = StringBuilder()
                            val lines = reader.lineSequence().map { line ->
                                job.ensureActive()
                                text.appendLine(line)
                                line
                            }.iterator()
                            fun previewTarget(): Long {
                                val clock = playhead()
                                // A resumed race can start far from the heartbeat estimate.
                                if (clock != null && clock.positionMs >= 180_000L && clock.videoUtcMs == null &&
                                    android.os.SystemClock.elapsedRealtime() - started < 3_000L
                                ) return Long.MAX_VALUE
                                return targetUtc(start)
                            }
                            for (preview in DriverTimingParser.previews(lines, start, ::previewTarget, { job.ensureActive() })) {
                                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                                checkpoint("tower-preview", "drivers=${preview.history.laps.size} throughUtcMs=${preview.throughUtcMs} elapsedMs=$elapsed")
                                withContext(Dispatchers.Main) {
                                    partialClock = initialStart
                                    partialHistory = preview.history
                                    publishTopics()
                                }
                                primaryPreview.complete(Unit)
                                val clock = playhead()
                                val videoClockReady = clock != null && clock.videoUtcMs != null && !clock.cachedVideoClock
                                if ((videoClockReady || elapsed >= 10_000L) &&
                                    preview.throughUtcMs - targetUtc(start) >= TOWER_PREVIEW_LOOKAHEAD_MS
                                ) break
                            }
                            primaryPreview.complete(Unit)
                            while (lines.hasNext()) lines.next()
                            progress("OK TimingData.jsonStream · ${text.length} chars · ${android.os.SystemClock.elapsedRealtime() - started}ms")
                            text.toString()
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        primaryPreview.complete(Unit)
                        progress("FAIL TimingData.jsonStream · ${error.javaClass.simpleName}: ${error.message}")
                        null
                    }
                }
                val timingF1Task = async {
                    // Let the primary standings and position streams start before optional mini-sectors.
                    withTimeoutOrNull(1_500L) { primaryPreview.await() }
                    fetchOptionalStage(path + "TimingDataF1.jsonStream").orEmpty()
                }
                val timingStream = timingTask.await()
                val timingF1Stream = timingF1Task.await()
                val history = if (timingStream == null) {
                    progress("TimingData unavailable; timing tower history could not be loaded")
                    DriverTimingParse(emptyMap(), StateParse(emptyMap(), emptyList()))
                } else withContext(Dispatchers.Default) {
                    progress("PARSING timing tower and lap history · ${timingStream.length} chars")
                    val started = android.os.SystemClock.elapsedRealtime()
                    try {
                        val accumulator = StateHistoryAccumulator()
                        val laps = traceStage("tower-history") { report ->
                            val job = kotlin.coroutines.coroutineContext
                            var publishedTarget: Long? = null
                            val threadId = android.os.Process.myTid()
                            val priority = android.os.Process.getThreadPriority(threadId)
                            try {
                                android.os.Process.setThreadPriority(threadId, android.os.Process.THREAD_PRIORITY_BACKGROUND)
                                DriverTimingParser.parse(timingStream, initialStart, timingF1Stream, accumulator, report,
                                    checkActive = { job.ensureActive() }) { through, buildLaps ->
                                    val target = targetUtc(initialStart)
                                    if (through >= target && publishedTarget?.let { abs(target - it) < 30_000L } != true) {
                                        val laps = buildLaps()
                                        if (laps.values.any { it.at(target)?.position != null }) {
                                            publishedTarget = target
                                            val snapshot = DriverTimingParse(laps, accumulator.finish())
                                            launch(Dispatchers.Main) {
                                                partialClock = initialStart
                                                partialHistory = snapshot
                                                publishTopics()
                                            }
                                        }
                                    }
                                }
                            } finally {
                                android.os.Process.setThreadPriority(threadId, priority)
                            }
                        }
                        DriverTimingParse(laps, accumulator.finish()).also {
                            progress("PARSED timing history · towerDrivers=${it.laps.size} stateDrivers=${it.states.states.size} · ${android.os.SystemClock.elapsedRealtime() - started}ms")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        progress("PARSE FAIL timing history · ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                        DriverTimingParse(emptyMap(), StateParse(emptyMap(), emptyList()))
                    }
                }
                withContext(Dispatchers.Main) {
                    partialClock = initialStart
                    partialHistory = history
                    publishTopics()
                }
                Triple(timingStream, history, initialStart)
            }
            val drivers = driverTask.await()
            val initialStart = heartbeatTask.await()
            withContext(Dispatchers.Main) { publishTopics() }
            // Download and parse the map while the single bounded timing-history parser runs. The
            // tower and lap-state histories share each decoded JSON object, avoiding two concurrent
            // full passes over this multi-megabyte stream on lower-memory Android TV devices.
            progress("Loading track positions while timing history parses…")
            val positionParseStarted = android.os.SystemClock.elapsedRealtime()
            val positionReport = positionsTask.await()
            val tracks = positionReport.tracks
            progress(
                "PARSED positions · records=${positionReport.decodedRecords}/${positionReport.inputRecords}, " +
                    "samples=${positionReport.positionSamples}, cars=${tracks.size}, malformed=${positionReport.malformedRecords} · " +
                    "${android.os.SystemClock.elapsedRealtime() - positionParseStarted}ms"
            )
            if (tracks.isEmpty()) {
                progress("Track positions unavailable; continuing to load timing tower")
            }

            val geometryStarted = android.os.SystemClock.elapsedRealtime()
            progress("Building the track map while timing tower finishes · cars=${tracks.size}")
            val built = withContext(Dispatchers.Default) {
                traceStage("map-track-arrays") { _ -> tracks.mapValues { it.value.build() } }
            }
            val traced = withContext(Dispatchers.Default) {
                traceStage("map-outline") { _ -> traceOutline(tracks.values.toList()) }
            }
            withContext(Dispatchers.Main) {
                partialTracks = built
                if (traced.isNotEmpty()) partialOutline = traced
                publishTopics()
            }
            progress(if (tracks.isEmpty()) "Track positions unavailable; loading timing history…" else "Track map available · loading timing history…")

            val (timingStream, initialHistory, historyStart) = historyTask.await()
            // Heartbeat anchors the stream clock. Some sessions have bad offsets; the cars' own
            // timestamps win when they disagree with it by more than a minute.
            val streamStart = positionReport.streamStart?.takeIf { abs(it - initialStart) > STREAM_START_SLACK_MS } ?: initialStart
            val clockShift = streamStart - historyStart
            val history = if (clockShift == 0L) {
                initialHistory
            } else {
                progress("Timing clock corrected by ${clockShift}ms · shifting indexed timestamps")
                DriverTimingParse(
                    initialHistory.laps.mapValues { it.value.shiftedBy(clockShift) },
                    StateParse(
                        initialHistory.states.states.mapValues { it.value.shiftedBy(clockShift) },
                        initialHistory.states.crossings.map { (driver, time) -> driver to (time + clockShift) },
                    ),
                )
            }

            progress(
                "TRACK GEOMETRY · samples=${tracks.values.sumOf { it.size }}, outlinePoints=${traced.size / 2} · " +
                    "${android.os.SystemClock.elapsedRealtime() - geometryStarted}ms"
            )
            val line = traceStage("start-finish") { _ -> findStartFinish(traced, history.states.crossings, built) }
            val outline = when {
                line != null -> startAt(traced, line)
                traced.isNotEmpty() -> traced
                circuitTask.isCompleted -> circuitTask.await()?.outline ?: traced
                else -> traced
            }
            ReplayCore(
                path, type, drivers, built, history.states.states,
                outline, line != null, streamStart, history.laps,
                if (type.contains("Qualifying", ignoreCase = true)) {
                    traceStage("session-parts") { _ -> parseParts(timingStream.orEmpty(), streamStart) }
                } else emptyList(),
            )
        }

        val loadJob = kotlin.coroutines.coroutineContext
        fun ReplayCore.partial() = SessionTiming(
            type = type, drivers = drivers, tracks = tracks, states = states, outline = outline,
            hasStartFinish = hasStartFinish, corners = emptyList(), startUtcMs = startUtc,
            circuitKey = ref.circuitKey, laps = laps, radioBase = BASE + path,
        ).apply { archivePartial = true }
        val previewStarted = android.os.SystemClock.elapsedRealtime()
        replayCache?.readPreview(replayPositionMs) { loadJob.ensureActive() }?.let { preview ->
            publish(preview.partial())
            progress("Replay startup snapshot hit · ${android.os.SystemClock.elapsedRealtime() - previewStarted}ms")
        }
        val cacheStarted = android.os.SystemClock.elapsedRealtime()
        val cached = replayCache?.read { loadJob.ensureActive() }
        progress(
            "Replay index ${if (cached == null) "miss" else "hit"} · " +
                "${android.os.SystemClock.elapsedRealtime() - cacheStarted}ms"
        )
        val core = cached ?: loadCore()
        val path = core.path
        val type = core.type
        val drivers = core.drivers
        val built = core.tracks
        val streamStart = core.startUtc
        val laps = core.laps
        val states = core.states
        var outline = core.outline
        // Publish the traced map immediately. Optional external circuit lookup may provide a
        // better outline/corner labels, but it must not hold up a usable map or timing tower.
        publish(core.partial())

        if (cached == null && replayCache != null && core.outline.size >= 8 &&
            core.tracks.isNotEmpty() && core.laps.isNotEmpty()
        ) launch {
            val writeJob = kotlin.coroutines.coroutineContext
            val started = android.os.SystemClock.elapsedRealtime()
            val saved = replayCache.write(core) { writeJob.ensureActive() }
            progress("Replay index ${if (saved) "saved" else "write failed"} · ${android.os.SystemClock.elapsedRealtime() - started}ms")
        }

        val isRace = type.contains("Race", ignoreCase = true)
        val metadataIo = Dispatchers.IO.limitedParallelism(4)
        fun topic(name: String) = async(metadataIo) { fetchOptionalStage(path + name) }
        val weatherTask = topic("WeatherData.jsonStream")
        val statusTask = topic("SessionStatus.jsonStream")
        val controlTask = topic("RaceControlMessages.jsonStream")
        val sessionInfoTask = topic("SessionInfo.json")
        val flagsTask = topic("TrackStatus.jsonStream")
        val tyresTask = topic("TimingAppData.jsonStream")
        val radioTask = topic("TeamRadio.jsonStream")
        val lapCountTask = if (isRace) topic("LapCount.jsonStream") else null
        val clockTask = topic("ExtrapolatedClock.jsonStream")

        progress("Loading circuit outline and corner metadata…")
        val circuitStarted = android.os.SystemClock.elapsedRealtime()
        val circuit = circuitTask.await()
        progress("Circuit metadata ${if (circuit == null) "unavailable" else "loaded"} · ${android.os.SystemClock.elapsedRealtime() - circuitStarted}ms")
        val corners = circuit?.corners.orEmpty()
        if (outline.size < 8 && (circuit?.outline?.size ?: 0) >= 8) outline = circuit!!.outline
        if (circuit != null) {
            publish(
                SessionTiming(
                    type = type,
                    drivers = drivers,
                    tracks = built,
                    states = states,
                    outline = outline,
                    hasStartFinish = core.hasStartFinish,
                    corners = corners,
                    startUtcMs = streamStart,
                    circuitKey = ref.circuitKey,
                    laps = laps,
                    radioBase = BASE + path,
                ).apply { archivePartial = true }
            )
        }

        progress(
            if (outline.size >= 8) "Loading optional race details…"
            else "Timing tower data is loaded, but F1 has not provided a usable circuit outline for the map…"
        )
        val weather = weatherTask.await()?.let {
            runCatching { WeatherParser.parse(it, streamStart) }.getOrNull()
        } ?: WeatherLog.NONE
        val started = statusTask.await()?.let {
            runCatching { parseSessionStarted(it, streamStart) }.getOrNull()
        }
        val control = controlTask.await()
        val chequered = control?.let { runCatching { parseChequered(it, streamStart) }.getOrNull() }
        // Restart times come in the track's local time; the session's offset from GMT turns them to UTC.
        val gmtOffset = sessionInfoTask.await()?.let {
            runCatching { JSONObject(it).optString("GmtOffset") }.getOrNull()
        }
        val flags = TrackFlags(
            control?.let { runCatching { parseSectorFlags(it, streamStart) }.getOrNull() }.orEmpty(),
            flagsTask.await()?.let {
                runCatching { parseTrackStatus(it, streamStart) }.getOrDefault(emptyList())
            }.orEmpty(),
            control?.let { runCatching { parseSafetyCarIn(it, streamStart) }.getOrNull() }.orEmpty(),
            control?.let { c -> gmtOffset?.let { runCatching { parseResumptions(c, streamStart, it) }.getOrNull() } }.orEmpty(),
            control?.let { runCatching { parseRedFlags(it, streamStart) }.getOrNull() }.orEmpty(),
        )
        val tyres = tyresTask.await()?.let {
            runCatching { TyreParser.parse(it, streamStart) }.getOrNull()
        }.orEmpty()
        val radio = radioTask.await()?.let {
            runCatching { parseRadio(it) }.getOrDefault(emptyList())
        }.orEmpty()
        android.util.Log.i("ApexviewRadio", "archive radio for $path: ${radio.size} clips")
        val totalLaps = lapCountTask?.await()?.let { runCatching { parseTotalLaps(it) }.getOrNull() }
        val clock = clockTask.await()?.let { runCatching { parseClock(it, streamStart) }.getOrNull() }.orEmpty()
        val grid = if (isRace && core.hasStartFinish) findGrid(TrackGeometry(outline), built, chequered) else null

        SessionTiming(
            type = type,
            drivers = drivers,
            tracks = built,
            states = states,
            outline = outline,
            hasStartFinish = core.hasStartFinish,
            corners = corners,
            startUtcMs = streamStart,
            circuitKey = ref.circuitKey,
            startedUtc = grid?.lightsOutUtc ?: started,
            finishedUtc = chequered,
            laps = laps,
            radio = radio,
            radioBase = BASE + path,
            tyres = tyres,
            marshalSectors = circuit?.sectors.orEmpty(),
            flags = flags,
            totalLaps = totalLaps,
            clock = SessionClock(clock, core.parts),
            weather = weather,
        ).also { it.startLine = grid?.startLine }
    }

    /** LapCount.jsonStream: the race distance, as last given (it can change when a race is shortened). */
    internal fun parseTotalLaps(stream: String): Int? =
        stream.lineSequence().mapNotNull { line ->
            if (line.length <= streamPrefixLength(line)) null
            else runCatching { JSONObject(line.substring(streamPrefixLength(line))) }.getOrNull()?.takeIf { it.has("TotalLaps") }?.optInt("TotalLaps")
        }.lastOrNull()?.takeIf { it > 0 }

    /** TeamRadio.jsonStream: each line adds Captures, as a list or as numbered entries. */
    internal fun parseRadio(stream: String): List<RadioClip> {
        val clips = mutableListOf<RadioClip>()
        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            runCatching { JSONObject(line.substring(streamPrefixLength(line))) }.getOrNull()?.let { addRadio(it, clips) }
        }
        return clips.sortedBy { it.utc }
    }

    internal fun addRadio(json: JSONObject, into: MutableList<RadioClip>) {
        val captures = json.opt("Captures")
        val list = when (captures) {
            is org.json.JSONArray -> (0 until captures.length()).mapNotNull { captures.optJSONObject(it) }
            is JSONObject -> captures.keys().asSequence().mapNotNull { captures.optJSONObject(it) }.toList()
            else -> emptyList()
        }
        list.forEach { c ->
            val utc = c.optString("Utc").let { if (it.isNotEmpty() && !it.endsWith("Z")) it + "Z" else it }
            val time = runCatching { Instant.parse(utc).toEpochMilli() }.getOrNull() ?: return@forEach
            val path = c.optString("Path").ifEmpty { return@forEach }
            if (into.none { it.path == path }) into += RadioClip(time, c.optString("RacingNumber"), path)
        }
    }

    /** The first time the session status went to Started (for a race, the formation lap). */
    internal fun parseSessionStarted(stream: String, streamStart: Long): Long? {
        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            val status = runCatching { JSONObject(line.substring(streamPrefixLength(line))).optString("Status") }.getOrNull()
            if (status == "Started") return streamStart + offsetMs(line)
        }
        return null
    }

    /** Race control's messages, each with its time (UTC ms), oldest first. */
    private fun controlMessages(stream: String, streamStart: Long): List<Pair<JSONObject, Long>> {
        val out = mutableListOf<Pair<JSONObject, Long>>()
        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            val messages = runCatching { JSONObject(line.substring(streamPrefixLength(line))).opt("Messages") }.getOrNull() ?: return@forEach
            val list = when (messages) {
                is org.json.JSONArray -> (0 until messages.length()).map { messages.getJSONObject(it) }
                is JSONObject -> messages.keys().asSequence().mapNotNull { messages.optJSONObject(it) }.toList()
                else -> emptyList()
            }
            list.forEach { m ->
                // Utc comes with or without a zone; without one it's UTC anyway.
                val utc = m.optString("Utc").let { if (it.isNotEmpty() && !it.endsWith("Z")) it + "Z" else it }
                out += m to (runCatching { Instant.parse(utc).toEpochMilli() }.getOrNull() ?: (streamStart + offsetMs(line)))
            }
        }
        return out
    }

    /** The last chequered flag in race control's messages. */
    internal fun parseChequered(stream: String, streamStart: Long): Long? =
        controlMessages(stream, streamStart).lastOrNull { it.first.optString("Flag") == "CHEQUERED" }?.second

    /**
     * Yellows and clears in single marshal sectors ("DOUBLE YELLOW IN TRACK SECTOR 10"), and
     * "TRACK CLEAR", which clears them all.
     */
    internal fun parseSectorFlags(stream: String, streamStart: Long): List<SectorFlag> =
        controlMessages(stream, streamStart).mapNotNull { (m, utc) ->
            val scope = m.optString("Scope")
            when (m.optString("Flag")) {
                "YELLOW" -> m.optInt("Sector", 0).takeIf { scope == "Sector" && it > 0 }?.let { SectorFlag(utc, it, 1) }
                "DOUBLE YELLOW" -> m.optInt("Sector", 0).takeIf { scope == "Sector" && it > 0 }?.let { SectorFlag(utc, it, 2) }
                "CLEAR", "GREEN" -> when (scope) {
                    "Sector" -> m.optInt("Sector", 0).takeIf { it > 0 }?.let { SectorFlag(utc, it, 0) }
                    "Track" -> SectorFlag(utc, 0, 0)
                    else -> null
                }
                else -> null
            }
        }.sortedBy { it.utc }

    /**
     * Each red flag from race control, until the next green light, chequered flag or session
     * (re)start; open-ended while it's still out.
     */
    internal fun parseRedFlags(stream: String, streamStart: Long): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var since: Long? = null
        controlMessages(stream, streamStart).forEach { (m, utc) ->
            val flag = m.optString("Flag")
            // Not "TRACK CLEAR": race control sends that while the session is still stopped (Spa 2026, Q3).
            val green = flag == "GREEN" || flag == "CHEQUERED" ||
                (m.optString("Category") == "SessionStatus" && m.optString("Message").contains("STARTED"))
            when {
                flag == "RED" -> if (since == null) since = utc
                green -> since?.let { out += it to utc; since = null }
            }
        }
        since?.let { out += it to Long.MAX_VALUE }
        return out
    }

    /** When race control said "SAFETY CAR IN THIS LAP". */
    internal fun parseSafetyCarIn(stream: String, streamStart: Long): List<Long> =
        controlMessages(stream, streamStart)
            .filter { (m, _) -> m.optString("Category") == "SafetyCar" && m.optString("Message").contains("IN THIS LAP") }
            .map { it.second }

    /**
     * Announced restarts: "Q3 WILL RESUME AT 17:04", "RACE WILL RESUME AT 15:42", "SQ2 WILL START
     * AT 17:08", "ESTIMATED TIME OF SQ1 RESUMPTION - 16:59". Times are the track's local time;
     * [gmtOffset] ("02:00:00") is the session's offset from GMT.
     */
    internal fun parseResumptions(stream: String, streamStart: Long, gmtOffset: String): List<Resumption> {
        val zone = parseOffset(gmtOffset)
        return controlMessages(stream, streamStart).mapNotNull { (m, utc) ->
            val text = m.optString("Message").uppercase()
            WILL_AT.find(text)?.groupValues?.let { g -> return@mapNotNull resumption(utc, zone, g[1], g[2], g[3], g[4]) }
            ESTIMATED.find(text)?.groupValues?.let { g ->
                return@mapNotNull resumption(utc, zone, g[1], if (g[2] == "RESUMPTION") "RESUME" else "START", g[3], g[4])
            }
            null
        }
    }

    /** One announcement as a [Resumption]: the next time it's [h]:[min] at the track after it was made. */
    private fun resumption(utc: Long, zone: java.time.ZoneOffset, who: String, verb: String, h: String, min: String): Resumption? {
        val said = java.time.Instant.ofEpochMilli(utc).atOffset(zone)
        var at = said.withHour(h.toIntOrNull() ?: return null).withMinute(min.toIntOrNull() ?: return null).withSecond(0).withNano(0)
        if (at.isBefore(said.minusHours(1))) at = at.plusDays(1)
        val name = who.removePrefix("THE ").trim().let { w ->
            if (w.matches(Regex("""S?Q\d"""))) w else w.lowercase().replaceFirstChar { it.uppercase() }
        }
        val action = when (verb) {
            "RESUME" -> "resumes"
            "RESTART" -> "restarts"
            else -> "starts"
        }
        return Resumption(utc, at.toInstant().toEpochMilli(), "$name $action")
    }

    /** TrackStatus.jsonStream: the whole track's status code as it changes. */
    internal fun parseTrackStatus(stream: String, streamStart: Long): List<Pair<Long, Int>> =
        stream.lineSequence().mapNotNull { line ->
            if (line.length <= streamPrefixLength(line)) return@mapNotNull null
            val code = runCatching { JSONObject(line.substring(streamPrefixLength(line))).optString("Status").toInt() }.getOrNull()
                ?: return@mapNotNull null
            streamStart + offsetMs(line) to code
        }.toList()

    /**
     * ExtrapolatedClock.jsonStream: lines of Utc, Remaining ("00:17:59") and Extrapolating, any of
     * which a line can leave out. Without Remaining, the clock reads what it had counted down to.
     */
    internal fun parseClock(stream: String, streamStart: Long): List<SessionClock.Mark> {
        val marks = mutableListOf<SessionClock.Mark>()
        stream.lineSequence().forEach { line ->
            if (line.length <= streamPrefixLength(line)) return@forEach
            val json = runCatching { JSONObject(line.substring(streamPrefixLength(line))) }.getOrNull() ?: return@forEach
            val utc = json.optString("Utc").takeIf { it.isNotEmpty() }
                ?.let { text -> runCatching { java.time.Instant.parse(if (text.endsWith("Z")) text else text + "Z").toEpochMilli() }.getOrNull() }
                ?: (streamStart + offsetMs(line))
            val last = marks.lastOrNull()
            val remaining = json.optString("Remaining").takeIf { it.isNotEmpty() }?.let(::clockMs)
                ?: last?.let { if (it.running) (it.remainingMs - (utc - it.utc)).coerceAtLeast(0) else it.remainingMs }
                ?: return@forEach
            val running = if (json.has("Extrapolating")) json.optBoolean("Extrapolating") else last?.running ?: false
            marks += SessionClock.Mark(utc, remaining, running)
        }
        return marks.sortedBy { it.utc }
    }

    /** "00:17:59" in ms. */
    private fun clockMs(text: String): Long? {
        val parts = text.split(":").map { it.toDoubleOrNull() ?: return null }
        if (parts.size != 3) return null
        return ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000).toLong()
    }

    /** Which part of qualifying is running, as TimingData says when it changes. */
    internal fun parseParts(stream: String, streamStart: Long): List<Pair<Long, Int>> =
        stream.lineSequence().mapNotNull { line ->
            if (!line.contains("\"SessionPart\"") || line.length <= streamPrefixLength(line)) return@mapNotNull null
            val part = SESSION_PART.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            streamStart + offsetMs(line) to part
        }.toList()

    internal class Grid(val startLine: Float, val lightsOutUtc: Long)

    /**
     * Finds a race's grid from the cars themselves. Before the start the field sits still near the
     * line twice: on the grid before the formation lap, then again after it, just before lights
     * out. The second stop gives both the start (its front car is on pole, the line a few metres
     * ahead) and the moment of lights out (when it ends). Stops after the chequered flag
     * ([before]) don't count: that's parc fermé.
     */
    internal fun findGrid(geometry: TrackGeometry, tracks: Map<String, DriverTrack>, before: Long?): Grid? {
        if (geometry.length == 0f || tracks.size < GRID_CARS) return null
        val from = tracks.values.minOf { it.startMs } + STILL_MS
        val to = minOf(before ?: Long.MAX_VALUE, tracks.values.maxOf { it.endMs })
        // Each stop: first moment, last moment, and its front car's lap fraction at the last moment.
        val stops = mutableListOf<Triple<Long, Long, Float>>()
        var t = from
        while (t <= to) {
            var count = 0
            var front = -Float.MAX_VALUE
            for (track in tracks.values) {
                val now = track.at(t) ?: continue
                val earlier = track.at(t - STILL_MS) ?: continue
                if (hypot(now.first - earlier.first, now.second - earlier.second) > STILL_UNITS) continue
                var s = geometry.progress(now.first, now.second)
                if (s > 0.5f) s -= 1f
                if (s < -GRID_ZONE || s > GRID_ZONE / 2) continue
                count++
                if (s > front) front = s
            }
            if (count >= GRID_CARS) {
                val last = stops.lastOrNull()
                if (last == null || t - last.second > MOVING_MS) stops += Triple(t, t, front)
                else stops[stops.lastIndex] = Triple(last.first, t, front)
            }
            // Two stops and the field long gone: the race is under way.
            if (stops.size >= 2 && t - stops[1].second > MOVING_MS * 2) break
            t += STEP_MS
        }
        val stop = stops.getOrNull(1) ?: stops.firstOrNull() ?: return null
        return Grid(
            startLine = (stop.third + POLE_TO_LINE_UNITS / geometry.length).mod(1f),
            lightsOutUtc = stop.second + STEP_MS / 2,
        )
    }

    /**
     * Where cars were when their lap count ticked over, pinned to the outline. The busiest spot
     * wins, so pit-lane crossings and timing lag don't drag it off the line.
     */
    internal fun findStartFinish(
        outline: FloatArray,
        crossings: List<Pair<String, Long>>,
        tracks: Map<String, DriverTrack>,
    ): Int? {
        if (outline.size < 8) return null
        val geometry = TrackGeometry(outline)
        val n = outline.size / 2
        val counts = IntArray(n)
        var found = 0
        crossings.forEach { (number, time) ->
            val (x, y) = tracks[number]?.at(time) ?: return@forEach
            counts[geometry.nearestIndex(x, y)]++
            found++
        }
        if (found < 5) return null
        // Busiest stretch of 7 points, then its busiest point (else ties pick the stretch's start).
        var best = 0
        var bestScore = -1L
        for (i in 0 until n) {
            var c = 0
            for (k in -3..3) c += counts[(i + k + n) % n]
            val score = c * 100_000L + counts[i]
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }

    /** The same loop, starting at point [index]. */
    internal fun startAt(outline: FloatArray, index: Int): FloatArray {
        val n = outline.size / 2
        return FloatArray(outline.size) { i -> outline[((i / 2 + index) % n) * 2 + i % 2] }
    }

    /**
     * The circuit's outline (x0,y0,x1,y1,...), numbered corners and marshal sectors, from
     * MultiViewer's public circuit data, which uses the same coordinates as F1's position feed.
     * Sent without the F1 token: it's a third party. The outline doesn't start at the line; a
     * live session needs it before any car has done a lap to trace one from.
     */
    internal fun fetchCircuit(circuitKey: Int, year: Int): Circuit? {
        val conn = URL("https://api.multiviewer.app/api/v1/circuits/$circuitKey/$year").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", userAgent)
            if (conn.responseCode >= 400) return null
            val json = JSONObject(conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
            val xs = json.optJSONArray("x")
            val ys = json.optJSONArray("y")
            val outline = if (xs != null && ys != null && xs.length() == ys.length()) {
                FloatArray(xs.length() * 2) { i -> (if (i % 2 == 0) xs else ys).optDouble(i / 2).toFloat() }
            } else FloatArray(0)
            fun points(key: String): List<Corner> {
                val list = json.optJSONArray(key) ?: return emptyList()
                return (0 until list.length()).mapNotNull { i ->
                    val c = list.getJSONObject(i)
                    val p = c.optJSONObject("trackPosition") ?: return@mapNotNull null
                    Corner(c.optInt("number"), p.optDouble("x").toFloat(), p.optDouble("y").toFloat())
                }
            }
            return Circuit(outline, points("corners"), points("marshalSectors"))
        } finally {
            conn.disconnect()
        }
    }

    internal fun exactArchiveSession(sessionKey: Int, index: JSONObject): Pair<String, String>? {
        val meetings = index.getJSONArray("Meetings")
        for (i in 0 until meetings.length()) {
            val sessions = meetings.getJSONObject(i).getJSONArray("Sessions")
            for (j in 0 until sessions.length()) {
                val session = sessions.getJSONObject(j)
                val path = session.optString("Path")
                if (session.optInt("Key") == sessionKey && path.isNotBlank()) {
                    return path to session.optString("Type")
                }
            }
        }
        return null
    }

    /** Finds the session's archive path by F1's session key, else by meeting and session name. */
    private fun findSession(ref: SessionRef, watchedUtc: Long?, index: JSONObject): Pair<String, String> {
        val lookupUtc = archiveLookupTime(watchedUtc, ref.sessionStartUtcMs)
        val meetings = index.getJSONArray("Meetings")
        var byName: Pair<String, String>? = null
        var byNameLength = 0
        // The meeting's sessions with their start times, for when the name doesn't say which;
        // and its folder and time zone, to look for a session the index doesn't list yet.
        val meetingSessions = mutableListOf<Pair<Long, Pair<String, String>>>()
        var meetingFolder: String? = null
        var meetingOffset: String? = null
        for (i in 0 until meetings.length()) {
            val meeting = meetings.getJSONObject(i)
            val sameMeeting = meeting.optInt("Key") == ref.meetingKey ||
                meeting.optString("Name").equals(ref.meetingName, ignoreCase = true)
            val sessions = meeting.getJSONArray("Sessions")
            for (j in 0 until sessions.length()) {
                val session = sessions.getJSONObject(j)
                val path = session.optString("Path")
                if (path.isEmpty()) continue
                val found = path to session.optString("Type")
                if (ref.sessionKey != null && session.optInt("Key") == ref.sessionKey) return found
                if (sameMeeting) {
                    sessionStartUtc(session)?.let { meetingSessions += it to found }
                    meetingFolder = path.split('/').take(2).joinToString("/", postfix = "/")
                    meetingOffset = session.optString("GmtOffset").ifEmpty { null }
                }
                val name = session.optString("Name")
                // "Sprint Qualifying" also contains "Qualifying"; keep the longest matching name.
                if (sameMeeting && name.length > byNameLength && ref.title.contains(name, ignoreCase = true)) {
                    byName = found
                    byNameLength = name.length
                }
            }
        }
        byName?.let { return it }
        // F1 TV's live broadcasts (and their replays straight after) are titled with just the
        // event, with no session key: take the weekend's session that had started by the time
        // the video was broadcast, the latest such.
        if (lookupUtc != null) {
            fun recent(start: Long) = start <= lookupUtc + SESSION_SLACK_MS && lookupUtc - start < SESSION_SPAN_MS
            meetingSessions.filter { recent(it.first) }.maxByOrNull { it.first }?.let { return it.second }
            // Just finished: the index can lag well behind. Sessions live in predictable
            // folders, so look for that day's under the weekend's folder.
            val folder = meetingFolder
            if (folder != null) {
                val zone = parseOffset(meetingOffset ?: "00:00:00")
                val day = java.time.Instant.ofEpochMilli(lookupUtc).atOffset(zone).toLocalDate()
                SESSION_FOLDERS.mapNotNull { name ->
                    val path = "$folder${day}_$name/"
                    runCatching { JSONObject(fetch(path + "SessionInfo.json")) }.getOrNull()?.let { info ->
                        sessionStartUtc(info)?.let { it to (path to info.optString("Type")) }
                    }
                }.filter { recent(it.first) }.maxByOrNull { it.first }?.let { return it.second }
            }
        }
        throw IOException("Couldn't find \"${ref.title}\" in F1's ${ref.year} timing archive")
    }

    /** A session's start in UTC: the archive gives local time plus its offset from GMT. */
    private fun sessionStartUtc(session: JSONObject): Long? = runCatching {
        val local = java.time.LocalDateTime.parse(session.getString("StartDate"))
        local.toInstant(parseOffset(session.optString("GmtOffset", "00:00:00"))).toEpochMilli()
    }.getOrNull()

    /** "-05:00:00" to a zone offset. */
    internal fun parseOffset(text: String): java.time.ZoneOffset {
        val negative = text.startsWith("-")
        val (h, m) = text.trimStart('-', '+').split(":").let { it[0].toInt() to it.getOrElse(1) { "0" }.toInt() }
        return java.time.ZoneOffset.ofHoursMinutes(if (negative) -h else h, if (negative) -m else m)
    }

    internal fun parseDrivers(json: JSONObject): Map<String, DriverInfo> {
        val result = mutableMapOf<String, DriverInfo>()
        json.keys().forEach { number ->
            val d = json.optJSONObject(number) ?: return@forEach
            val color = runCatching { Color.parseColor("#" + d.optString("TeamColour")) }.getOrDefault(Color.WHITE)
            result[number] = DriverInfo(
                number, d.optString("Tla", number), color,
                firstName = d.optString("FirstName"),
                lastName = d.optString("LastName"),
                team = d.optString("TeamName"),
            )
        }
        return result
    }

    internal data class PositionParseReport(
        val tracks: Map<String, TrackBuilder>,
        val inputRecords: Int,
        val decodedRecords: Int,
        val malformedRecords: Int,
        val positionSamples: Int,
        val streamStart: Long? = null,
    )

    internal fun parsePositions(stream: String): Map<String, TrackBuilder> =
        parsePositionsDetailed(stream).tracks

    internal fun parsePositionsDetailed(stream: String): PositionParseReport =
        parsePositionLines(stream.lineSequence())

    private fun parsePositionLines(
        lines: Sequence<String>,
        checkActive: () -> Unit = {},
        checkpoint: ((String) -> Unit)? = null,
        onProgress: ((Map<String, TrackBuilder>) -> Unit)? = null,
    ): PositionParseReport {
        val starts = ArrayList<Long>(START_SAMPLES)
        val tracks = mutableMapOf<String, TrackBuilder>()
        var inputRecords = 0
        var decodedRecords = 0
        var malformedRecords = 0
        var positionSamples = 0
        ArchiveInflater().use { inflater ->
            lines.forEach { raw ->
                checkActive()
                val line = raw.removePrefix("\uFEFF")
                val prefix = streamPrefixLength(line)
                if (line.length <= prefix) return@forEach
                inputRecords++
                if (inputRecords == 1 || inputRecords % 1024 == 0) {
                    checkpoint?.invoke("records=$inputRecords decoded=$decodedRecords malformed=$malformedRecords samples=$positionSamples cars=${tracks.size}")
                }
                val json = try {
                    JSONObject(inflater.decode(line.substring(prefix)))
                } catch (_: Exception) {
                    malformedRecords++
                    return@forEach
                }
                val samples = json.optJSONArray("Position")
                if (samples == null) {
                    malformedRecords++
                    return@forEach
                }
                if (starts.size < START_SAMPLES) positionStart(line, samples)?.let(starts::add)
                decodedRecords++
                positionSamples += samples.length()
                addPositions(json, tracks)
                if (decodedRecords == 1 || decodedRecords % 32 == 0) onProgress?.invoke(tracks)
            }
        }
        return PositionParseReport(tracks, inputRecords, decodedRecords, malformedRecords, positionSamples,
            starts.sorted().let { it.getOrNull(it.size / 2) })
    }

    /**
     * A replay's car speeds. A large file, and only onboards show them, so it's read line by line
     * once one is on screen rather than holding up the map.
     */
    suspend fun loadSpeeds(base: String): Map<String, SpeedLog> = withContext(Dispatchers.IO) {
        val cars = mutableMapOf<String, SpeedBuilder>()
        open(base.removePrefix(BASE) + "CarData.z.jsonStream").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                ensureActive()
                val text = line.removePrefix("\uFEFF")
                if (text.length <= streamPrefixLength(text)) return@forEach
                runCatching { JSONObject(inflate(text.substring(streamPrefixLength(text)))) }.getOrNull()?.let { addCarData(it, cars) }
            }
        }
        cars.mapValues { it.value.build() }
    }

    /** One decoded CarData message: channel 2 is speed (km/h), channel 3 is gear. */
    internal fun addCarData(json: JSONObject, cars: MutableMap<String, SpeedBuilder>) {
        val entries = json.optJSONArray("Entries") ?: return
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val time = runCatching { Instant.parse(entry.optString("Utc")).toEpochMilli() }.getOrNull() ?: continue
            val list = entry.optJSONObject("Cars") ?: continue
            list.keys().forEach { number ->
                val channels = list.optJSONObject(number)?.optJSONObject("Channels")
                if (channels != null) {
                    val kmh = channels.optInt("2", -1).takeIf { it in 0..500 }
                    val gear = channels.optInt("3", -1).takeIf { it in 0..8 }
                    if (kmh != null || gear != null) cars.getOrPut(number) { SpeedBuilder() }.add(time, kmh, gear)
                }
            }
        }
    }

    /** One decoded Position message (archive line or live update) onto the cars' tracks. */
    internal fun addPositions(json: JSONObject, tracks: MutableMap<String, TrackBuilder>) {
        val samples = json.optJSONArray("Position") ?: return
        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val time = runCatching { Instant.parse(sample.getString("Timestamp")).toEpochMilli() }.getOrNull() ?: continue
            val entries = sample.optJSONObject("Entries") ?: continue
            entries.keys().forEach { number ->
                val e = entries.getJSONObject(number)
                if (e.optString("Status") == "OffTrack") return@forEach
                tracks.getOrPut(number) { TrackBuilder() }
                    .add(time, e.optDouble("X").toFloat(), e.optDouble("Y").toFloat())
            }
        }
    }

    /**
     * jsonStream lines carry an offset from the stream start; the heartbeat ties it to UTC. The
     * median of its first beats, so one odd beat can't move every time in the session.
     */
    internal fun parseStreamStart(stream: String): Long {
        val starts = stream.lineSequence().mapNotNull { line ->
            if (line.length <= streamPrefixLength(line)) return@mapNotNull null
            val utc = runCatching { JSONObject(line.substring(streamPrefixLength(line))).getString("Utc") }.getOrNull() ?: return@mapNotNull null
            runCatching { Instant.parse(utc).toEpochMilli() - offsetMs(line) }.getOrNull()
        }.take(START_SAMPLES).toList().sorted()
        if (starts.isEmpty()) throw IOException("The archive's heartbeat has no clock")
        return starts[starts.size / 2]
    }

    /**
     * The stream start the car positions imply: each line's latest sample time less the line's
     * offset (the line goes out a moment after its samples, so this runs a touch early).
     */
    internal fun positionsStreamStart(stream: String): Long? {
        val starts = stream.lineSequence().mapNotNull { line ->
            if (line.length <= streamPrefixLength(line)) return@mapNotNull null
            val json = runCatching { JSONObject(inflate(line.substring(streamPrefixLength(line)))) }.getOrNull() ?: return@mapNotNull null
            val samples = json.optJSONArray("Position") ?: return@mapNotNull null
            positionStart(line, samples)
        }.take(START_SAMPLES).toList().sorted()
        return if (starts.isEmpty()) null else starts[starts.size / 2]
    }

    private fun positionStart(line: String, samples: org.json.JSONArray): Long? {
        val latest = (0 until samples.length()).mapNotNull { i ->
            runCatching { Instant.parse(samples.getJSONObject(i).getString("Timestamp")).toEpochMilli() }.getOrNull()
        }.maxOrNull() ?: return null
        return runCatching { latest - offsetMs(line) }.getOrNull()
    }

    internal fun parseStates(stream: String, streamStart: Long): StateParse {
        val crossings = mutableListOf<Pair<String, Long>>()
        val times = mutableMapOf<String, MutableList<Long>>()
        val values = mutableMapOf<String, MutableList<LapState>>()
        val current = mutableMapOf<String, LapState>()
        val laps = mutableMapOf<String, Int>()

        stream.lineSequence().forEach { line ->
            // Sector and gap updates cannot change lap state.
            if (!line.contains("\"InPit\"") && !line.contains("\"PitOut\"") &&
                !line.contains("\"NumberOfLaps\"") && !line.contains("\"KnockedOut\"")) return@forEach
            if (line.length <= streamPrefixLength(line)) return@forEach
            val lines = runCatching { JSONObject(line.substring(streamPrefixLength(line))).optJSONObject("Lines") }.getOrNull()
                ?: return@forEach
            val time = streamStart + offsetMs(line)
            lines.keys().forEach { number ->
                val d = lines.optJSONObject(number) ?: return@forEach
                var state = current[number] ?: LapState.UNKNOWN
                if (d.optBoolean("InPit")) state = LapState.PIT
                // Leaving the pits: F1 sends PitOut, but before a race the last run to the grid
                // can end with just InPit going false. Either way the car is out; without the
                // second case, cars that hadn't stopped yet stayed "in the pits" (dimmed) all race.
                if (d.optBoolean("PitOut") || (d.has("InPit") && !d.optBoolean("InPit") && state == LapState.PIT)) {
                    state = LapState.OUT_LAP
                }
                if (d.has("NumberOfLaps")) {
                    val n = d.optInt("NumberOfLaps")
                    val before = laps[number]
                    laps[number] = n
                    // Crossing the line: an out lap becomes a push lap, push and cool-down alternate.
                    if (before != null && n > before) {
                        crossings += number to time
                        state = when (state) {
                            LapState.OUT_LAP, LapState.COOL_DOWN -> LapState.PUSH
                            LapState.PUSH -> LapState.COOL_DOWN
                            else -> state
                        }
                    }
                }
                if (d.optBoolean("KnockedOut")) state = LapState.KNOCKED_OUT
                if (state != current[number]) {
                    current[number] = state
                    times.getOrPut(number) { mutableListOf() }.add(time)
                    values.getOrPut(number) { mutableListOf() }.add(state)
                }
            }
        }
        return StateParse(
            states = times.mapValues { (number, t) -> StateLog(t.toLongArray(), values.getValue(number).toTypedArray()) },
            crossings = crossings,
        )
    }

    /**
     * Traces the circuit from one lap: from a point on track, follow a car until it comes back
     * to the same spot. The shortest such loop across a few cars is a clean racing line, not a
     * trip through the pit lane.
     */
    internal fun traceOutline(tracks: List<TrackBuilder>): FloatArray {
        var best: FloatArray? = null
        var bestTime = Long.MAX_VALUE
        for (track in tracks.sortedByDescending { it.size }.take(4)) {
            for (k in 1..9) {
                val start = track.size * k / 10
                val loop = track.loopFrom(start) ?: continue
                if (loop.second < bestTime) {
                    bestTime = loop.second
                    best = loop.first
                }
            }
        }
        return best ?: FloatArray(0)
    }

    /** When a session's streams count from: the heartbeat's clock, unless the cars' own times disagree. */
    internal fun streamStart(heartbeatStream: String, positions: String): Long {
        val heartbeat = parseStreamStart(heartbeatStream)
        return positionsStreamStart(positions)?.takeIf { abs(it - heartbeat) > STREAM_START_SLACK_MS } ?: heartbeat
    }

    internal fun fetchStream(path: String): String = fetch(path)

    private fun fetch(path: String): String =
        open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }.removePrefix("﻿")

    private fun open(path: String): java.io.InputStream {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        if (code == 401 || code == 403) throw IOException("F1's timing archive refused the login (HTTP $code)")
        if (code >= 400) throw IOException("HTTP $code for ${path.substringAfterLast('/')}")
        return conn.inputStream
    }

    internal fun inflate(quoted: String): String = ArchiveInflater().use { it.decode(quoted) }

    private class ArchiveInflater : AutoCloseable {
        private val inflater = Inflater(true)
        private val buffer = ByteArray(8192)
        private val output = ByteArrayOutputStream()
        fun decode(quoted: String): String {
            inflater.reset()
            output.reset()
            inflater.setInput(Base64.getDecoder().decode(quoted.trim().trim('"')))
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && !inflater.finished()) throw IOException("Incomplete or stalled compressed timing record")
                output.write(buffer, 0, count)
            }
            return output.toString("UTF-8").removePrefix("\uFEFF")
        }

        override fun close() = inflater.end()
    }

    private fun offsetMs(line: String): Long = streamOffsetMs(line)

    private val WILL_AT = Regex("""^(?:THE )?([A-Z0-9 ]+?) WILL (RESUME|RESTART|START) AT (\d{1,2}):(\d{2})""")
    private val ESTIMATED = Regex("""ESTIMATED TIME OF (?:THE )?([A-Z0-9 ]+?) (RESUMPTION|RESTART|START) ?-? ?(\d{1,2}):(\d{2})""")

    companion object {
        internal const val BASE = "https://livetiming.formula1.com/static/"
        /** Lines read to find a stream's start. */
        private const val START_SAMPLES = 50
        private const val MAX_SEASON_INDEX_BYTES = 2L * 1024L * 1024L
        private const val SEASON_INDEX_MAX_AGE_MS = 24L * 60L * 60_000L
        /** The heartbeat and the car positions may disagree by this much before the cars win. */
        private const val STREAM_START_SLACK_MS = 60_000L
        private val SESSION_PART = Regex("\"SessionPart\":(\\d+)")
        /** A video may start a little before its session officially does (build-up). */
        private const val SESSION_SLACK_MS = 30 * 60_000L
        /** A session plus its build-up and aftermath: a video this long after a start still belongs to it. */
        private const val SESSION_SPAN_MS = 4 * 3_600_000L
        /** How F1 names session folders, for finding one the index doesn't list yet. */
        private val SESSION_FOLDERS = listOf(
            "Race", "Sprint", "Qualifying", "Sprint_Qualifying", "Sprint_Shootout",
            "Practice_1", "Practice_2", "Practice_3",
        )
        // Grid search: sample every second; a car is still if it moved under 3 m in 4 s; the
        // grid sits within 12% of a lap before the line; at least 12 cars make a grid; stops
        // closer than a minute apart are one stop; pole sits about 5 m behind the line.
        private const val STEP_MS = 1_000L
        private const val STILL_MS = 4_000L
        private const val STILL_UNITS = 30f
        private const val GRID_ZONE = 0.12f
        private const val GRID_CARS = 12
        private const val MOVING_MS = 60_000L
        private const val POLE_TO_LINE_UNITS = 50f
    }
}

/** Grows one car's samples, then freezes them into a [DriverTrack]. */
class TrackBuilder {
    private var t = LongArray(4096)
    private var x = FloatArray(4096)
    private var y = FloatArray(4096)
    var size = 0
        private set

    fun add(time: Long, px: Float, py: Float) {
        // Samples can repeat or arrive slightly out of order; keep time increasing.
        if (size > 0 && time <= t[size - 1]) return
        if (size == t.size) {
            t = t.copyOf(size * 2)
            x = x.copyOf(size * 2)
            y = y.copyOf(size * 2)
        }
        t[size] = time
        x[size] = px
        y[size] = py
        size++
    }

    fun build() = DriverTrack(t, x, y, size)

    /** As [build], with [earlier]'s samples from before these in front: the archive's part of a live session. */
    fun build(earlier: TrackBuilder?): DriverTrack {
        if (earlier == null) return build()
        val first = if (size > 0) t[0] else Long.MAX_VALUE
        var k = 0
        while (k < earlier.size && earlier.t[k] < first) k++
        if (k == 0) return build()
        val n = k + size
        val nt = LongArray(n)
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        System.arraycopy(earlier.t, 0, nt, 0, k)
        System.arraycopy(earlier.x, 0, nx, 0, k)
        System.arraycopy(earlier.y, 0, ny, 0, k)
        System.arraycopy(t, 0, nt, k, size)
        System.arraycopy(x, 0, nx, k, size)
        System.arraycopy(y, 0, ny, k, size)
        return DriverTrack(nt, nx, ny, n)
    }

    /** The points of one lap starting at [start] and its duration, or null if it doesn't close. */
    fun loopFrom(start: Int): Pair<FloatArray, Long>? {
        var travelled = 0f
        for (i in start + 1 until size) {
            if (t[i] - t[i - 1] > 2_000) return null
            travelled += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            if (travelled > 120_000) return null
            if (travelled > 20_000 && hypot(x[i] - x[start], y[i] - y[start]) < 300f) {
                val points = FloatArray((i - start + 1) * 2)
                for (k in start..i) {
                    points[(k - start) * 2] = x[k]
                    points[(k - start) * 2 + 1] = y[k]
                }
                return points to (t[i] - t[start])
            }
        }
        return null
    }
}
