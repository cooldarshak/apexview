package app.apexview.cast.local

import app.apexview.VideoQuality
import org.json.JSONArray
import org.json.JSONObject

/** Numeric, bounded metadata only; the receiver owns its own authorized source. */
internal object DirectQualityProtocol {
    private const val MAX_OFFERS = 32
    fun parseHeight(value: Any): VideoQuality {
        require(value is Int) { "Invalid direct quality" }
        return VideoQuality.entries.firstOrNull { it.targetHeight == value }
            ?: throw IllegalArgumentException("Unsupported direct quality")
    }
    fun command(playbackId: String, quality: VideoQuality): JSONObject {
        require(playbackId.isNotBlank() && playbackId.length <= 80)
        return JSONObject().put("action", "directQuality").put("playbackId", playbackId)
            .put("height", quality.targetHeight)
    }
    fun decode(body: JSONObject, playbackId: String): VideoQuality? {
        if (playbackId.isEmpty() || body.optString("playbackId") != playbackId) return null
        require(body.getString("action") == "directQuality")
        return parseHeight(body.get("height"))
    }
    private fun valid(width: Int, height: Int) = width in 1..16384 && height in 1..16384
    fun encodeOffers(offers: List<Pair<Int, Int>>): JSONArray = JSONArray().apply {
        offers.filter { valid(it.first, it.second) }.distinct().take(MAX_OFFERS).forEach { (w, h) ->
            put(JSONArray().put(w).put(h))
        }
    }
    fun decodeOffers(packet: JSONArray): List<Pair<Int, Int>> {
        require(packet.length() <= MAX_OFFERS)
        return (0 until packet.length()).map { index ->
            val pair = packet.getJSONArray(index)
            require(pair.length() == 2)
            val w = pair.get(0); val h = pair.get(1)
            require(w is Int && h is Int && valid(w, h))
            w to h
        }.distinct()
    }
    /** The manual override may choose the highest supported lower rung only if
     * the requested height is absent. No above-cap fallback. */
    fun manualHeight(quality: VideoQuality, offered: List<Pair<Int, Int>>): Int? =
        if (quality == VideoQuality.AUTO) null else offered.asSequence()
            .filter { valid(it.first, it.second) && it.first <= quality.maxWidth && it.second <= quality.targetHeight }
            .maxOfOrNull { it.second }
}
