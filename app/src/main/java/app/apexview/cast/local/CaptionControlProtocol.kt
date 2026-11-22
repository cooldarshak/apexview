package app.apexview.cast.local

import app.apexview.CaptionPolicy
import org.json.JSONObject

/** Only the viewer preference travels over the local control socket, never DRM keys or cue content. */
internal data class RemoteCaptionPreference(val enabled: Boolean, val language: String)

internal object CaptionControlProtocol {
    fun encode(enabled: Boolean, language: String): JSONObject =
        JSONObject().put("action", "captions").put("enabled", enabled)
            .put("language", CaptionPolicy.normalizeLanguage(language))

    fun decode(body: JSONObject): RemoteCaptionPreference {
        require(body.getString("action") == "captions")
        val raw = body.getString("language")
        require(raw.length <= 16 && raw == CaptionPolicy.normalizeLanguage(raw)) { "Invalid caption language" }
        return RemoteCaptionPreference(body.getBoolean("enabled"), raw)
    }
}