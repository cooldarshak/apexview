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
    private val tableFingerprint = CastTableFingerprint()
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
    private var pitGeometry: TrackGeometry? = null
    private var pitProjection: NativeCircuitProjection? = null
    private var profileKey: Pair<Long, Long>? = null
    private var profile = LapProfile.EVEN
    private var sequence = 0L
    private var lastPlaybackId = ""
    private val publicationEpoch = CastTimingPublicationEpoch()
    private var lastFullSnapshotAt = Long.MIN_VALUE
    private var sourceTiming: SessionTiming? = null
    private var geometry: JSONObject? = null
    private var lastFrameAt = Long.MIN_VALUE
    private var mapSamplerOwner: SessionTiming? = null
    private var mapSampler: OverlayPositionSampler? = null
    private var mapSamplerNative = false
    private var prediction: NativeCastPrediction? = null
    private var lastTowerSample: TowerSample? = null
    private var towerSampler: OverlayTowerSampler? = null
    private var towerSamplerNative = false

    // Dropped batches never leave the receiver permanently referencing an
    // unsent geometry or table. The next permitted sample sends all three.
    fun requestFullRefresh() { lastFullSnapshotAt = Long.MIN_VALUE }

    fun messages(playbackId: String,
        receiverPredictionSteps: Int = CastTimingProtocol.V1_SEND_PREDICTION_STEPS
    ): List<JSONObject> {
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val epoch = app.castTimingEpoch
        val epochChanged = publicationEpoch.requiresSample(epoch)
        // A seek is a new timing generation, not ordinary 200ms motion.
        // Publish the newly sampled table with the next matching frame.
        if (!epochChanged && lastPlaybackId == playbackId && lastFrameAt != Long.MIN_VALUE &&
            elapsed - lastFrameAt < CastTimingProtocol.POSITION_STEP_MS) return emptyList()
        lastFrameAt = elapsed
        val resend = epochChanged || lastPlaybackId != playbackId ||
            lastFullSnapshotAt == Long.MIN_VALUE || elapsed - lastFullSnapshotAt >= 5_000
        if (lastPlaybackId != playbackId) {
            lastPlaybackId = playbackId
            // Never mutate an already published geometry envelope for another load.
            sourceTiming = null
            laneTiming = null
            laneAt = Long.MIN_VALUE
            lane = null
            profileKey = null
            profile = LapProfile.EVEN
            geometry = null
            rowsText = ""
            rows = JSONArray()
            stoppedDrivers = JSONArray()
            lastTableUtc = Long.MIN_VALUE
            lastTableElapsed = Long.MIN_VALUE
            sentFrames = 0L
            sentTables = 0L
            mapSamplerOwner = null
            mapSampler = null
            prediction = null
            lastTowerSample = null
            towerSampler = null
        }
        val timing = app.timing
        val oldTiming = sourceTiming
        val timingReplaced = timing !== oldTiming
        val position = app.castFramePositionMs
        val now = app.mapTimeAt(position) ?: 0L
        val output = mutableListOf<JSONObject>()
        val nativeRequested = app.settings.nativeRustStitching
        val activeTowerTiming = timing.takeIf { app.towerOpen }
        val towerOwnerChanged = if (activeTowerTiming == null) towerSampler != null else
            nativeRequested != towerSamplerNative || towerSampler?.updateSource(activeTowerTiming) != true
        if (towerOwnerChanged) {
            lastTowerSample = null
            towerSamplerNative = nativeRequested
            towerSampler = activeTowerTiming?.let {
                OverlayTowerSampler(it, OverlayPerformanceProbe("pitwall-cast-tower-sample"), nativeRequested)
            }
        }
        if (timing != null) {
            // Replay geometry is immutable within a published session snapshot.
            if (oldTiming == null || geometry == null || timing.live && (timingReplaced || resend) ||
                oldTiming.outline !== timing.outline || oldTiming.drivers !== timing.drivers ||
                oldTiming.corners !== timing.corners || oldTiming.marshalSectors !== timing.marshalSectors ||
                oldTiming.hasStartFinish != timing.hasStartFinish || oldTiming.startLine != timing.startLine ||
                oldTiming.type != timing.type || oldTiming.name != timing.name) {
                geometry = CastTimingProtocol.geometry(timing).put("playbackId", playbackId)
            }
            sourceTiming = timing
            val currentGeometry = requireNotNull(geometry)
            val id = currentGeometry.getString("id")
            if (id != geometryId) {
                geometryId = id; rowsText = ""; lastTableUtc = Long.MIN_VALUE
                output += currentGeometry
            } else if (resend) output += currentGeometry
            if (app.towerOpen) {
                val driverLabelsChanged = oldTiming?.drivers !== timing.drivers
                // Tower telemetry need not re-serialize up to 24 full rows for every 200ms
                // car-motion packet. 500ms keeps sector/speed/gear updates responsive while
                // leaving the receiver's GPU and main thread time to draw smooth 60Hz dots.
                if (epochChanged || lastTableUtc == Long.MIN_VALUE ||
                    lastTableElapsed == Long.MIN_VALUE ||
                    elapsed < lastTableElapsed ||
                    elapsed - lastTableElapsed >= 500L ||
                    driverLabelsChanged || towerOwnerChanged) {
                    val sample = requireNotNull(towerSampler).sample(now)
                    if (sample !== lastTowerSample || driverLabelsChanged || towerOwnerChanged) {
                        rows = CastTimingProtocol.table(timing, now, sample)
                        stoppedDrivers = CastTimingProtocol.stoppedDrivers(timing, now, sample)
                        lastTowerSample = sample
                    }
                    lastTableUtc = now
                    lastTableElapsed = elapsed
                }
            } else if (rows.length() > 0 || stoppedDrivers.length() > 0) {
                rows = JSONArray()
                stoppedDrivers = JSONArray()
                lastTableUtc = Long.MIN_VALUE
                lastTableElapsed = Long.MIN_VALUE
            }
        } else {
            if (rows.length() > 0 || stoppedDrivers.length() > 0) {
                rows = JSONArray(); stoppedDrivers = JSONArray()
            }
            geometryId = ""; geometry = null; sourceTiming = null
        }
        val text = tableFingerprint.text(rows, stoppedDrivers)
        if (text != rowsText || rowsId == 0L || resend) {
            if (text != rowsText || rowsId == 0L) rowsId++
            rowsText = text
            sentTables++
            output += JSONObject().put("version", 1).put("kind", "table").put("playbackId", playbackId)
                .put("id", rowsId).put("clockEpoch", epoch).put("rows", rows).put("stoppedDrivers", stoppedDrivers)
        }
        val needsPit = timing != null && (app.mapMode == MapMode.PIT || app.pitDriver != null)
        val geometryOwner = timing?.geometry.takeIf { nativeRequested && needsPit }
        if (pitGeometry !== geometryOwner) {
            pitGeometry = geometryOwner
            pitProjection = geometryOwner?.let(::NativeCircuitProjection)
        }
        val basis = if (timing != null && needsPit) {
            pitBasis(timing, now)
        } else {
            laneTiming = null
            lane = null
            laneAt = Long.MIN_VALUE
            profileKey = null
            profile = LapProfile.EVEN
            null
        }
        // Exactly the same per-driver cursor and gap rules as the native track
        // map. This is sender-owned; receiver never shares mutable cursor state.
        val activeMapTiming = timing.takeIf { app.mapMode != MapMode.OFF }
        if (activeMapTiming !== mapSamplerOwner || nativeRequested != mapSamplerNative) {
            mapSamplerOwner = activeMapTiming
            mapSamplerNative = nativeRequested
            prediction = if (nativeRequested) activeMapTiming?.let(::NativeCastPrediction) else null
            mapSampler = activeMapTiming?.let {
                OverlayPositionSampler(it, OverlayPerformanceProbe("pitwall-cast-sender-position"), nativeRequested)
            }
        }
        val cars = mapSampler?.sample(now).orEmpty()
        val stop = basis?.let {
            val projected = pitProjection?.project(cars) == true
            pitStopNow(it, app.pitDriver, cars.mapIndexed { index, car ->
                car.number to if (projected) requireNotNull(pitProjection).fractions[index]
                    else requireNotNull(timing).geometry.progress(car.x, car.y)
            })
        }
        val circle = if (app.mapMode == MapMode.PIT && timing != null && basis != null)
            pitCircleData(app, timing, now, basis, cars, pitProjection) else null
        if (resend) android.util.Log.i("PitwallCast", "TIMING_SAMPLE positionMs=$position utc=$now " +
            "rows=${rows.length()} cars=${cars.size} trackStart=${timing?.firstTrackUtc} trackEnd=${timing?.lastTrackUtc}")
        val message = CastTimingProtocol.encodeFrame(geometryId, position, now,
            context.resources.displayMetrics.density, context.resources.configuration.fontScale,
            app, timing, rows, circle, stop, cars, receiverPredictionSteps, stoppedDrivers,
            prediction?.sample(now, receiverPredictionSteps))
        message.remove("rows")
        message.remove("stoppedDrivers")
        output += message.put("playbackId", playbackId).put("tableId", rowsId).put("sequence", ++sequence).put("clockEpoch", epoch)
        sentFrames++
        val packets = output.flatMap(TimingMessageChunks::split)
        // Only a complete encoded batch can retire the forced seek sample.
        // A failed sample/chunk split must still resample on the next attempt.
        publicationEpoch.commit(epoch)
        if (resend) lastFullSnapshotAt = elapsed
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
        fun progress(x: Float, y: Float) = pitProjection?.progress(x, y) ?: timing.geometry.progress(x, y)
        val leader = leaderAt(timing, now)
        val laps = leader?.let { timing.laps[it] }
        val green = laps?.lapWindows(now).orEmpty().filter { (a, b) ->
            laps!!.pitStops.none { (i, o) -> i < b && o > a } && timing.flags.at((a + b) / 2, app.settings.nativeRustStitching).track == TrackCondition.CLEAR
        }.takeLast(PitWindow.REFERENCE_LAPS)
        val reference = green.takeIf { it.isNotEmpty() }?.map { it.second - it.first }?.average()?.toLong()
            ?: laps?.typicalLapMs(now) ?: laps?.at(now)?.lastLap?.time?.let(PitWindow::lapTimeMs)
        val key = green.lastOrNull()
        if (key != profileKey || timing !== laneTiming && !timing.live) {
            profileKey = key
            profile = if (leader != null && key != null) timing.tracks[leader]?.let { track ->
                LapProfile.from((key.first..key.second step 200L).mapNotNull { t ->
                    track.at(t)?.let { (x, y) -> (t - key.first) to progress(x, y) }
                }, key.second - key.first)
            } ?: LapProfile.EVEN else LapProfile.EVEN
        }
        if (laneAt == Long.MIN_VALUE || kotlin.math.abs(now - laneAt) >= 5_000 || timing !== laneTiming && !timing.live) {
            lane = PitWindow.pitLane(timing, ::progress); laneAt = now; laneTiming = timing
        }
        val saved = app.savedPitLoss(timing.circuitKey)
        return PitBasis(leader, green, reference, profile, lane, saved, lane?.lossMs ?: saved ?: PitWindow.TYPICAL_LOSS_MS)
    }
}

/** The table arrays are immutable after publication by CastTimingPublisher.
 * Fingerprint their content once per replacement, not on every unchanged
 * tower sample or resend. Retain only the current pair and its string. */
internal class CastTableFingerprint {
    private var rows: JSONArray? = null
    private var stops: JSONArray? = null
    private var value = ""
    fun text(nextRows: JSONArray, nextStops: JSONArray): String {
        if (rows !== nextRows || stops !== nextStops) {
            value = nextRows.toString() + nextStops.toString()
            rows = nextRows
            stops = nextStops
        }
        return value
    }
}
