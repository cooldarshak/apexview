package app.apexview.cast

import android.content.Context
import app.apexview.*
import app.apexview.cast.local.TimingMessageChunks
import org.json.JSONArray
import org.json.JSONObject

/** Samples phone-owned histories; the TV only receives the state near its own video position. */
internal class CastTimingPublisher(private val context: Context, private val app: AppState) {
    private var geometryId = ""
    private var rowsText = ""
    private var rowsId = 0L
    private var rows = JSONArray()
    private var lastTableUtc = Long.MIN_VALUE
    private var lane: PitWindow.PitLane? = null
    private var laneAt = Long.MIN_VALUE
    private var laneTiming: SessionTiming? = null
    private var profileKey: Pair<Long, Long>? = null
    private var profile = LapProfile.EVEN
    private var sequence = 0L
    private var lastPlaybackId = ""
    private var lastFullSnapshotAt = Long.MIN_VALUE
    private var sourceTiming: SessionTiming? = null
    private var geometry: JSONObject? = null
    private var lastFrameAt = Long.MIN_VALUE

    fun messages(playbackId: String): List<JSONObject> {
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (lastPlaybackId == playbackId && lastFrameAt != Long.MIN_VALUE && elapsed - lastFrameAt < 200) return emptyList()
        lastFrameAt = elapsed
        val resend = lastPlaybackId != playbackId || lastFullSnapshotAt == Long.MIN_VALUE || elapsed - lastFullSnapshotAt >= 5_000
        if (lastPlaybackId != playbackId) {
            lastPlaybackId = playbackId
            rowsText = ""
            lastTableUtc = Long.MIN_VALUE
        }
        if (resend) lastFullSnapshotAt = elapsed
        val timing = app.timing
        val position = app.castFramePositionMs
        val now = app.mapTimeAt(position) ?: 0L
        val output = mutableListOf<JSONObject>()
        if (timing != null) {
            // Replay geometry is immutable within a published session snapshot.
            if (timing !== sourceTiming || timing.live && resend) {
                geometry = CastTimingProtocol.geometry(timing)
                sourceTiming = timing
            }
            val currentGeometry = requireNotNull(geometry)
            val id = currentGeometry.getString("id")
            if (id != geometryId) {
                geometryId = id; rowsText = ""; lastTableUtc = Long.MIN_VALUE
                output += JSONObject(currentGeometry.toString()).put("playbackId", playbackId)
            } else if (resend) output += JSONObject(currentGeometry.toString()).put("playbackId", playbackId)
            if (lastTableUtc == Long.MIN_VALUE || kotlin.math.abs(now - lastTableUtc) >= 400) {
                rows = CastTimingProtocol.table(timing, now)
                lastTableUtc = now
            }
        } else { rows = JSONArray(); geometryId = ""; geometry = null; sourceTiming = null }
        val text = rows.toString()
        if (text != rowsText || rowsId == 0L || resend) {
            if (text != rowsText || rowsId == 0L) rowsId++
            rowsText = text
            output += JSONObject().put("version", 1).put("kind", "table").put("playbackId", playbackId)
                .put("id", rowsId).put("rows", rows)
        }
        val basis = if (timing != null && (app.mapMode == MapMode.PIT || app.pitDriver != null)) pitBasis(timing, now) else null
        val cars = if (app.mapMode != MapMode.OFF) timing?.let { carsAt(it, now) }.orEmpty() else emptyList()
        val stop = basis?.let { pitStopNow(it, app.pitDriver, cars.map { car -> car.number to timing!!.geometry.progress(car.x, car.y) }) }
        val circle = if (app.mapMode == MapMode.PIT && timing != null && basis != null) pitCircleData(app, timing, now, basis) else null
        if (resend) android.util.Log.i("PitwallCast", "TIMING_SAMPLE positionMs=$position utc=$now " +
            "rows=${rows.length()} cars=${cars.size} trackStart=${timing?.firstTrackUtc} trackEnd=${timing?.lastTrackUtc}")
        val message = CastTimingProtocol.encodeFrame(geometryId, position, now,
            context.resources.displayMetrics.density, context.resources.configuration.fontScale,
            app, timing, rows, circle, stop, cars)
        message.remove("rows")
        output += message.put("playbackId", playbackId).put("tableId", rowsId).put("sequence", ++sequence)
        return output.flatMap(TimingMessageChunks::split)
    }
    private fun pitBasis(timing: SessionTiming, now: Long): PitBasis {
        val leader = leaderAt(timing, now)
        val laps = leader?.let { timing.laps[it] }
        val green = laps?.lapWindows(now).orEmpty().filter { (a, b) ->
            laps!!.pitStops.none { (i, o) -> i < b && o > a } && timing.flags.at((a + b) / 2).track == TrackCondition.CLEAR
        }.takeLast(PitWindow.REFERENCE_LAPS)
        val reference = green.takeIf { it.isNotEmpty() }?.map { it.second - it.first }?.average()?.toLong()
            ?: laps?.typicalLapMs(now) ?: laps?.at(now)?.lastLap?.time?.let(PitWindow::lapTimeMs)
        val key = green.lastOrNull()
        if (key != profileKey || timing !== laneTiming && !timing.live) {
            profileKey = key
            profile = if (leader != null && key != null) timing.tracks[leader]?.let { track ->
                LapProfile.from((key.first..key.second step 200L).mapNotNull { t ->
                    track.at(t)?.let { (x, y) -> (t - key.first) to timing.geometry.progress(x, y) }
                }, key.second - key.first)
            } ?: LapProfile.EVEN else LapProfile.EVEN
        }
        if (laneAt == Long.MIN_VALUE || kotlin.math.abs(now - laneAt) >= 5_000 || timing !== laneTiming && !timing.live) {
            lane = PitWindow.pitLane(timing); laneAt = now; laneTiming = timing
        }
        val saved = app.savedPitLoss(timing.circuitKey)
        return PitBasis(leader, green, reference, profile, lane, saved, lane?.lossMs ?: saved ?: PitWindow.TYPICAL_LOSS_MS)
    }
}
