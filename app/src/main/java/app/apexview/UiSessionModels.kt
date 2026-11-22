package app.apexview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable UI/session models shared by phone, Android TV and Cast.
 * Separated from AppState's async orchestration to keep their persistence and
 * layout behavior individually testable without moving decoder or DRM code.
 */
/** A feed's window on the canvas. Positions and sizes are in pixels. */
class Tile(val id: Int, val contentId: Long, channel: Channel, feed: FeedPlayer) {
    var channel by mutableStateOf(channel)
    var feed by mutableStateOf(feed)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var w by mutableFloatStateOf(640f)
    var h by mutableFloatStateOf(360f)
    var z by mutableFloatStateOf(0f)
    var audible by mutableStateOf(false)
    /** Pinned tiles are never replaced by a car picked on the map. */
    var pinned by mutableStateOf(false)
    /** Showing, but its picture paused while the phone is too hot (sound and place kept). */
    var coolingOff by mutableStateOf(false)
    /** Showing, but its picture paused while sped up, on a device that can't keep every feed smooth. */
    var heldForSpeed by mutableStateOf(false)
    /** TV decoder budget: audio and sync continue, but this tile has no video decoder slot. */
    var heldForDecoder by mutableStateOf(false)
    /** Hidden source tile while its picture is represented inside the composed HEVC grid. */
    var protectedCompositionHidden by mutableStateOf(false)
    /** When this tile last took a car picked on the map (or was added). Oldest goes first. */
    var mapPickedAt = System.currentTimeMillis()

    fun resize(width: Float) {
        w = width.coerceIn(MIN_WIDTH, 4000f)
        h = w * 9f / 16f
    }

    var layoutMotion: kotlinx.coroutines.Job? = null
    var hasLayoutSlot by mutableStateOf(false)
        internal set

    fun place(slot: Slot) {
        layoutMotion?.cancel()
        layoutMotion = null
        x = slot.x
        y = slot.y
        resize(slot.w)
        hasLayoutSlot = true
    }

    companion object {
        const val MIN_WIDTH = 240f
    }
}

enum class MapMode(val label: String) {
    TRACK("Track"),
    EDGE("Edge"),
    /** The lap as a circle, with where a pit stop would put a driver. */
    PIT("Pit"),
    OFF("Off");

    /** Drawn in the map's own window, which the feeds can be kept clear of. */
    val inWindow get() = this == TRACK || this == PIT
}

/**
 * What to offer next while watching [watching] live: something that wasn't live when it was
 * opened ([atStart]), or, once it's gone off air, anything live. Never what's on screen or
 * something already turned down.
 */
internal fun pickNextLive(live: List<CatalogItem>, watching: Long, atStart: Set<Long>, playing: Set<Long>, declined: Set<Long>): CatalogItem? {
    val ended = live.none { it.contentId == watching }
    return live.firstOrNull { item ->
        item.contentId > 0 && item.contentId !in playing && item.contentId !in declined &&
            (ended || item.contentId !in atStart)
    }
}

/**
 * Live timing is a different source from the replay archive. Don't classify an empty or placeholder
 * Media3 timeline as a replay: slower TV devices can publish the real window later than phones.
 * Catalog metadata can positively identify a live session before the player timeline is ready.
 */
internal fun liveTimingSourceFor(
    knownLive: Boolean,
    hasTimeline: Boolean,
    placeholder: Boolean,
    playerReportsLive: Boolean,
): Boolean? = when {
    knownLive -> true
    !hasTimeline || placeholder -> null
    else -> playerReportsLive
}

internal fun catalogTimingSource(
    knownLive: Boolean,
    knownArchive: Boolean,
    completedLive: Boolean,
): Boolean? = when {
    completedLive || knownArchive -> false
    knownLive -> true
    else -> null
}



/**
 * Where a panel over the feeds sits, in canvas pixels: first in the biggest space the feeds
 * leave, and once dragged, kept (as fractions of the screen) for next time. Its size too, once
 * resized by hand; until then its default.
 */
class FloatingSpot(
    private val prefs: android.content.SharedPreferences,
    private val key: String,
    private val perOrientation: Boolean = false,
) {
    var x by mutableFloatStateOf(-1f)
    var y by mutableFloatStateOf(-1f)
    var w by mutableFloatStateOf(-1f)
    var h by mutableFloatStateOf(-1f)

    private fun storageKey(width: Float, height: Float) =
        if (!perOrientation) key else key + if (width > height) "L" else "P"

    /** Canvas fractions for position and optionally size. Older installs used the unsuffixed key. */
    fun saved(width: Float, height: Float): List<Float>? {
        val text = prefs.getString(storageKey(width, height), null) ?: prefs.getString(key, null) ?: return null
        return text.split(',').map { it.toFloatOrNull()?.takeIf { value -> value.isFinite() } ?: return null }
            .takeIf { it.size == 2 || it.size == 4 }
    }

    fun place(area: Area) {
        x = area.x
        y = area.y
        w = area.w
        h = area.h
    }

    fun store(width: Float, height: Float) {
        if (width <= 0f || height <= 0f || x < 0f || y < 0f) return
        val size = if (w > 0f && h > 0f) ",${w / width},${h / height}" else ""
        prefs.edit().putString(storageKey(width, height), "${x / width},${y / height}$size").apply()
    }
}

/** The track map's own window. Any shape; the track is fitted inside it. */
class MapPanel {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var w by mutableFloatStateOf(600f)
    var h by mutableFloatStateOf(600f)
    /** Turn added to the best-fitting angle, in degrees. */
    var rotation by mutableFloatStateOf(0f)
    /** False until placed, by default or from storage. */
    var placed = false

    fun toJson(width: Float, height: Float, opacity: Float, left: Float = 0f, top: Float = 0f): String = JSONObject()
        .put("x", ((x - left) / width).toDouble()).put("y", ((y - top) / height).toDouble())
        .put("w", (w / width).toDouble()).put("h", (h / height).toDouble())
        .put("rotation", rotation.toDouble()).put("opacity", opacity.toDouble())
        .toString()

    /** Puts back a stored panel on a canvas of this size; returns its opacity. */
    fun restore(text: String?, width: Float, height: Float, left: Float = 0f, top: Float = 0f): Float? = runCatching {
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
        val j = JSONObject(text ?: return null)
        // Parse and validate the entire saved geometry before mutating Compose
        // state. A malformed backup must not partially move a live track map
        // to NaN/off-screen coordinates or leave "placed" incorrectly true.
        val nx = left + j.getDouble("x").toFloat() * width
        val ny = top + j.getDouble("y").toFloat() * height
        val nw = j.getDouble("w").toFloat() * width
        val nh = j.getDouble("h").toFloat() * height
        val nr = j.getDouble("rotation").toFloat()
        val opacity = j.getDouble("opacity").toFloat()
        require(listOf(nx, ny, nw, nh, nr, opacity).all(Float::isFinite) &&
            nw > 0f && nh > 0f && opacity in 0f..1f)
        x = nx; y = ny; w = nw; h = nh; rotation = nr; placed = true
        opacity
    }.getOrNull()

    fun place(area: Area) {
        x = area.x
        y = area.y
        w = area.w
        h = area.h
    }

    fun scale(factor: Float) {
        val cx = x + w / 2
        val cy = y + h / 2
        w = (w * factor).coerceIn(MIN_SIZE, 5000f)
        h = (h * factor).coerceIn(MIN_SIZE, 5000f)
        x = cx - w / 2
        y = cy - h / 2
    }

    fun stretch(dx: Float, dy: Float) {
        w = (w + dx).coerceAtLeast(MIN_SIZE)
        h = (h + dy).coerceAtLeast(MIN_SIZE)
    }

    companion object {
        const val MIN_SIZE = 200f
    }
}

/** Where the browse side of the app is. The viewer sits on top whenever feeds are playing. */
sealed interface Screen {
    data object Home : Screen
    /** [artKey] names the card or hero it was opened from, whose artwork grows into the page's. */
    data class Weekend(val weekend: WeekendInfo, val artKey: String? = null) : Screen
    data object Settings : Screen
    data object Calendar : Screen
    /** A show's or documentary's episodes. */
    data class Collection(val pageId: Int, val title: String) : Screen
    /** Popular races from every season. */
    data object Archive : Screen
}

/**
 * One slide of home's carousel: something to watch next and why, a session to pick back up, or
 * a weekend whose schedule is the thing to look at.
 */
data class Pick(val reason: String, val item: CatalogItem? = null, val weekend: WeekendInfo? = null, val resume: SavedWatch? = null)

/** What's been watched of a video: when last, for how long, and whether through to the end. */
data class Seen(val at: Long, val watchedMs: Long, val finished: Boolean)

enum class AuthState { CHECKING, SIGNED_OUT, SIGNED_IN }

/**
 * Enough to put a viewing session back as it was: the session, its feeds (by channel), which
 * one was big, where the audio was, and how far in. Saved to storage, so it survives a crash or
 * a force-close.
 */
data class SavedWatch(
    val contentId: Long,
    val title: String,
    val image: String?,
    val positionMs: Long,
    val durationMs: Long,
    val live: Boolean,
    /** Channel IDs of the feeds, -1 for a single-feed video's only feed. */
    val channels: List<Int>,
    val bigChannel: Int,
    val audioChannel: Int,
    val layoutMode: String,
    val mapMode: String,
    /** Where each feed was, in the order of [channels]; null for sessions saved before 0.16. */
    val layout: SavedLayout? = null,
) {
    fun toJson(): String = toJsonObject().toString()

    fun toJsonObject(): JSONObject = JSONObject()
        .put("contentId", contentId).put("title", title).put("image", image ?: "")
        .put("positionMs", positionMs).put("durationMs", durationMs).put("live", live)
        .put("channels", JSONArray(channels)).put("bigChannel", bigChannel).put("audioChannel", audioChannel)
        .put("layoutMode", layoutMode).put("mapMode", mapMode)
        .put("layout", layout?.toJson() ?: "")

    companion object {
        /** The recently watched list, newest first. */
        fun listFromJson(text: String?): List<SavedWatch> = runCatching {
            val list = JSONArray(text ?: return emptyList())
            (0 until list.length()).mapNotNull { fromJson(list.getJSONObject(it).toString()) }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<SavedWatch>): String = JSONArray(list.map { it.toJsonObject() }).toString()

        fun fromJson(text: String?): SavedWatch? = runCatching {
            val j = JSONObject(text ?: return null)
            val list = j.getJSONArray("channels")
            SavedWatch(
                contentId = j.getLong("contentId"),
                title = j.getString("title"),
                image = j.optString("image").ifEmpty { null },
                positionMs = j.getLong("positionMs"),
                durationMs = j.getLong("durationMs"),
                live = j.getBoolean("live"),
                channels = (0 until list.length()).map { list.getInt(it) },
                bigChannel = j.getInt("bigChannel"),
                audioChannel = j.getInt("audioChannel"),
                layoutMode = j.getString("layoutMode"),
                mapMode = j.getString("mapMode"),
                layout = SavedLayout.fromJson(j.optString("layout").ifEmpty { null }),
            )
        }.getOrNull()
    }
}

