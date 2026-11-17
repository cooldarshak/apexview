package app.apexview.cast

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.apexview.cast.local.TimingMessageChunks
import org.json.JSONArray
import org.json.JSONObject

internal class CastTimingReceiver {
    var geometry by mutableStateOf<CastTimingProtocol.Geometry?>(null)
        private set
    var frame by mutableStateOf<CastTimingProtocol.Frame?>(null)
        private set
    private val assembler = TimingMessageChunks.Assembler(SystemClock::elapsedRealtime)
    private var tableId = -1L
    private var rows = JSONArray()
    private var decodedRows: Pair<String, CastTimingProtocol.Table>? = null
    private var lastSequence = -1L
    fun clear() { geometry = null; frame = null; tableId = -1; lastSequence = -1; rows = JSONArray(); decodedRows = null; assembler.clear() }
    fun accept(packet: JSONObject, playbackId: String) {
        val j = assembler.accept(packet) ?: return
        if (j.optString("playbackId") != playbackId) return
        when (j.getString("kind")) {
            "geometry" -> { geometry = CastTimingProtocol.decodeGeometry(j) }
            "table" -> {
                require(j.getInt("version") == 1)
                val incoming = j.getJSONArray("rows"); require(incoming.length() <= 24)
                tableId = j.getLong("id"); rows = incoming
            }
            "frame" -> {
                val sequence = j.getLong("sequence")
                if (sequence <= lastSequence || j.getLong("tableId") != tableId) return
                if (j.getString("geometry").isNotEmpty() && j.getString("geometry") != geometry?.id) return
                j.put("rows", rows)
                val result = CastTimingProtocol.decodeFrame(j, decodedRows)
                decodedRows = result.second; frame = result.first; lastSequence = sequence
            }
        }
    }
}
