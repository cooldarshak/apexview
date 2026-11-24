package app.apexview.cast.local

import app.apexview.CaptionAppearance
import app.apexview.CaptionPolicy
import app.apexview.CaptionTrackOption
import org.json.JSONArray
import org.json.JSONObject

/** Only the viewer preference travels over the local control socket, never DRM keys or cue content. */
internal data class RemoteCaptionPreference(
    val enabled: Boolean, val language: String, val appearance: CaptionAppearance? = null,
)

internal object CaptionControlProtocol {
    fun encode(enabled: Boolean, language: String, appearance: CaptionAppearance? = null): JSONObject =
        JSONObject().put("action", "captions").put("enabled", enabled)
            .put("language", CaptionPolicy.normalizeLanguage(language)).apply {
                if (appearance != null) put("appearance", JSONObject()
                    .put("textSize", appearance.textSizePercent)
                    .put("backgroundOpacity", appearance.backgroundOpacityPercent ?: -1))
            }

    fun forLoad(
        enabled: Boolean, language: String, playbackId: String, appearance: CaptionAppearance? = null,
    ): JSONObject {
        require(playbackId.isNotBlank() && playbackId.length <= 80)
        return encode(enabled, language, appearance).put("playbackId", playbackId)
    }

    fun matchesLoad(body: JSONObject, playbackId: String, requireId: Boolean): Boolean =
        if (body.has("playbackId")) playbackId.isNotEmpty() && body.optString("playbackId") == playbackId
        else !requireId

    fun decode(body: JSONObject): RemoteCaptionPreference {
        require(body.getString("action") == "captions")
        val raw = body.getString("language")
        require(raw.length <= 16 && raw == CaptionPolicy.normalizeLanguage(raw)) { "Invalid caption language" }
        val appearance = if (body.has("appearance")) body.getJSONObject("appearance").let { style ->
            val size = style.get("textSize")
            val opacity = style.get("backgroundOpacity")
            require(size is Int && opacity is Int) { "Invalid caption appearance" }
            CaptionAppearance(size, opacity.takeUnless { it == -1 })
        } else null
        return RemoteCaptionPreference(body.getBoolean("enabled"), raw, appearance)
    }
}
/**
 * The receiver owns the actual authorized media manifest. Advertise only
 * bounded, supported track labels back to the phone's player CC menu.
 * No text cues, stream links, credentials or protection metadata cross LAN.
 */
internal object CaptionTrackProtocol {
    private const val MAX_TRACKS = 16
    private const val MAX_LABEL_CHARS = 64

    fun encode(tracks: List<CaptionTrackOption>): JSONArray = JSONArray().apply {
        tracks.distinctBy { it.language }.take(MAX_TRACKS).forEach { track ->
            val language = CaptionPolicy.normalizeLanguage(track.language)
            require(language != CaptionPolicy.AUTO) { "Caption track has no valid language" }
            put(JSONArray(listOf(language, track.label.take(MAX_LABEL_CHARS))))
        }
    }

    fun decode(packet: JSONArray): List<CaptionTrackOption> {
        require(packet.length() <= MAX_TRACKS) { "Too many caption tracks" }
        return (0 until packet.length()).map { index ->
            val entry = packet.getJSONArray(index)
            require(entry.length() == 2)
            val language = entry.getString(0)
            val label = entry.getString(1)
            require(language != CaptionPolicy.AUTO && language == CaptionPolicy.normalizeLanguage(language))
            require(label.isNotBlank() && label.length <= MAX_LABEL_CHARS)
            CaptionTrackOption(language, label)
        }.distinctBy { it.language }
    }
}
