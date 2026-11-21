package app.apexview

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, credential-free snapshots of video positions and both overlay panels.
 * Stored in an Android Auto Backup eligible SharedPreferences file. No stream
 * URLs, F1 tokens, Widevine keys or other account credentials are ever saved.
 */
internal data class NamedLayoutPreset(
    val id: String,
    val name: String,
    val feedCount: Int,
    val landscape: Boolean,
    val layout: SavedLayout,
    val overlaySnapshot: String,
    val createdMs: Long,
) {
    fun toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("feeds", feedCount)
        .put("landscape", landscape)
        .put("layout", JSONObject(layout.toJson()))
        .put("overlay", JSONObject(overlaySnapshot))
        .put("created", createdMs)

    companion object {
        fun parse(json: JSONObject): NamedLayoutPreset? = runCatching {
            val id = json.getString("id").takeIf {
                it.length in 8..80 && it.all { c -> c.isLetterOrDigit() || c == '-' }
            } ?: return null
            val name = json.getString("name").trim().takeIf { NamedLayoutPresets.validName(it) } ?: return null
            val feeds = json.getInt("feeds").takeIf { it in 1..6 } ?: return null
            val layout = SavedLayout.fromJson(json.getJSONObject("layout").toString())
                ?.takeIf { it.boxes.size == feeds && it.aspect.isFinite() && it.aspect > 0f }
                ?: return null
            val landscape = json.getBoolean("landscape")
            if ((layout.aspect > 1f) != landscape) return null
            layout.boxes.forEach { box ->
                require(box.size == 3 && box.all { it.isFinite() } &&
                    box[0] in -1f..3f && box[1] in -1f..3f && box[2] in 0.01f..3f) {
                    "Invalid normalized video window geometry"
                }
            }
            val overlay = json.getJSONObject("overlay").toString()
            require(overlay.length <= 12_000)
            NamedLayoutPreset(id, name, feeds, landscape, layout, overlay,
                json.optLong("created", 0L))
        }.getOrNull()
    }
}

internal object NamedLayoutPresets {
    const val MAX_ITEMS = 50
    fun validName(name: String) = name.trim().length in 1..48 &&
        name.none { it == '\n' || it == '\r' || it.isISOControl() }

    fun parse(data: String?): List<NamedLayoutPreset> = runCatching {
        val root = JSONObject(data ?: return emptyList())
        if (root.optInt("version") != 1) return emptyList()
        val array = root.getJSONArray("presets")
        (0 until minOf(array.length(), MAX_ITEMS)).mapNotNull { i ->
            array.optJSONObject(i)?.let(NamedLayoutPreset::parse)
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    fun encode(items: List<NamedLayoutPreset>): String {
        require(items.size <= MAX_ITEMS && items.map { it.id }.distinct().size == items.size)
        return JSONObject()
            .put("version", 1)
            .put("presets", JSONArray().apply { items.forEach { put(it.toJson()) } })
            .toString()
    }
}
