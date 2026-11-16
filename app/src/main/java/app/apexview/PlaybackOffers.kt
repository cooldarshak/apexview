package app.apexview

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** URL hints are used for selection only. Capture verifies the actual response body. */
internal enum class ManifestProtocol { DASH, HLS, UNKNOWN;
    companion object {
        fun fromUrl(url: String): ManifestProtocol {
            val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("").lowercase(Locale.ROOT)
            return when {
                path.endsWith(".mpd") -> DASH
                path.endsWith(".m3u8") -> HLS
                else -> UNKNOWN
            }
        }

        fun fromBody(bytes: ByteArray): ManifestProtocol {
            val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            return when {
                text.startsWith("#EXTM3U") -> HLS
                Regex("<(?:[A-Za-z_][\\w.-]*:)?MPD(?:\\s|>)").containsMatchIn(text) -> DASH
                else -> UNKNOWN
            }
        }
    }
}

/** Never stringify an offer: its URL can contain playback credentials. */
internal class PlaybackOffer(val url: String, val streamType: String, val licenseUrl: String?, val field: String) {
    val protocol get() = ManifestProtocol.fromUrl(url)
}

internal object PlaybackOffers {
    // Only inspect URL-bearing fields within this channel. Root-level/default-channel URLs
    // must never be silently assigned to other feeds. Unknown metadata is not interpreted.
    fun read(channel: JSONObject, streamType: String, licenseUrl: String?): List<PlaybackOffer> {
        val offers = mutableListOf<PlaybackOffer>()
        fun visit(node: JSONObject, path: String, inheritedType: String, inheritedLicense: String?, depth: Int) {
            if (depth > 4) return
            val type = node.optString("streamType").takeUnless { it.isBlank() || it == "null" } ?: inheritedType
            val license = listOf("laURL", "laUrl").firstNotNullOfOrNull { key ->
                node.optString(key).takeUnless { it.isBlank() || it == "null" }
            } ?: inheritedLicense
            for (key in listOf("url", "manifestUrl", "dashUrl", "hlsUrl")) {
                val url = node.optString(key).takeIf { node.opt(key) is String && it.startsWith("https://") }
                if (url != null) offers += PlaybackOffer(url, type, license, "$path.$key")
            }
            for (key in listOf("offers", "variants", "sources", "streams", "urls")) {
                when (val child = node.opt(key)) {
                    is JSONObject -> {
                        visit(child, "$path.$key", type, license, depth + 1)
                        // Named variants, e.g. urls.dash.url, remain scoped to this feed.
                        child.keys().forEach { name ->
                            child.optJSONObject(name)?.let { visit(it, "$path.$key.variant", type, license, depth + 1) }
                        }
                    }
                    is JSONArray -> for (i in 0 until child.length()) {
                        child.optJSONObject(i)?.let { visit(it, "$path.$key[$i]", type, license, depth + 1) }
                    }
                }
            }
        }
        visit(channel, "feed", streamType, licenseUrl, 0)
        return offers.distinctBy { it.url }
    }

    fun preferred(offers: List<PlaybackOffer>, preferDash: Boolean): PlaybackOffer? =
        (if (preferDash) offers.firstOrNull { it.protocol == ManifestProtocol.DASH } else null)
            ?: offers.firstOrNull()

    fun safeLabel(value: String): String = value.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) } ?: "unknown"

    fun diagnostic(platform: String, player: String, contentId: Long, channelId: Int?,
                   reportedType: String, offers: List<PlaybackOffer>, selected: PlaybackOffer?, fallback: Boolean): String =
        "platform=${safeLabel(platform)} player=${safeLabel(player)} content=$contentId channel=${channelId ?: "default"} " +
            "reportedType=${safeLabel(reportedType)} variants=${offers.joinToString { "${it.field}:${it.protocol}:${safeLabel(it.streamType)}" }} " +
            "selected=${selected?.protocol ?: ManifestProtocol.UNKNOWN} field=${selected?.field ?: "none"} fallback=$fallback"
}
