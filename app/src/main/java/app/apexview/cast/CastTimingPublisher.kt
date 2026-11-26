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
    private var stoppedDrivers = JSONArray()
    private var lastTableUtc = Long.MIN_VALUE
    private var lastTableElapsed = Long.MIN_VALUE
    private var lastHealthAt = Long.MIN_VALUE
    private var sentFrames = 0L
    private var sentTables = 0L
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
    private var mapSamplerOwner: SessionTiming? = null
    private var mapSampler: OverlayPositionSampler? = null
    private var mapSamplerNative = false
    private var towerSamplerOwner: SessionTiming? = null
    private var towerSampler: OverlayTowerSampler? = null
    private var towerSamplerNative = false

    fun messages(playbackId: String,
        receiverPredictionSteps: Int = CastTimingProtocol.V1_SEND_PREDICTION_STEPS
    ): List<JSONObject> {
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (lastPlaybackId == playbackId && lastFrameAt != Long.MIN_VALUE && elapsed - lastFrameAt < CastTimingProtocol.POSITION_STEP_MS) return emptyList()
        lastFrameAt = elapsed
        val resend = lastPlaybackId != playbackId || lastFullSnapshotAt == Long.MIN_VALUE || elapsed - lastFullSnapshotAt >= 5_000
        if (lastPlaybackId != playbackId) {
            lastPlaybackId = playbackId
            rowsText = ""
            lastTableUtc = Long.MIN_VALUE
            lastTableElapsed = Long.MIN_VALUE
            sentFrames = 0L
            sentTables = 0L
            mapSamplerOwner = null
            mapSampler = null
            towerSamplerOwner = null
            towerSampler = null
        }
        if (resend) lastFullSnapshotAt = elapsed
        val timing = app.timing
        val timingReplaced = timing !== sourceTiming
        val position = app.castFramePositionMs
        val now = app.mapTimeAt(position) ?: 0L
        val output = mutableListOf<JSONObject>()
        var tableSampled = false
        val nativeRequested = app.settings.nativeRustStitching
        val activeTowerTiming = timing.takeIf { app.towerOpen }
        val towerOwnerChanged = activeTowerTiming !== towerSamplerOwner || nativeRequested != towerSamplerNative
        if (towerOwnerChanged) {
            towerSamplerOwner = activeTowerTiming
            towerSamplerNative = nativeRequested
            towerSampler = activeTowerTiming?.let {
                OverlayTowerSampler(it, OverlayPerformanceProbe("pitwall-cast-tower-sample"), nativeRequested)
            }
        }
        if (timing != null) {
            // Replay geometry is immutable within a published session snapshot.
            if (timingReplaced || timing.live && resend) {
                geometry = CastTimingProtocol.geometry(timing)
                sourceTiming = timing
            }
            val currentGeometry = requireNotNull(geometry)
            val id = currentGeometry.getString("id")
            if (id != geometryId) {
                geometryId = id; rowsText = ""; lastTableUtc = Long.MIN_VALUE
                output += JSONObject(currentGeometry.toString()).put("playbackId", playbackId)
            } else if (resend) output += JSONObject(currentGeometry.toString()).put("playbackId", playbackId)
            if (app.towerOpen) {
                // Tower telemetry need not re-serialize up to 24 full rows for every 200ms
                // car-motion packet. 500ms keeps sector/speed/gear updates responsive while
                // leaving the receiver's GPU and main thread time to draw smooth 60Hz dots.
                if (lastTableUtc == Long.MIN_VALUE ||
                    lastTableElapsed == Long.MIN_VALUE ||
                    elapsed < lastTableElapsed ||
                    elapsed - lastTableElapsed >= 500L ||
                    timingReplaced || towerOwnerChanged) {
                    rows = CastTimingProtocol.table(timing, now, requireNotNull(towerSampler).sample(now))
                    stoppedDrivers = CastTimingProtocol.stoppedDrivers(timing, now)
                    tableSampled = true
                    lastTableUtc = now
                    lastTableElapsed = elapsed
                }
            } else if (rows.length() > 0) {
                rows = JSONArray()
                stoppedDrivers = JSONArray()
                tableSampled = true
                lastTableUtc = Long.MIN_VALUE
                lastTableElapsed = Long.MIN_VALUE
            }
        } else {
            tableSampled = rows.length() > 0 || rowsText.isEmpty()
            if (tableSampled) { rows = JSONArray(); stoppedDrivers = JSONArray() }
            geometryId = ""; geometry = null; sourceTiming = null
        }
        val text = if (tableSampled) rows.toString() + stoppedDrivers.toString() else rowsText
        if (text != rowsText || rowsId == 0L || resend) {
            if (text != rowsText || rowsId == 0L) rowsId++
            rowsText = text
            sentTables++
            output += JSONObject().put("version", 1).put("kind", "table").put("playbackId", playbackId)
                .put("id", rowsId).put("rows", rows).put("stoppedDrivers", stoppedDrivers)
        }
        val basis = if (timing != null && (app.mapMode == MapMode.PIT || app.pitDriver != null)) pitBasis(timing, now) else null
        // Exactly the same per-driver cursor and gap rules as the native track
        // map. This is sender-owned; receiver never shares mutable cursor state.
        val activeMapTiming = timing.takeIf { app.mapMode != MapMode.OFF }
        if (activeMapTiming !== mapSamplerOwner || nativeRequested != mapSamplerNative) {
            mapSamplerOwner = activeMapTiming
            mapSamplerNative = nativeRequested
            mapSampler = activeMapTiming?.let {
                OverlayPositionSampler(it, OverlayPerformanceProbe("pitwall-cast-sender-position"), nativeRequested)
            }
        }
        val cars = mapSampler?.sample(now).orEmpty()
        val stop = basis?.let { pitStopNow(it, app.pitDriver, cars.map { car -> car.number to timing!!.geometry.progress(car.x, car.y) }) }
        val circle = if (app.mapMode == MapMode.PIT && timing != null && basis != null)
            pitCircleData(app, timing, now, basis, cars) else null
        if (resend) android.util.Log.i("PitwallCast", "TIMING_SAMPLE positionMs=$position utc=$now " +
            "rows=${rows.length()} cars=${cars.size} trackStart=${timing?.firstTrackUtc} trackEnd=${timing?.lastTrackUtc}")
        val message = CastTimingProtocol.encodeFrame(geometryId, position, now,
            context.resources.displayMetrics.density, context.resources.configuration.fontScale,
            app, timing, rows, circle, stop, cars, receiverPredictionSteps, stoppedDrivers)
        message.remove("rows")
        message.remove("stoppedDrivers")
        output += message.put("playbackId", playbackId).put("tableId", rowsId).put("sequence", ++sequence).put("clockEpoch", app.castTimingEpoch)
        sentFrames++
        val packets = output.flatMap(TimingMessageChunks::split)
        if (lastHealthAt == Long.MIN_VALUE || elapsed < lastHealthAt ||
            elapsed - lastHealthAt >= 5_000L) {
            android.util.Log.i("PitwallCast", "TIMING_TX_HEALTH frames=$sentFrames tables=$sentTables " +
                "rows=${rows.length()} cars=${cars.size} packets=${packets.size} " +
                "tableId=$rowsId epoch=${app.castTimingEpoch} positionMs=$position")
            lastHealthAt = elapsed
        }
        return packets
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
