package app.apexview.cast

import app.apexview.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Only current display state crosses the LAN. No archive histories, tokens, URLs or radio paths. */
internal object CastTimingProtocol {
    const val VERSION = 1
    internal const val POSITION_STEP_MS = 200L
    // Six measured intervals preserve motion through short LAN jitter or
    // receiver buffer latency, without extrapolating beyond authorized data.
    internal const val MAX_PREDICTION_STEPS = 6
    private const val MAX_DRIVERS = 24
    private const val MAX_COORDINATES = 3072
    data class Panel(val x: Float, val y: Float, val width: Float, val height: Float)
    data class CarMotion(
        val number: String, val x: Float, val y: Float, val nextX: Float, val nextY: Float, val state: LapState,
        val later: List<Pair<Float, Float>> = emptyList(),
    ) {
        fun at(steps: Float): Car {
            val last = later.size + 1
            val progress = steps.coerceIn(0f, last.toFloat())
            val i = progress.toInt().coerceAtMost(last - 1)
            val fraction = progress - i
            // Smooth slopes at measured 200 ms boundaries, but pass through
            // every transmitted position exactly. Axis-monotone cubic Hermite
            // avoids the corner overshoot of unconstrained Catmull-Rom; this
            // is interpolation only, NEVER extrapolation beyond measured data.
            fun coordinate(index: Int, horizontal: Boolean): Float = when (index) {
                0 -> if (horizontal) x else y
                1 -> if (horizontal) nextX else nextY
                else -> if (horizontal) later[index - 2].first else later[index - 2].second
            }
            fun smooth(horizontal: Boolean): Float {
                val a = coordinate(i, horizontal)
                val b = coordinate(i + 1, horizontal)
                val p = coordinate((i - 1).coerceAtLeast(0), horizontal)
                val q = coordinate((i + 2).coerceAtMost(last), horizontal)
                val slope = b - a
                fun tangent(before: Float, after: Float): Float {
                    if (before == 0f || after == 0f || before * after <= 0f) return 0f
                    return 2f * before * after / (before + after)
                }
                val m0 = if (i == 0) slope else tangent(a - p, slope)
                val m1 = if (i + 1 == last) slope else tangent(slope, q - b)
                val t2 = fraction * fraction
                val t3 = t2 * fraction
                val interpolated = (2f * t3 - 3f * t2 + 1f) * a +
                    (t3 - 2f * t2 + fraction) * m0 +
                    (-2f * t3 + 3f * t2) * b +
                    (t3 - t2) * m1
                return interpolated.coerceIn(minOf(a, b), maxOf(a, b))
            }
            return Car(number, smooth(true), smooth(false), state)
        }
    }
    class Geometry(val id: String, val timing: SessionTiming)
    class Table(val rows: List<Pair<String, CardState>>, val tyres: Map<String, Tyre?>,
                val speeds: Map<String, Pair<Int?, Int?>>)
    class Frame(val geometryId: String, val positionMs: Long, val utcMs: Long,
        val density: Float, val fontScale: Float, val mapMode: MapMode, val map: Panel?, val tower: Panel?,
        val mapRotation: Float, val mapOpacity: Float, val dotSize: Float, val labelSize: Float,
        val towerOpacity: Float, val towerFontScale: Float, val towerHeaderPx: Float, val tvLayout: Boolean,
        val speedUnit: String, val onTop: String?, val leader: String?, val cars: List<CarMotion>,
        val flags: FlagPicture, val stop: PitStopNow?, val circle: PitCircleData?, val table: Table,
        val header: TowerSessionHeaderData, val message: String?) {
        fun carsAt(position: Long): List<Car> {
            // Hold the last measured endpoint between packets. Seeks invalidate frames at the receiver.
            val steps = (position - positionMs).toFloat() / POSITION_STEP_MS
            return cars.map { it.at(steps) }
        }
    }
    private fun number(v: Double, bound: Double = 10_000_000.0): Float = v.toFloat().also {
        require(v.isFinite() && it.isFinite() && abs(v) <= bound) { "Invalid timing coordinate" }
    }
    private fun JSONObject.f(key: String, min: Float, max: Float): Float = number(getDouble(key)).also { require(it in min..max) { "Invalid $key" } }
    private fun JSONArray.f(i: Int) = number(getDouble(i))
    private fun String.safe(n: Int = 80) = take(n)
    private fun JSONArray.count(max: Int): Int = length().also { require(it <= max) { "Timing array is too large" } }
    private fun JSONObject.optionalInt(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)
    private fun JSONArray.optionalInt(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun JSONArray.optionalString(i: Int): String? = if (isNull(i)) null else getString(i).safe()
    private fun encodePanel(value: Panel?) = value?.let { JSONArray(listOf(it.x, it.y, it.width, it.height)) } ?: JSONObject.NULL
    private fun readPanel(value: Any?): Panel? {
        if (value == null || value === JSONObject.NULL) return null
        val a = value as JSONArray; require(a.length() == 4)
        val p = Panel(a.f(0), a.f(1), a.f(2), a.f(3))
        require(abs(p.x) <= 100_000 && abs(p.y) <= 100_000 && p.width in 1f..100_000f && p.height in 1f..100_000f)
        return p
    }
    fun geometry(timing: SessionTiming): JSONObject {
        val outline = timing.outline
        val points = outline.size / 2
        val step = maxOf(1, (points + MAX_COORDINATES / 2 - 1) / (MAX_COORDINATES / 2))
        val coordinates = (0 until points step step).flatMap { listOf(outline[it * 2], outline[it * 2 + 1]) }
        val buffer = ByteBuffer.allocate(coordinates.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        coordinates.forEach { require(it.isFinite()); buffer.putFloat(it) }
        val drivers = JSONArray().apply { timing.drivers.entries.take(MAX_DRIVERS).forEach { (n, d) ->
            put(JSONArray(listOf(n.safe(8), d.tla.safe(8), d.color, d.firstName.safe(32), d.lastName.safe(32), d.team.safe(32))))
        } }
        val corners = JSONArray().apply { timing.corners.take(64).forEach { put(JSONArray(listOf(it.number, it.x, it.y))) } }
        val sectors = JSONArray().apply { timing.marshalSectors.take(64).forEach { put(JSONArray(listOf(it.number, it.x, it.y))) } }
        val body = JSONObject().put("version", VERSION).put("kind", "geometry")
            .put("type", timing.type.safe()).put("name", timing.name.safe()).put("drivers", drivers)
            .put("outline", Base64.getEncoder().encodeToString(buffer.array())).put("corners", corners).put("sectors", sectors)
            .put("finish", timing.hasStartFinish).put("startLine", timing.startLine ?: JSONObject.NULL)
        // Stable across live SessionTiming replacements, unlike object identity.
        body.put("id", Integer.toHexString(body.toString().hashCode()))
        return body
    }
    fun decodeGeometry(j: JSONObject): Geometry {
        require(j.getInt("version") == VERSION && j.getString("kind") == "geometry")
        val encoded = j.getString("outline"); require(encoded.length <= MAX_COORDINATES * 4 / 3 * 4 + 4)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size % 8 == 0 && bytes.size <= MAX_COORDINATES * 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val outline = FloatArray(bytes.size / 4) { number(buffer.float.toDouble()) }
        val a = j.getJSONArray("drivers")
        val drivers = (0 until a.count(MAX_DRIVERS)).associate { i ->
            val d = a.getJSONArray(i); val n = d.getString(0).safe(8); require(n.matches(Regex("[0-9]{1,3}")))
            n to DriverInfo(n, d.getString(1).safe(8), d.getInt(2), d.getString(3).safe(32), d.getString(4).safe(32), d.getString(5).safe(32))
        }
        require(drivers.size == a.length())
        fun corners(key: String): List<Corner> = j.getJSONArray(key).let { values -> (0 until values.count(64)).map { i ->
            values.getJSONArray(i).let { Corner(it.getInt(0), it.f(1), it.f(2)) }
        } }
        val timing = SessionTiming(j.getString("type").safe(), drivers, emptyMap(), emptyMap(), outline,
            j.getBoolean("finish"), corners("corners"), 0L, marshalSectors = corners("sectors"), name = j.getString("name").safe())
        if (!j.isNull("startLine")) timing.startLine = number(j.getDouble("startLine"), 1.0).also { require(it >= 0) }
        return Geometry(j.getString("id").safe(64), timing)
    }
    private fun sector(s: SectorCell?) = s?.let { JSONArray(listOf(it.time.safe(32), it.pace.ordinal,
        JSONArray(it.segments.take(64)), it.previousTime.safe(32))) } ?: JSONObject.NULL
    private fun readSector(value: Any?): SectorCell? {
        if (value == null || value === JSONObject.NULL) return null
        val a = value as JSONArray; val segments = a.getJSONArray(2)
        return SectorCell(a.getString(0).safe(32), Pace.entries[a.getInt(1)],
            (0 until segments.count(64)).map { segments.getInt(it).also { s -> require(s in 0..4096) } }, a.getString(3).safe(32))
    }
    fun table(timing: SessionTiming, now: Long): JSONArray = JSONArray().apply {
        timing.laps.entries.mapNotNull { (n, laps) -> laps.at(now, includeRunningTime = false)?.let { n to it } }.sortedBy { it.second.position ?: 99 }
            .take(MAX_DRIVERS).forEach { (n, s) ->
                val tyre = timing.tyres[n]?.at(now)
                put(JSONArray().put(n).put(s.position ?: JSONObject.NULL).put(s.inPit).put(s.retired)
                    .put(s.positionChange ?: JSONObject.NULL).put(sector(s.lastLap)).put(sector(s.bestLap))
                    .put(JSONArray().apply { repeat(3) { put(sector(s.towerSector(it))) } })
                    .put(s.gap?.safe() ?: JSONObject.NULL).put(s.toLeader?.safe() ?: JSONObject.NULL).put(s.ahead?.safe() ?: JSONObject.NULL)
                    .put(tyre?.compound?.safe(24) ?: JSONObject.NULL).put(tyre?.laps ?: 0)
                    .put(timing.speeds[n]?.kmhAt(now) ?: JSONObject.NULL).put(timing.speeds[n]?.gearAt(now) ?: JSONObject.NULL))
            }
    }
    fun readTable(a: JSONArray): Table {
        val tyres = mutableMapOf<String, Tyre?>(); val speeds = mutableMapOf<String, Pair<Int?, Int?>>()
        val rows = (0 until a.count(MAX_DRIVERS)).map { i ->
            val r = a.getJSONArray(i); require(r.length() == 15)
            val n = r.getString(0).safe(8); require(n.matches(Regex("[0-9]{1,3}")))
            val sectors = r.getJSONArray(7); require(sectors.length() == 3)
            tyres[n] = r.optionalString(11)?.let { Tyre(it, r.getInt(12).coerceIn(0, 1000)) }
            speeds[n] = r.optionalInt(13)?.coerceIn(0, 500) to r.optionalInt(14)?.coerceIn(0, 8)
            n to CardState(r.optionalInt(1), (0..2).map { readSector(sectors.opt(it)) }, emptyList(), null,
                readSector(r.opt(5)), r.optionalString(8), r.getBoolean(2), r.optionalString(9), r.optionalString(10),
                readSector(r.opt(6)), r.getBoolean(3), r.optionalInt(4))
        }
        require(rows.map { it.first }.distinct().size == rows.size)
        return Table(rows, tyres, speeds)
    }
    fun encodeFrame(geometryId: String, positionMs: Long, now: Long, density: Float, fontScale: Float,
        app: AppState, timing: SessionTiming?, table: JSONArray, circle: PitCircleData?, stop: PitStopNow?,
        cars: List<Car>): JSONObject {
        val flags = timing?.flags?.at(now) ?: FlagPicture(TrackCondition.CLEAR, emptyMap())
        val header = timing?.let { towerSessionHeader(it, now) } ?: TowerSessionHeaderData("TIMING", "", Color.White, "", emptyList())
        val panel = app.activeMapPanel
        val tower = app.towerSpot
        val mapPanel = if (app.mapMode.inWindow && panel.placed) Panel(panel.x, panel.y, panel.w, panel.h) else null
        val towerPanel = if (!app.towerOpen) null else app.castTowerBounds
            ?: if (tower.x >= 0 && tower.w > 0 && tower.h > 0) Panel(tower.x, tower.y, tower.w, tower.h) else null
        return JSONObject().put("version", VERSION).put("kind", "frame").put("geometry", geometryId)
            .put("position", positionMs).put("utc", now).put("density", density).put("fontScale", fontScale)
            .put("mapMode", app.mapMode.name).put("map", encodePanel(mapPanel)).put("tower", encodePanel(towerPanel))
            .put("rotation", panel.rotation).put("mapOpacity", app.mapOpacity).put("dot", app.mapDotSizeDp).put("label", app.mapLabelSizeSp)
            .put("towerOpacity", app.towerOpacity).put("towerFont", app.towerFontScale)
            .put("towerHeader", 0f)
            .put("tv", app.layoutMode == LayoutMode.TV).put("speedUnit", app.settings.speedUnit)
            .put("onTop", app.tiles.firstOrNull { it.id == app.bigTileIdForUi }?.channel?.racingNumber?.toString() ?: JSONObject.NULL)
            .put("leader", timing?.let { leaderAt(it, now) } ?: JSONObject.NULL)
            .put("cars", JSONArray().apply { cars.take(MAX_DRIVERS).forEach { car ->
                val track = timing!!.tracks[car.number]
                var next = track?.atOrFirst(now + POSITION_STEP_MS) ?: (car.x to car.y)
                val row = JSONArray(listOf(car.number, car.x, car.y, next.first, next.second, car.state.ordinal))
                // Keep the original 200 ms endpoint for older receivers.
                // Additional real track samples cover up to 1.2s of jitter and
                // avoid artificial freezes before a delayed packet is received.
                for (step in 2..MAX_PREDICTION_STEPS) {
                    next = track?.atOrFirst(now + step * POSITION_STEP_MS) ?: next
                    row.put(next.first).put(next.second)
                }
                put(row)
            } }).put("condition", flags.track.ordinal).put("yellow", JSONArray().apply { flags.yellow.entries.take(64).forEach { put(JSONArray(listOf(it.key, it.value))) } })
            .put("resumption", flags.resumption?.let { JSONArray(listOf(it.announcedUtc, it.atUtc, it.what.safe())) } ?: JSONObject.NULL)
            .put("stop", stop?.let { JSONArray(listOf(it.number, it.carProgress, it.ghostProgress, it.outBehind?.first ?: JSONObject.NULL, it.outBehind?.second ?: 0)) } ?: JSONObject.NULL)
            .put("circle", encodeCircle(circle)).put("rows", table)
            .put("header", JSONArray(listOf(header.name, header.track, header.color.toArgb(), header.counter, JSONArray(header.weather))))
            .put("message", (timing?.let { noPositionsLine(it, now) } ?: app.mapStatus)?.let(DiagnosticRedaction::redact)?.safe(220) ?: JSONObject.NULL)
    }
    fun decodeFrame(j: JSONObject, table: Table = readTable(j.getJSONArray("rows"))): Frame {
        require(j.getInt("version") == VERSION && j.getString("kind") == "frame")
        val carArray = j.getJSONArray("cars")
        val cars = (0 until carArray.count(MAX_DRIVERS)).map { i -> carArray.getJSONArray(i).let {
            val n = it.getString(0).safe(8); require(n.matches(Regex("[0-9]{1,3}")))
            require(it.length() in 6..(4 + MAX_PREDICTION_STEPS * 2) && it.length() % 2 == 0)
            val later = (6 until it.length() step 2).map { index -> it.f(index) to it.f(index + 1) }
            CarMotion(n, it.f(1), it.f(2), it.f(3), it.f(4), LapState.entries[it.getInt(5)], later)
        } }
        require(cars.map { it.number }.distinct().size == cars.size)
        val yellow = j.getJSONArray("yellow").let { a -> (0 until a.count(64)).associate { i -> a.getJSONArray(i).let { it.getInt(0) to it.getInt(1).also { v -> require(v in 1..2) } } } }
        val resumption = j.optJSONArray("resumption")?.let { Resumption(it.getLong(0), it.getLong(1), it.getString(2).safe()) }
        val stop = j.optJSONArray("stop")?.let { PitStopNow(it.getString(0).safe(8), it.f(1), it.f(2), it.optionalString(3)?.let { n -> n to it.getLong(4) }) }
        val h = j.getJSONArray("header"); val weather = h.getJSONArray(4)
        val header = TowerSessionHeaderData(h.getString(0).safe(), h.getString(1).safe(), Color(h.getInt(2)), h.getString(3).safe(), (0 until weather.count(6)).map { weather.getString(it).safe(32) })
        val position = j.getLong("position").also { require(it in 0..604_800_000L) }
        val utc = j.getLong("utc").also { require(it in 0..4_102_444_800_000L) }
        return Frame(j.getString("geometry").safe(64), position, utc,
            j.f("density", .25f, 10f), j.f("fontScale", .25f, 10f), MapMode.valueOf(j.getString("mapMode")), readPanel(j.opt("map")), readPanel(j.opt("tower")),
            j.f("rotation", -10000f, 10000f), j.f("mapOpacity", 0f, 1f), j.f("dot", 1f, 40f), j.f("label", 1f, 80f),
            j.f("towerOpacity", 0f, 1f), j.f("towerFont", .25f, 5f), j.f("towerHeader", 0f, 10000f), j.getBoolean("tv"),
            j.getString("speedUnit").also { require(it == Settings.MPH || it == Settings.KMH) }, if (j.isNull("onTop")) null else j.getString("onTop").safe(8),
            if (j.isNull("leader")) null else j.getString("leader").safe(8), cars, FlagPicture(TrackCondition.entries[j.getInt("condition")], yellow, resumption),
            stop, decodeCircle(j.optJSONObject("circle")), table, header, if (j.isNull("message")) null else j.getString("message").safe(220))
    }
    private fun encodeCircle(c: PitCircleData?): Any = c?.let {
        JSONObject().put("reference", it.referenceMs).put("ring", JSONArray().apply { it.ring.take(MAX_DRIVERS).forEach { r -> put(JSONArray(listOf(r.car.number, r.car.state.ordinal, r.at, r.distance))) } })
            .put("lane", JSONArray().apply { it.inLane.entries.take(MAX_DRIVERS).forEach { r -> put(JSONArray(listOf(r.key, r.value))) } })
            .put("gaps", JSONObject(it.gaps.mapValues { r -> r.value.safe(24) })).put("chosen", it.chosenNumber ?: JSONObject.NULL).put("ghost", it.ghost ?: JSONObject.NULL)
            .put("near", it.neighbour?.let { r -> JSONArray(listOf(r.first, r.second)) } ?: JSONObject.NULL).put("turn", it.turn)
            .put("laneStart", it.laneStart ?: JSONObject.NULL).put("laneSweep", it.laneSweep ?: JSONObject.NULL)
            .put("corners", JSONArray().apply { it.corners.take(64).forEach { r -> put(JSONArray(listOf(r.first, r.second))) } })
            .put("lap", it.lap ?: JSONObject.NULL).put("green", it.greenCount).put("loss", it.lossMs).put("stops", it.laneStops ?: JSONObject.NULL)
            .put("saved", it.hasSavedLoss).put("toLeader", it.gapsToLeader).put("condition", it.condition.ordinal)
    } ?: JSONObject.NULL
    private fun decodeCircle(j: JSONObject?): PitCircleData? {
        if (j == null) return null
        val ring = j.getJSONArray("ring").let { a -> (0 until a.count(MAX_DRIVERS)).map { i -> a.getJSONArray(i).let { OnCircle(Car(it.getString(0).safe(8), 0f, 0f, LapState.entries[it.getInt(1)]), it.f(2), it.f(3)) } } }
        val lane = j.getJSONArray("lane").let { a -> (0 until a.count(MAX_DRIVERS)).associate { i -> a.getJSONArray(i).let { it.getString(0).safe(8) to it.f(1) } } }
        val gaps = j.getJSONObject("gaps").let { a -> require(a.length() <= MAX_DRIVERS); a.keys().asSequence().associateWith { a.getString(it).safe(24) } }
        fun optionalFloat(key: String) = if (j.isNull(key)) null else j.f(key, 0f, 1f)
        return PitCircleData(j.getLong("reference").also { require(it in 1..600_000) }, ring, lane, gaps,
            if (j.isNull("chosen")) null else j.getString("chosen").safe(8), optionalFloat("ghost"), j.optJSONArray("near")?.let { it.getString(0).safe(8) to it.getLong(1) },
            j.f("turn", -1f, 1f).also { require(it == -1f || it == 1f) }, optionalFloat("laneStart"), optionalFloat("laneSweep"),
            j.getJSONArray("corners").let { a -> (0 until a.count(64)).map { i -> a.getJSONArray(i).let { it.getInt(0) to it.f(1) } } },
            j.optionalInt("lap"), j.getInt("green").coerceIn(0, 100), j.getLong("loss").also { require(it in 0..600_000) }, j.optionalInt("stops"),
            j.getBoolean("saved"), j.getBoolean("toLeader"), TrackCondition.entries[j.getInt("condition")])
    }
}
