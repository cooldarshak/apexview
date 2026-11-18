package app.apexview.cast

import android.os.SystemClock
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
    private var table: CastTimingProtocol.Table? = null
    var clockEpoch = 0L
        private set
    private var pendingLegacySeek: Pair<Long, Long>? = null
    private var lastSequence = -1L
    fun clear() {
        geometry = null
        frame = null
        tableId = -1
        table = null
        lastSequence = -1
        clockEpoch = 0
        pendingLegacySeek = null
        assembler.clear()
    }

    fun onSeek(fromMs: Long, toMs: Long) {
        if (fromMs == toMs) return
        clockEpoch++
        pendingLegacySeek = fromMs to toMs
        frame = null
    }
    fun accept(packet: JSONObject, playbackId: String) {
        val j = assembler.accept(packet) ?: return
        if (j.optString("playbackId") != playbackId) return
        when (j.getString("kind")) {
            "geometry" -> {
                if (geometry?.id != j.getString("id")) geometry = CastTimingProtocol.decodeGeometry(j)
            }
            "table" -> {
                require(j.getInt("version") == 1)
                val id = j.getLong("id")
                if (id <= tableId) return
                table = CastTimingProtocol.readTable(j.getJSONArray("rows"))
                tableId = id
            }
            "frame" -> {
                val sequence = j.getLong("sequence")
                if (sequence <= lastSequence || j.getLong("tableId") != tableId) return
                if (j.getString("geometry").isNotEmpty() && j.getString("geometry") != geometry?.id) return
                val currentTable = table ?: return
                if (j.has("clockEpoch")) {
                    if (j.getLong("clockEpoch") != clockEpoch) return
                } else {
                    // Older senders have no seek acknowledgement. Discard updates still at the old playhead.
                    pendingLegacySeek?.let { (from, to) ->
                        val position = j.getLong("position")
                        if (kotlin.math.abs(position - from) < kotlin.math.abs(position - to)) return
                    }
                }
                frame = CastTimingProtocol.decodeFrame(j, currentTable)
                pendingLegacySeek = null
                lastSequence = sequence
            }
        }
    }
}
