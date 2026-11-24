package app.apexview.cast.local

import org.json.JSONObject

/** A stable representation ID scoped to the active receiver load. */
internal object NativeAudioControl {
    fun representationId(index: Int): String {
        require(index in 0..5)
        return "pitwall-audio-$index"
    }
    fun command(loadId: String, index: Int): JSONObject {
        require(loadId.isNotBlank() && loadId.length <= 80)
        representationId(index)
        return JSONObject().put("action", "nativeAudio").put("playbackId", loadId).put("index", index)
    }
    fun decode(body: JSONObject, loadId: String): Int? {
        if (body.optString("action") != "nativeAudio" || loadId.isBlank() ||
            body.optString("playbackId") != loadId) return null
        val index = body.optInt("index", -1)
        return index.takeIf { it in 0..5 }
    }
    fun acknowledgment(loadId: String, index: Int, selected: Boolean): JSONObject =
        command(loadId, index).put("action", "nativeAudioResult").put("selected", selected)
}
