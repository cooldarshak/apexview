package app.apexview.cast

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.apexview.cast.local.TimingMessageChunks
import org.json.JSONObject

internal class CastTimingReceiver {
    var geometry by mutableStateOf<CastTimingProtocol.Geometry?>(null)
        private set
    var frame by mutableStateOf<CastTimingProtocol.Frame?>(null)
        private set
    private val assembler = TimingMessageChunks.Assembler(SystemClock::elapsedRealtime)
    private var tableId = -1L
    /** Independent Compose state: a new tower table must display even when its associated
     * car-motion frame is late, stale or temporarily rejected across a video seek. */
    var currentTable by mutableStateOf<CastTimingProtocol.Table?>(null)
        private set
    private var tablePackets = 0L
    private var framePackets = 0L
    private var rejectedFrames = 0L
    private var rejectedTableVersion = 0L
    private var rejectedClockEpoch = 0L
    private var rejectedGeometry = 0L
    private var rejectedSequence = 0L
    private var aheadTableFramesRecovered = 0L
    private var lastTableReceivedMs = -1L
    private var lastFrameReceivedMs = -1L
    var clockEpoch = 0L
        private set
    private var pendingLegacySeek: Pair<Long, Long>? = null
    private var lastSequence = -1L
    // All state mutation stays on the owner UI scope; this lease prevents a
    // completed background parse from publishing after stop or a seek.
    private var mutationGeneration = 0L
    fun clear() {
        mutationGeneration++
        geometry = null
        frame = null
        tableId = -1
        currentTable = null
        tablePackets = 0L
        framePackets = 0L
        rejectedFrames = 0L
        rejectedTableVersion = 0L
        rejectedClockEpoch = 0L
        rejectedGeometry = 0L
        rejectedSequence = 0L
        aheadTableFramesRecovered = 0L
        lastTableReceivedMs = -1L
        lastFrameReceivedMs = -1L
        lastSequence = -1
        clockEpoch = 0
        pendingLegacySeek = null
        assembler.clear()
    }

    data class Health(
        val framesAccepted: Long,
        val tablesAccepted: Long,
        val framesRejected: Long,
        val tableVersionRejects: Long,
        val clockEpochRejects: Long,
        val geometryRejects: Long,
        val sequenceRejects: Long,
        val aheadTableFramesRecovered: Long,
        val lastFrameAgeMs: Long,
        val lastTableAgeMs: Long,
        val clockEpoch: Long,
    )

    fun health(nowMs: Long = SystemClock.elapsedRealtime()) = Health(
        framePackets, tablePackets, rejectedFrames, rejectedTableVersion,
        rejectedClockEpoch, rejectedGeometry, rejectedSequence,
        aheadTableFramesRecovered,
        if (lastFrameReceivedMs < 0L) -1L else (nowMs - lastFrameReceivedMs).coerceAtLeast(0L),
        if (lastTableReceivedMs < 0L) -1L else (nowMs - lastTableReceivedMs).coerceAtLeast(0L),
        clockEpoch,
    )

    fun onSeek(fromMs: Long, toMs: Long) {
        if (fromMs == toMs) return
        mutationGeneration++
        clockEpoch++
        pendingLegacySeek = fromMs to toMs
        frame = null
    }
    /** Synchronous contract retained for regression tests and lightweight callers. */
    fun accept(packet: JSONObject, playbackId: String) {
        val j = assembler.accept(packet) ?: return
        acceptDecoded(j, playbackId)
    }

    /**
     * Receiver TCP transport awaits this before reading the next packet, keeping
     * geometry, table and motion publication strictly ordered. Decode expensive
     * JSON models on Default rather than freezing TCL Compose/remote input.
     * The original CBCS, geometry, epoch and sequence checks still run on Main.
     */
    suspend fun acceptAsync(packet: JSONObject, playbackId: String) {
        val j = assembler.accept(packet) ?: return
        if (j.optString("playbackId") != playbackId) return
        val lease = mutationGeneration
        var decodedGeometry: CastTimingProtocol.Geometry? = null
        var decodedTable: CastTimingProtocol.Table? = null
        var decodedFrame: CastTimingProtocol.Frame? = null
        when (j.getString("kind")) {
            "geometry" -> {
                if (geometry?.id != j.getString("id")) {
                    decodedGeometry = withContext(Dispatchers.Default) {
                        CastTimingProtocol.decodeGeometry(j)
                    }
                }
            }
            "table" -> {
                require(j.getInt("version") == 1)
                val version = j.getLong("id")
                if (version > tableId || currentTable == null) {
                    decodedTable = withContext(Dispatchers.Default) {
                        CastTimingProtocol.readTable(j.getJSONArray("rows"))
                    }
                }
            }
            "frame" -> {
                val incomingTableId = j.getLong("tableId")
                val canUseTable = incomingTableId == tableId ||
                    (tableId >= 0L && tableId < Long.MAX_VALUE &&
                        incomingTableId == tableId + 1L && currentTable != null)
                val geometryName = j.getString("geometry")
                val validGeometry = geometryName.isEmpty() || geometryName == geometry?.id
                val incomingEpoch = if (j.has("clockEpoch")) j.getLong("clockEpoch") else null
                val validEpoch = incomingEpoch == null ||
                    (incomingEpoch >= 0L && (incomingEpoch == clockEpoch ||
                        CastTimingEpochPolicy.adoptInitialEpoch(
                            framePackets, lastSequence, pendingLegacySeek != null, incomingEpoch)))
                val table = currentTable
                if (j.getLong("sequence") > lastSequence && canUseTable &&
                    validGeometry && validEpoch && table != null) {
                    decodedFrame = withContext(Dispatchers.Default) {
                        CastTimingProtocol.decodeFrame(j, table)
                    }
                }
            }
        }
        // A native player seek or new PLAY can occur while parsing a large
        // tower/geometry packet. Never resurrect the previous playhead on return.
        if (lease != mutationGeneration) return
        acceptDecoded(j, playbackId, decodedGeometry, decodedTable, decodedFrame)
    }

    private fun acceptDecoded(j: JSONObject, playbackId: String,
        decodedGeometry: CastTimingProtocol.Geometry? = null,
        decodedTable: CastTimingProtocol.Table? = null,
        decodedFrame: CastTimingProtocol.Frame? = null,
    ) {
        if (j.optString("playbackId") != playbackId) return
        when (j.getString("kind")) {
            "geometry" -> {
                if (geometry?.id != j.getString("id")) {
                    // Do not draw the previous circuit while the new timing
                    // geometry/frame pair is arriving over the LAN.
                    val next = decodedGeometry ?: CastTimingProtocol.decodeGeometry(j)
                    geometry = next
                    frame = null
                }
            }
            "table" -> {
                require(j.getInt("version") == 1)
                val id = j.getLong("id")
                if (id < tableId) return
                if (id == tableId && currentTable != null) {
                    // Periodic same-version snapshots are valid proof that the
                    // tower transport is alive. Do not reparse or recompose rows.
                    lastTableReceivedMs = SystemClock.elapsedRealtime()
                    return
                }
                val updated = decodedTable ?: CastTimingProtocol.readTable(j.getJSONArray("rows"))
                tableId = id
                currentTable = updated
                tablePackets++
                lastTableReceivedMs = SystemClock.elapsedRealtime()
            }
            "frame" -> {
                val sequence = j.getLong("sequence")
                if (sequence <= lastSequence) {
                    rejectedFrames++; rejectedSequence++; return
                }
                val incomingTableId = j.getLong("tableId")
                // New marker positions should survive a pending tower table
                // packet, but only one generation ahead and only when a
                // previous validated tower table already exists.
                val oneAhead = tableId >= 0L && tableId < Long.MAX_VALUE &&
                    incomingTableId == tableId + 1L && currentTable != null
                if (incomingTableId != tableId && !oneAhead) {
                    rejectedFrames++; rejectedTableVersion++; return
                }
                if (j.getString("geometry").isNotEmpty() && j.getString("geometry") != geometry?.id) {
                    rejectedFrames++; rejectedGeometry++; return
                }
                val acceptedTable = currentTable ?: return
                if (j.has("clockEpoch")) {
                    val incomingEpoch = j.getLong("clockEpoch")
                    if (incomingEpoch < 0L) {
                        rejectedFrames++; rejectedClockEpoch++; return
                    }
                    // A newly connected TV may join a sender that has already
                    // sought. Establish the sender's epoch on the FIRST valid
                    // frame of a new playback only; later frames must match the
                    // receiver's seek-driven epoch and cannot silently rebase.
                    if (CastTimingEpochPolicy.adoptInitialEpoch(
                            framePackets, lastSequence, pendingLegacySeek != null, incomingEpoch
                        ) && incomingEpoch != clockEpoch) {
                        clockEpoch = incomingEpoch
                    }
                    if (incomingEpoch != clockEpoch) {
                        rejectedFrames++; rejectedClockEpoch++; return
                    }
                } else {
                    // Older senders have no seek acknowledgement. Discard updates still at the old playhead.
                    pendingLegacySeek?.let { (from, to) ->
                        val position = j.getLong("position")
                        if (kotlin.math.abs(position - from) < kotlin.math.abs(position - to)) return
                    }
                }
                frame = decodedFrame ?: CastTimingProtocol.decodeFrame(j, acceptedTable)
                if (oneAhead) aheadTableFramesRecovered++
                pendingLegacySeek = null
                lastSequence = sequence
                framePackets++
                lastFrameReceivedMs = SystemClock.elapsedRealtime()
            }
        }
    }
}
