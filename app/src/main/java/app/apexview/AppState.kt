package app.apexview

import androidx.media3.common.C
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.AndroidUiDispatcher
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.lazy.LazyListState
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Calendar
import kotlin.math.abs
import app.apexview.tiled.ProtectedHevcLiveComposition
import app.apexview.tiled.F1DashFeedCapture
import app.apexview.tiled.ArchiveCompositionStartup
import app.apexview.tiled.F1ArchiveFeedCapture
import app.apexview.tiled.CmafCaptureLifecycle
import app.apexview.tiled.ArchiveBufferPolicy

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

    fun place(slot: Slot) {
        x = slot.x
        y = slot.y
        resize(slot.w)
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
class FloatingSpot(private val prefs: android.content.SharedPreferences, private val key: String) {
    var x by mutableFloatStateOf(-1f)
    var y by mutableFloatStateOf(-1f)
    var w by mutableFloatStateOf(-1f)
    var h by mutableFloatStateOf(-1f)

    /** The place, and size if it's been resized, as fractions of the screen: x, y and optionally w, h. */
    fun saved(): List<Float>? =
        prefs.getString(key, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size >= 2 }

    fun store(width: Float, height: Float) {
        val size = if (w > 0f && h > 0f) ",${w / width},${h / height}" else ""
        prefs.edit().putString(key, "${x / width},${y / height}$size").apply()
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

    fun toJson(width: Float, height: Float, opacity: Float): String = JSONObject()
        .put("x", (x / width).toDouble()).put("y", (y / height).toDouble())
        .put("w", (w / width).toDouble()).put("h", (h / height).toDouble())
        .put("rotation", rotation.toDouble()).put("opacity", opacity.toDouble())
        .toString()

    /** Puts back a stored panel on a canvas of this size; returns its opacity. */
    fun restore(text: String?, width: Float, height: Float): Float? = runCatching {
        val j = JSONObject(text ?: return null)
        x = j.getDouble("x").toFloat() * width
        y = j.getDouble("y").toFloat() * height
        w = j.getDouble("w").toFloat() * width
        h = j.getDouble("h").toFloat() * height
        rotation = j.getDouble("rotation").toFloat()
        placed = true
        j.getDouble("opacity").toFloat()
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

private fun Channel.key() = channelId ?: -1

/** The sheet for choosing which of a session's feeds to watch. */
/** [live]: on air now, so the sheet asks whether to start live or from the beginning. */
class FeedPicker(val contentId: Long, val title: String, val live: Boolean = false) {
    var channels by mutableStateOf<List<Channel>?>(null)
    var error by mutableStateOf<String?>(null)
    /** New feeds to start. */
    val selected = mutableStateListOf<Channel>()
    /** Feeds on screen that were unpicked, to make room. */
    val removing = mutableStateListOf<Channel>()
    /** Shown under the title when a pick doesn't fit. */
    var note by mutableStateOf<String?>(null)
}

class AppState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val userAgent: String,
    private val chromeMajor: String,
    private val openLogin: () -> Unit,
) {
    private var api = F1TvApi(userAgent, chromeMajor)
    private var f1DashCaptureJob: Job? = null
    var singleDecoderDiagnostic by mutableStateOf<String?>(null)
        private set
    val observedActiveVideoDecoders get() = tiles.sumOf { it.feed.activeVideoDecoderCount }
    private var f1DashCapture: CmafCaptureLifecycle? = null
    private var protectedCompositionHost: Tile? = null
    private var protectedCompositionAudioMaster: Tile? = null
    private var nativeArchiveTiles by mutableStateOf<List<Tile>>(emptyList())
    val settings = Settings(context)
    var decoderMode by mutableStateOf(settings.decoderMode)
        private set
    private var archiveCompositionStarting by mutableStateOf(false)
    var singleDecoderFailure by mutableStateOf<String?>(null)
        private set
    private var singleDecoderRetryJob: Job? = null
    val singleDecoderSceneActive: Boolean get() = nativeArchiveTiles.isNotEmpty()
    val singleDecoderScenePlayer: FeedPlayer? get() = if (singleDecoderSceneActive) protectedCompositionHost?.feed else null
    var castBusy by mutableStateOf(false)
        private set
    var googleCastAvailable by mutableStateOf(false)
        private set
    var castStatus by mutableStateOf<String?>(null)
        private set
    private var castRelay by mutableStateOf<app.apexview.cast.SceneCastRelay?>(null)
    private var googleCastRemoteClient: com.google.android.gms.cast.framework.media.RemoteMediaClient? = null
    private var googleCastSession: com.google.android.gms.cast.framework.CastSession? = null
    private var castSceneJob: Job? = null
    private var castSceneConfirmed = false
    private val usesLayoutCastReceiver get() = BuildConfig.CAST_RECEIVER_APP_ID.isNotEmpty()
    private var googleCastLoadedUrl: String? = null
    private var castPreparationJob: Job? = null
    private var castPreparationGeneration = 0L
    private var castRetryAfter = 0L
    private val retiredCastRelays = mutableListOf<app.apexview.cast.SceneCastRelay>()
    val castingActive: Boolean get() = castRelay != null
    var castPickerOpen by mutableStateOf(false)
    internal var localCastDevices by mutableStateOf<List<app.apexview.cast.local.PitwallDevice>>(emptyList())
        private set
    var localCastDiscoveryStatus by mutableStateOf("Looking for Pitwall TVs…")
        private set
    private var localDiscovery: app.apexview.cast.local.PitwallDiscovery? = null
    private var localConnection: app.apexview.cast.local.FcastConnection? = null
    private var localConnectionJob: Job? = null
    private var localConnectionGeneration = 0L
    private var localReady = false
    private var localTimingSupported = false
    internal var castTowerBounds: app.apexview.cast.CastTimingProtocol.Panel? = null
        private set
    internal fun reportCastTowerBounds(x: Float, y: Float, width: Float, height: Float) {
        castTowerBounds = app.apexview.cast.CastTimingProtocol.Panel(x, y, width, height)
    }
    private var localLoadId = ""
    private var localChosen = false
    var castDeviceName by mutableStateOf("TV")
        private set
    var castPlaybackHandedOff by mutableStateOf(false)
        private set
    private val remoteCastClock = app.apexview.cast.local.RemotePlaybackClock { android.os.SystemClock.elapsedRealtime() }
    private var castRemoteBuffering = false
    private var googleHasPlayed = false
    private var googleLoading = false
    private var googleCastSelected = true
    private val googleRemoteCallback = object : com.google.android.gms.cast.framework.media.RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            if (!castPlaybackHandedOff || localChosen || googleLoading) return
            val remote = googleCastRemoteClient ?: return
            if (remote.isPlaying || remote.isPaused) googleHasPlayed = true
            if (googleHasPlayed && remote.mediaStatus?.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE) {
                remoteCastClock.update(remote.approximateStreamPosition, false, playbackSpeed, remote.streamDuration)
                if (remote.mediaStatus?.idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED) playing = false
                googleCastSelected = false
                releaseCastRelay()
                castStatus = "TV playback ended. Playback returned to this device."
                return
            }
            playing = remote.isPlaying || remote.isBuffering
            castRemoteBuffering = remote.isBuffering
            if (remote.streamDuration > 0) durationMs = remote.streamDuration
            remoteCastClock.update(remote.approximateStreamPosition, remote.isPlaying, playbackSpeed, remote.streamDuration)
        }
    }

    fun openCastPicker() { castPickerOpen = true }
    internal fun startLocalDiscovery() {
        localDiscovery?.close()
        localCastDevices = emptyList(); localCastDiscoveryStatus = "Looking for Pitwall TVs…"
        localDiscovery = app.apexview.cast.local.PitwallDiscovery(context, { devices ->
            localCastDevices = devices
            localCastDiscoveryStatus = if (devices.isEmpty()) "Open Settings → Receive from phone on the TV. Both devices need the same local network."
                else "Choose a TV. Allow the connection on its screen."
        }, { error -> localCastDiscoveryStatus = error }).also { it.start() }
    }
    internal fun stopLocalDiscovery() { localDiscovery?.close(); localDiscovery = null }
    fun openTvReceiver() {
        stopAll()
        context.startActivity(Intent(context, app.apexview.cast.local.PitwallReceiverActivity::class.java))
    }
    internal var externalDisplayRequested by mutableStateOf(false)
        private set
    internal var externalDisplayActive by mutableStateOf(false)
        private set
    internal var externalDisplayName by mutableStateOf("Wireless display")
        private set
    internal var stopExternalDisplay: () -> Unit = {}
    private var externalRequestDeadline = 0L
    internal fun reportExternalDisplayStatus(text: String) { message = text }
    internal fun setExternalDisplay(active: Boolean, name: String = externalDisplayName) {
        externalDisplayActive = active; externalDisplayName = name
    }
    internal fun clearExternalDisplayRequest() {
        externalDisplayRequested = false; externalDisplayActive = false
        tiles.forEach { it.feed.setExternalQualityFloor(false) }
    }
    internal fun externalDisplayQualityReady(): Boolean {
        val native = singleDecoderScenePlayer?.archiveSceneSession
        if (native != null) return native.sourceDisplayHeight >= 720
        return tiles.isNotEmpty() && tiles.all { it.feed.player.videoFormat?.height?.let { h -> h >= 720 } == true }
    }
    internal fun requestExternalDisplayPlayback() {
        if (tiles.isEmpty()) { message = "Open the feeds before connecting a wireless display."; return }
        externalDisplayRequested = true
        externalRequestDeadline = android.os.SystemClock.elapsedRealtime() + 120_000L
        val native = singleDecoderScenePlayer?.archiveSceneSession
        if (native == null) tiles.forEach { it.feed.setExternalQualityFloor(true) }
        else if (native.sourceDisplayHeight < 720) tiles.firstOrNull()?.let { startArchiveSingleDecoderComposition(it.contentId) }
        scope.launch {
            kotlinx.coroutines.delay(120_000)
            if (externalDisplayRequested && !externalDisplayActive && android.os.SystemClock.elapsedRealtime() >= externalRequestDeadline) clearExternalDisplayRequest()
        }
    }
    fun openMiracast() {
        castPickerOpen = false
        stopFullSceneCast()
        requestExternalDisplayPlayback()
        message = if (app.apexview.cast.local.Miracast.open(context)) {
            "Choose your TV in wireless display controls. If Android exposes a separate TV display, Pitwall moves the picture there and leaves this phone as the controller."
        } else {
            "Open Quick Settings → Smart View and choose the TV. Pitwall uses a separate TV display when Samsung exposes one; ordinary mirroring otherwise keeps the phone picture visible."
        }
    }
    /** Called immediately before opening Google's standard route dialog. */
    internal fun chooseGoogleCast() {
        stopFullSceneCast()
        localChosen = false
        googleCastSelected = true
        castPickerOpen = false
    }
    internal fun connectLocalCast(device: app.apexview.cast.local.PitwallDevice) {
        stopFullSceneCast()
        val attempt = ++localConnectionGeneration
        localChosen = true; localReady = false; castDeviceName = device.name
        castPickerOpen = false; castBusy = true
        castStatus = "Connecting to ${device.name}…"; message = castStatus
        localConnectionJob = scope.launch {
            val socket = java.net.Socket()
            var assigned = false
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    socket.connect(java.net.InetSocketAddress(device.address, device.port), 6_000)
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (attempt != localConnectionGeneration || !localChosen) return@launch
                lateinit var next: app.apexview.cast.local.FcastConnection
                next = app.apexview.cast.local.FcastConnection(scope, socket, { packet ->
                    if (localConnection === next) receiveLocalCast(packet)
                }, { reason ->
                    if (localConnection === next) {
                        localConnection = null; localReady = false
                        castPreparationJob?.cancel(); castPreparationJob = null
                        releaseCastRelay(); castBusy = false
                        castStatus = "TV disconnected ($reason). Choose the TV again to reconnect."; message = castStatus
                    }
                })
                localConnection = next; assigned = true; next.start()
                next.send(app.apexview.cast.local.FcastProtocol.VERSION, JSONObject().put("version", 3))
                kotlinx.coroutines.delay(8_000)
                if (!localReady && localConnection === next) {
                    next.close()
                    castStatus = "The TV did not confirm Pitwall layout support. Reopen Receive from phone on the TV."; message = castStatus
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (attempt == localConnectionGeneration) {
                    castBusy = false; castStatus = "Unable to connect to the TV: ${error.javaClass.simpleName}"; message = castStatus
                }
            } finally {
                if (!assigned) runCatching { socket.close() }
                if (attempt == localConnectionGeneration) localConnectionJob = null
            }
        }
    }
    private fun receiveLocalCast(packet: app.apexview.cast.local.FcastProtocol.Packet) {
        val body = packet.body
        val protocol = app.apexview.cast.local.FcastProtocol
        try {
            when (packet.opcode) {
                protocol.VERSION -> {
                    require(body != null && body.getInt("version") >= 3) { "Receiver does not support the Pitwall FCast connection" }
                    localConnection?.send(protocol.INITIAL, JSONObject().put("displayName", android.os.Build.MODEL)
                        .put("appName", "Pitwall TV").put("appVersion", BuildConfig.VERSION_NAME).put("pitwallScene", 1).put("pitwallOverlays", 1))
                }
                protocol.INITIAL -> {
                    require(body?.optInt("pitwallScene") == 1) { "This receiver cannot arrange Pitwall video windows" }
                    if (!localReady) {
                        localTimingSupported = body?.optInt("pitwallOverlays") == 1
                        localReady = true; castBusy = false
                        startFullSceneCast()
                    }
                }
                protocol.UI_CONTROL -> if (localTimingSupported && body != null) {
                    val number = body.optString("number")
                    require(number.matches(Regex("[0-9]{1,3}")))
                    when (body.optString("action")) {
                        "driver" -> showOnboard(number)
                        "pitDriver" -> pitDriver = if (pitDriver == number) null else number
                    }
                }
                protocol.TIMING -> if (localTimingSupported && body?.optString("kind") == "drawn" && body.optString("playbackId") == localLoadId) {
                    android.util.Log.i("PitwallCast", "TIMING_UI_CONFIRMED rows=${body.optInt("rows")} cars=${body.optInt("cars")}")
                    castStatus = "Playing on $castDeviceName with the phone video layout and timing/map UI."
                }
                protocol.PING -> localConnection?.send(protocol.PONG)
                protocol.RENDERING -> {
                    if (body?.optString("playbackId") != localLoadId || castRelay == null) return
                    castSceneConfirmed = true
                    handOffCastPlayback()
                    castStatus = "Playing on $castDeviceName in the phone video layout. ${castRelay?.session?.sourceDisplayHeight ?: 720}p sources."
                    message = castStatus
                }
                protocol.PLAYBACK_UPDATE -> if (localReady && body != null) {
                    val state = body.optInt("state")
                    val time = body.optDouble("time", 0.0)
                    val duration = body.optDouble("duration", 0.0)
                    val speed = body.optDouble("speed", playbackSpeed.toDouble())
                    require(time.isFinite() && duration.isFinite() && time in 0.0..604800.0 && duration in 0.0..604800.0 && speed.isFinite() && speed in 0.25..4.0)
                    if (state == 0 && castPlaybackHandedOff) {
                        remoteCastClock.update((time * 1000).toLong(), false, speed.toFloat(), (duration * 1000).toLong())
                        if (body.optBoolean("finished")) playing = false
                        releaseCastRelay(); castStatus = "TV playback stopped. Playback returned to this device."
                    } else if (state in 1..2) {
                        if (duration > 0) durationMs = (duration * 1000).toLong()
                        castRemoteBuffering = body.optBoolean("buffering")
                        remoteCastClock.update((time * 1000).toLong(), state == 1 && !castRemoteBuffering, speed.toFloat(), (duration * 1000).toLong())
                        if (castPlaybackHandedOff) { playing = state == 1; positionMs = remoteCastClock.positionMs() }
                    }
                }
                protocol.ERROR -> {
                    castPreparationJob?.cancel(); castPreparationJob = null
                    releaseCastRelay(); castBusy = false
                    castStatus = DiagnosticRedaction.redact(body?.optString("message") ?: "TV playback failed").take(220)
                    message = castStatus
                }
            }
        } catch (error: Exception) {
            castStatus = DiagnosticRedaction.redact(error.message.orEmpty()).take(220); message = castStatus
            localConnection?.close()
        }
    }
    private fun loadLocalCastMedia(relay: app.apexview.cast.SceneCastRelay) {
        val link = localConnection ?: throw IOException("TV disconnected during preparation")
        val scene = castVideoScene(relay.session) ?: throw IOException("The phone layout is not ready. Keep the player open and retry casting.")
        val time = castFramePositionMs.coerceAtLeast(0L)
        localLoadId = java.util.UUID.randomUUID().toString()
        remoteCastClock.update(time, playing, playbackSpeed, relay.session.durationUs / 1000)
        link.send(app.apexview.cast.local.FcastProtocol.PLAY, JSONObject()
            .put("container", "application/x-mpegURL").put("url", relay.googleCastMediaUrl)
            .put("time", time / 1000.0).put("speed", playbackSpeed).put("volume", if (mutedAll) 0 else 1)
            .put("metadata", JSONObject().put("type", 0).put("title", "Pitwall TV")
                .put("custom", JSONObject().put("pitwallScene", scene).put("playbackId", localLoadId))))
        if (!playing) link.send(app.apexview.cast.local.FcastProtocol.PAUSE)
        castStatus = "Allow playback on the TV. Waiting for its first rendered frame…"; message = castStatus
        startCastSceneUpdates(relay)
    }
    private fun handOffCastPlayback() {
        retiredCastRelays.forEach { it.close() }; retiredCastRelays.clear()
        if (castPlaybackHandedOff || castRelay == null) return
        if (!localChosen) remoteCastClock.update(castFramePositionMs, playing, playbackSpeed, castRelay!!.session.durationUs / 1000)
        castPlaybackHandedOff = true
        tiles.forEach { it.feed.suspendForRemotePlayback(true) }
        radioPlayer?.pause()
        android.util.Log.i("PitwallCast", "LOCAL_PLAYBACK_STOPPED transport=${if (localChosen) "FCast" else "GoogleCast"} feeds=${tiles.size}")
    }
    private fun returnCastPlayback() {
        if (!castPlaybackHandedOff) return
        val target = remoteCastClock.positionMs()
        castPlaybackHandedOff = false
        tiles.forEach {
            it.feed.suspendForRemotePlayback(false)
            it.feed.player.seekTo(target)
            it.feed.player.playWhenReady = playing && (!singleDecoderSceneActive || it === protectedCompositionHost)
        }
        applyVideoBudget()
        if (singleDecoderSceneActive) {
            tiles.firstOrNull { it.audible }?.let { singleDecoderScenePlayer?.selectArchiveAudio(it.feed.sourceStream.profileLabel) }
            singleDecoderScenePlayer?.setAudible(!mutedAll)
        } else if (settings.decoderMode == DecoderMode.SINGLE && tiles.size >= 2) {
            tiles.firstOrNull()?.let { startArchiveSingleDecoderComposition(it.contentId) }
        } else tiles.forEach { it.feed.setAudible(it.audible && !mutedAll) }
    }

    fun showGoogleCastStatus() {
        message = if (googleCastAvailable) {
            "Choose a Google Cast device from the Cast button in the player."
        } else {
            "Google Cast is unavailable here. Check that Google Play services is installed and updated."
        }
    }
    fun updateGoogleCastAvailability(available: Boolean) {
        googleCastAvailable = available
        if (!available) googleCastRemoteClient = null
    }
    fun onGoogleCastSessionAvailable(session: com.google.android.gms.cast.framework.CastSession) {
        if (localChosen || !googleCastSelected) return
        googleCastSession = session
        googleCastRemoteClient?.unregisterCallback(googleRemoteCallback)
        googleCastRemoteClient = session.remoteMediaClient
        googleCastRemoteClient?.registerCallback(googleRemoteCallback)
        castDeviceName = session.castDevice?.friendlyName ?: "Google Cast TV"
        if (googleCastRemoteClient == null) return
        if (usesLayoutCastReceiver) runCatching {
            session.setMessageReceivedCallbacks(app.apexview.cast.CastSceneProtocol.NAMESPACE) { _, _, text ->
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return@setMessageReceivedCallbacks
                when (event.optString("type")) {
                    "rendering" -> {
                        castSceneConfirmed = true
                        castStatus = "TV receiver is rendering the phone video layout. Sources are at least 720p."
                        android.util.Log.i("PitwallCast", "SCENE_RENDERING_CONFIRMED sources=${event.optInt("sources")}")
                    }
                    "error" -> {
                        castStatus = "TV layout receiver: " + event.optString("message").take(180)
                        message = castStatus
                        android.util.Log.w("PitwallCast", DiagnosticRedaction.redact(castStatus.orEmpty()))
                    }
                }
            }
        }.onFailure { android.util.Log.w("PitwallCast", "Scene channel registration failed: ${it.javaClass.simpleName}") }
        val relay = castRelay
        if (relay != null) loadGoogleCastMedia(relay) else startFullSceneCast()
    }
    fun onGoogleCastSessionEnded() {
        googleCastRemoteClient?.unregisterCallback(googleRemoteCallback)
        googleCastSession = null
        googleCastRemoteClient = null
        if (localChosen) return
        castPreparationJob?.cancel(); castPreparationJob = null
        releaseCastRelay()
        castBusy = false
        castStatus = "Google Cast disconnected."
    }
    fun onGoogleCastStartFailed(reason: String) {
        castStatus = reason.take(220)
        message = castStatus
    }
    fun startFullSceneCast() = prepareFullSceneCast(false)
    fun retryFullSceneCast() = prepareFullSceneCast(castingActive)
    private fun prepareFullSceneCast(replace: Boolean) {
        if ((castingActive && !replace) || castBusy) return
        if (localChosen && !localReady) { message = "Choose the TV again to reconnect."; return }
        if (!localChosen && googleCastRemoteClient == null) { message = "Choose a Cast device first."; return }
        if (tiles.isEmpty()) {
            castStatus = "Open an authorized archive session before casting."
            message = castStatus
            return
        }
        val castTiles = tiles.toList()
        if (castTiles.map { it.contentId }.distinct().size != 1) {
            castStatus = "Casting needs feeds from one archive session so they can be composed on one shared timeline."
            message = castStatus
            return
        }
        if (castTiles.any { it.contentId in knownLiveContentIds || it.feed.player.isCurrentMediaItemLive }) {
            castStatus = "Direct casting currently supports clear archive replays. Protected/live feeds need legitimate receiver-side Widevine support."
            message = castStatus
            return
        }
        val clockTile = castTiles.firstOrNull { it.id == bigTileId }
            ?: castTiles.firstOrNull { it.audible }
            ?: castTiles.first()
        castBusy = true; castStatus = "Preparing ${castTiles.size} feed${if (castTiles.size == 1) "" else "s"} for the TV…"
        message = castStatus
        val generation = ++castPreparationGeneration
        castRetryAfter = android.os.SystemClock.elapsedRealtime() + 5_000L
        castPreparationJob = scope.launch {
            var relay: app.apexview.cast.SceneCastRelay? = null
            var ownedSession: app.apexview.tiled.HevcArchiveVodSession? = null
            try {
                val expectedLabels = castTiles.map { it.feed.sourceStream.profileLabel }.toSet()
                val activeComposition = singleDecoderScenePlayer?.archiveSessionForCast()
                    ?.takeIf { it.sourceLabels.toSet() == expectedLabels && it.sourceLabels.size == expectedLabels.size && it.sourceDisplayHeight >= 720 }
                val session = activeComposition ?: run {
                    val capture = F1ArchiveFeedCapture(userAgent, api.ascendon.orEmpty(), api.entitlement.orEmpty())
                    try {
                        capture.prepareVod(
                            castTiles.map { F1DashFeedCapture.Feed(it.feed.sourceStream.profileLabel, it.feed.sourceStream.url) },
                            castFramePositionMs.coerceAtLeast(0),
                            clockTile.feed.sourceStream.profileLabel,
                            preferredHeight = if (settings.videoQuality == VideoQuality.P1080) 1080 else 720,
                            commentary = settings.commentary,
                            minimumHeight = 720,
                        ).also { ownedSession = it }
                    } finally { capture.reset() }
                }
                require(session.sourceDisplayHeight >= 720) { "Cast source quality is below 720p. No lower-quality stream was sent." }
                android.util.Log.i("PitwallCast", "CAST_SOURCE_QUALITY minimum=720 actual=${session.sourceDisplayWidth}x${session.sourceDisplayHeight} feeds=${session.sourceLabels.size} atlas=${session.width}x${session.height} decoderMode=$decoderMode layoutReceiver=$usesLayoutCastReceiver")
                val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
                val network = manager.allNetworks.firstOrNull { network -> manager.getNetworkCapabilities(network)?.let {
                    it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true } ?: throw IOException("Connect the phone and receiver to the same Wi-Fi or Ethernet network.")
                val address = (if (localChosen) localConnection?.socket?.localAddress?.takeIf { it is java.net.Inet4Address }?.hostAddress else null)
                    ?: manager.getLinkProperties(network)?.linkAddresses?.map { it.address }
                    ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
                    ?: throw IOException("No local IPv4 receiver address is available.")
                val factory = androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent(userAgent)
                    .setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(mapOf(
                        "ascendontoken" to api.ascendon.orEmpty(), "entitlementtoken" to api.entitlement.orEmpty()))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    relay = app.apexview.cast.SceneCastRelay(session, app.apexview.tiled.CmafHttpReader(factory), address)
                    (castTiles.firstOrNull { it.audible } ?: clockTile).let {
                        relay!!.selectAudio(it.feed.sourceStream.profileLabel)
                    }
                    relay!!.validateAudio()
                }
                if ((localChosen && (!localReady || localConnection == null)) || (!localChosen && googleCastRemoteClient == null))
                    throw kotlinx.coroutines.CancellationException("Cast session ended during setup")
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (generation != castPreparationGeneration) throw kotlinx.coroutines.CancellationException("Stale cast preparation")
                require(tiles.map { it.id to it.feed.sourceStream.profileLabel } == castTiles.map { it.id to it.feed.sourceStream.profileLabel }) { "Feed selection changed during preparation; retry casting" }
                castRelay?.takeIf { it !== relay }?.let { retiredCastRelays += it }
                castRelay = relay
                castStatus = if (localChosen || usesLayoutCastReceiver) "Sending at least 720p sources and the phone video layout to the TV…"
                    else "Casting ${session.sourceDisplayHeight}p sources. This build uses the standard receiver, which shows the encoded grid; the phone layout needs the registered Pitwall receiver."
                message = castStatus
                castTiles.forEach { it.feed.setAudible(false) }
                if (localChosen) loadLocalCastMedia(relay!!) else loadGoogleCastMedia(relay!!)
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                if (castRelay === relay) releaseCastRelay()
                relay?.close(); castStatus = "The selected feeds could not be composed for the TV in time."; message = castStatus
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                relay?.close()
                if (cancelled.message != "Cast session ended during setup") throw cancelled
            } catch (error: Exception) {
                if (castRelay === relay) releaseCastRelay()
                relay?.close()
                android.util.Log.w("PitwallCast", DiagnosticRedaction.redact(error.stackTraceToString()))
                castStatus = (error.message ?: "Unable to prepare the selected feeds for casting").replace(Regex("https?://\\S+"), "[URL]").take(240)
                message = castStatus
            } finally {
                ownedSession?.close()
                if (generation == castPreparationGeneration) { castBusy = false; castPreparationJob = null }
                if (relay != null && castRelay !== relay) relay?.close()
            }
        }
    }
    private fun loadGoogleCastMedia(relay: app.apexview.cast.SceneCastRelay) {
        val client = googleCastRemoteClient ?: return
        val url = relay.googleCastMediaUrl
        if (googleCastLoadedUrl == url) return
        val layoutScene = if (usesLayoutCastReceiver) castVideoScene(relay.session) else null
        if (usesLayoutCastReceiver && layoutScene == null) {
            failGoogleCastLoad("The phone video layout is not ready. Keep the player open and reconnect Cast.")
            return
        }
        val metadata = com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE
        ).apply {
            putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, "ApexView MultiView")
            putString(com.google.android.gms.cast.MediaMetadata.KEY_SUBTITLE, "${relay.session.sourceLabels.size} F1 feeds · one HEVC stream")
        }
        val media = com.google.android.gms.cast.MediaInfo.Builder(url)
            .setContentType("application/x-mpegURL")
            .setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .setCustomData(JSONObject().apply {
                if (layoutScene != null) put("pitwallScene", layoutScene)
            })
            .build()
        googleCastLoadedUrl = url
        googleLoading = true; googleHasPlayed = false
        val startPosition = castFramePositionMs.coerceAtLeast(0L)
        remoteCastClock.update(startPosition, playing, playbackSpeed, relay.session.durationUs / 1000)
        runCatching {
            client.load(media, playing, startPosition).setResultCallback { result ->
                if (castRelay !== relay) return@setResultCallback
                googleLoading = false
                if (result.status.isSuccess) {
                    handOffCastPlayback()
                    if (playbackSpeed != 1f) client.setPlaybackRate(playbackSpeed.toDouble())
                    client.setStreamMute(mutedAll)
                    if (!usesLayoutCastReceiver) castSceneConfirmed = true
                    startCastSceneUpdates(relay)
                    castStatus = if (usesLayoutCastReceiver) "TV accepted the stream; waiting for layout rendering confirmation…"
                        else "Google Cast is playing ${relay.session.sourceLabels.size} feeds with ${relay.session.sourceDisplayHeight}p sources. The standard receiver shows the encoded grid."
                    message = castStatus
                } else {
                    failGoogleCastLoad("Google Cast could not load the composed stream (status ${result.status.statusCode}).")
                }
            }
        }.onFailure { error ->
            failGoogleCastLoad("Google Cast load failed: ${(error.message ?: error.javaClass.simpleName).take(160)}")
        }
    }
    private fun failGoogleCastLoad(reason: String) {
        runCatching { googleCastRemoteClient?.stop() }
        releaseCastRelay()
        castBusy = false
        castStatus = reason
        message = reason
    }
    private fun releaseCastRelay(resumeLocal: Boolean = true) {
        ++castPreparationGeneration
        googleLoading = false; googleHasPlayed = false
        castBusy = false
        retiredCastRelays.forEach { it.close() }; retiredCastRelays.clear()
        castSceneJob?.cancel(); castSceneJob = null
        castSceneConfirmed = false
        googleCastLoadedUrl = null
        castRelay?.close(); castRelay = null
        if (resumeLocal) {
            returnCastPlayback()
            if (singleDecoderSceneActive) singleDecoderScenePlayer?.setAudible(!mutedAll)
            else tiles.forEach { it.feed.setAudible(it.audible && !mutedAll) }
        } else castPlaybackHandedOff = false
    }
    fun stopFullSceneCast() {
        stopExternalDisplay()
        clearExternalDisplayRequest()
        stopCasting(true)
    }
    private fun stopCasting(resumeLocal: Boolean) {
        ++localConnectionGeneration
        castPreparationJob?.cancel(); castPreparationJob = null
        if (localChosen) localConnection?.send(app.apexview.cast.local.FcastProtocol.STOP)
        else runCatching { googleCastRemoteClient?.stop() }
        localConnectionJob?.cancel(); localConnectionJob = null
        val previous = localConnection; localConnection = null; previous?.close()
        localReady = false; localTimingSupported = false; localChosen = false; googleCastSelected = false
        releaseCastRelay(resumeLocal); castBusy = false
        castStatus = "Casting stopped."
    }
    private fun castVideoScene(session: app.apexview.tiled.HevcArchiveVodSession): JSONObject? {
        if (canvasW <= 0 || canvasH <= 0) return null
        val labels = session.sourceLabels
        val windows = tiles.mapNotNull { tile ->
            val source = labels.indexOf(tile.feed.sourceStream.profileLabel)
            if (source < 0 || tile.w <= 0 || tile.h <= 0) null else
                app.apexview.scene.VideoWindow(tile.id, source, tile.x, tile.y, tile.w, tile.h, tile.z)
        }
        if (windows.size != labels.size) return null
        return app.apexview.cast.CastSceneProtocol.encode(app.apexview.scene.VideoScene(
            canvasW, canvasH, session.width, session.height, labels.size, windows,
            session.atlasColumns, session.sourceCodedWidth, session.sourceCodedHeight,
            session.sourceDisplayWidth, session.sourceDisplayHeight,
        ))
    }

    private fun startCastSceneUpdates(relay: app.apexview.cast.SceneCastRelay) {
        castSceneJob?.cancel()
        castSceneJob = scope.launch {
            var last = ""
            var loops = 0
            val timing = app.apexview.cast.CastTimingPublisher(context, this@AppState)
            while (castRelay === relay) {
                if (tiles.map { it.feed.sourceStream.profileLabel }.toSet() != relay.session.sourceLabels.toSet()) {
                    // Keep the old relay alive and the phone stopped until replacement is ready.
                    if (!castBusy && android.os.SystemClock.elapsedRealtime() >= castRetryAfter) prepareFullSceneCast(true)
                    kotlinx.coroutines.delay(500L)
                    continue
                }
                val scene = castVideoScene(relay.session)
                if (scene != null) {
                    val text = scene.toString()
                    if (text != last || loops % (if (localChosen && localTimingSupported) 50 else 10) == 0) {
                        runCatching {
                            if (localChosen) localConnection?.send(app.apexview.cast.local.FcastProtocol.SCENE, scene)
                            else if (usesLayoutCastReceiver) googleCastSession?.sendMessage(app.apexview.cast.CastSceneProtocol.NAMESPACE,
                                JSONObject().put("type", "scene").put("scene", scene).toString())
                        }.onFailure { android.util.Log.w("PitwallCast", "Scene send failed: ${it.javaClass.simpleName}") }
                        last = text
                    }
                }
                if (localChosen && localTimingSupported) runCatching {
                    timing.messages(localLoadId).forEach { localConnection?.send(app.apexview.cast.local.FcastProtocol.TIMING, it) }
                }.onFailure { android.util.Log.w("PitwallCast", "Timing display update failed: ${it.javaClass.simpleName}") }
                if (++loops == (if (localChosen && localTimingSupported) 150 else 30) && !castSceneConfirmed) {
                    castStatus = "TV has not confirmed layout rendering. Check the TV receiver screen and retry casting."
                    message = castStatus
                }
                kotlinx.coroutines.delay(if (localChosen && localTimingSupported) 100L else 500L)
            }
        }
    }

    val castFramePositionMs: Long get() = if (castPlaybackHandedOff) remoteCastClock.positionMs() else leaderTile()?.feed?.player?.currentPosition ?: 0L

    private var sceneOutputSize by mutableStateOf(0 to 0)
    fun reportSceneOutputSize(width: Int, height: Int) { sceneOutputSize = width to height }
    fun failSingleDecoderScene(reason: String) {
        stopProtectedCompositionPresentation()
        decoderMode = settings.decoderMode
        if (settings.decoderMode == DecoderMode.SINGLE) {
            singleDecoderFailure = reason
            tiles.forEach { it.feed.player.playWhenReady = false }
            applyVideoBudget()
        }
        message = reason
    }
    fun videoScene(width: Float, height: Float): app.apexview.scene.VideoScene? {
        val host = singleDecoderScenePlayer ?: return null
        val session = host.archiveSceneSession ?: return null
        val labels = host.archiveSceneLabels
        if (labels.isEmpty() || width <= 0 || height <= 0) return null
        return app.apexview.scene.VideoScene(width, height, host.archiveSceneWidth, host.archiveSceneHeight, labels.size,
            (if (inPip) listOfNotNull(pipTile()) else tiles).mapNotNull { tile ->
                val source = labels.indexOf(tile.feed.sourceStream.profileLabel)
                if (source < 0 || tile.w <= 0 || tile.h <= 0) null else if (inPip)
                    app.apexview.scene.VideoWindow(tile.id, source, 0f, 0f, width, height, tile.z)
                else app.apexview.scene.VideoWindow(tile.id, source, tile.x, tile.y, tile.w, tile.h, tile.z)
            }, session.atlasColumns, session.sourceCodedWidth, session.sourceCodedHeight,
            session.sourceDisplayWidth, session.sourceDisplayHeight)
    }
    fun setDecoderPreference(mode: DecoderMode) {
        if (mode == DecoderMode.MULTIPLE && tiles.size > MAX_FEEDS) {
            message = "Remove feeds until four remain before switching to Multiple decoders."
            return
        }
        settings.updateDecoderMode(mode)
        if (mode == DecoderMode.MULTIPLE) {
            singleDecoderRetryJob?.cancel(); singleDecoderRetryJob = null
            singleDecoderFailure = null
        } else if (tiles.size > 1 && !singleDecoderSceneActive) {
            tiles.forEach { it.feed.player.playWhenReady = false }
            applyVideoBudget()
        }
        if (mode == decoderMode && (mode == DecoderMode.MULTIPLE || singleDecoderSceneActive || tiles.size <= 1)) return
        chooseDecoderMode(mode)
    }

    fun retrySingleDecoder() {
        if (settings.decoderMode != DecoderMode.SINGLE || archiveCompositionStarting) return
        val leader = leaderTile() ?: return
        singleDecoderRetryJob?.cancel(); singleDecoderRetryJob = null
        android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_RETRY requested=USER")
        startArchiveSingleDecoderComposition(leader.contentId)
    }

    fun chooseDecoderMode(mode: DecoderMode) {
        if (mode == DecoderMode.MULTIPLE) {
            stopProtectedCompositionPresentation()
            decoderMode = mode
            tiles.forEach { it.feed.clearProtectedCompositionVideoRole(); it.feed.player.playWhenReady = playing }
            applyVideoBudget()
            layoutRequest++
            return
        }
        val leader = leaderTile() ?: run { decoderMode = mode; return }
        if (tiles.size == 1) { decoderMode = mode; return }
        if (tiles.any { it.contentId != leader.contentId } || leader.contentId in knownLiveContentIds ||
            tiles.any { it.feed.player.isCurrentMediaItemLive }) {
            decoderMode = mode
            singleDecoderFailure = "Single decoder currently supports compatible archive feeds from one session."
            message = singleDecoderFailure
            applyVideoBudget()
            return
        }
        decoderMode = mode
        startArchiveSingleDecoderComposition(leader.contentId)
    }

    private var composedArchiveOriginMs: Long? = null
    private var composedArchiveFillingBuffer = false
    private var protectedCompositionBounds: Map<Tile, ProtectedTileBounds> = emptyMap()
    private data class ProtectedTileBounds(val x: Float, val y: Float, val w: Float, val h: Float, val z: Float)
    private val prefs = context.getSharedPreferences("f1mv", Context.MODE_PRIVATE)

    // Video budget: only feeds you can see decode video, each at its tile's size, less as the phone heats up.

    private val power = context.getSystemService(android.os.PowerManager::class.java)
    /** The phone's thermal status (PowerManager.THERMAL_STATUS_*). */
    var thermal by mutableIntStateOf(power?.currentThermalStatus ?: android.os.PowerManager.THERMAL_STATUS_NONE)
        private set
    private val thermalListener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
        val before = thermal
        thermal = status
        when {
            status >= android.os.PowerManager.THERMAL_STATUS_CRITICAL && before < android.os.PowerManager.THERMAL_STATUS_CRITICAL ->
                message = "The phone is very hot. Only the main feed shows video until it cools down; the others keep their sound and place."
            status >= android.os.PowerManager.THERMAL_STATUS_SEVERE && before < android.os.PowerManager.THERMAL_STATUS_SEVERE ->
                message = "The phone is running hot, so the feeds are at lower quality until it cools down."
        }
        applyVideoBudget()
    }

    init {
        power?.addThermalStatusListener(context.mainExecutor, thermalListener)
        // Feeds come and go, move, hide behind each other and off screen; twice a second is soon enough.
        // Waits first: this runs while AppState is still being built, before the tiles exist.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                applyVideoBudget()
            }
        }
    }

    fun close() {
        singleDecoderRetryJob?.cancel(); singleDecoderRetryJob = null
        stopCasting(false)
        stopLocalDiscovery()
        googleCastRemoteClient?.unregisterCallback(googleRemoteCallback)
        stopProtectedCompositionPresentation(restorePlayer = false)
        f1DashCaptureJob?.cancel()
        f1DashCaptureJob = null
        f1DashCapture = null
        power?.removeThermalStatusListener(thermalListener)
    }

    /**
     * Video only for feeds that can be seen: not while browsing, not behind a full-screen feed,
     * not tucked away in laptop mode or hidden in the pop-out, not off the canvas or entirely
     * under another feed. Hidden ones keep playing their sound and stay in sync, so they come
     * back at the right moment (after a moment's buffering). Each visible feed asks for the size
     * of its tile; warm phones get less (3/4 size when hot, half when hotter), and a phone near
     * its limit shows video in the main feed only.
     */
    fun applyVideoBudget() {
        if (castPlaybackHandedOff) {
            tiles.forEach { it.feed.suspendForRemotePlayback(true) }
            return
        }
        if (singleDecoderVideoRole(settings.decoderMode, tiles.size, false, browsing) != null) {
            val owner = protectedCompositionHost
            tiles.forEach { tile ->
                tile.coolingOff = false; tile.heldForDecoder = false; tile.heldForSpeed = false
                tile.feed.setProtectedCompositionVideoRole(singleDecoderVideoRole(settings.decoderMode, tiles.size, tile === owner, browsing) == true,
                    canvasW.toInt().coerceAtLeast(1), canvasH.toInt().coerceAtLeast(1))
            }
            return
        }
        if (tiles.isEmpty()) {
            speedHeld = false
            return
        }
        // A speed this phone already struggled with, with this many feeds: straight to one picture.
        if (!speedHeld && playbackSpeed > 1f && tiles.size > 1 && (struggledAt[tiles.size] ?: Float.MAX_VALUE) <= playbackSpeed) {
            speedHeld = true
            message = "Only the main feed's picture plays at ${speedLabel(playbackSpeed)} on this device, to keep it smooth. Back to 1× brings the rest back."
        }
        val shown = if (inPip) pipTile() else null
        val main = protectedCompositionHost ?: tiles.firstOrNull { it.id == bigTileId } ?: tiles.firstOrNull { it.audible } ?: tiles.maxByOrNull { it.w }
        val scale = when {
            thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> 0.5f
            thermal >= android.os.PowerManager.THERMAL_STATUS_MODERATE -> 0.75f
            else -> 1f
        }
        val visibleTiles = tiles.filter { tile ->
            if (tile.protectedCompositionHidden) return@filter false
            when {
                browsing -> false
                inPip -> tile === shown
                laptopActive -> tile.id == bigTileId || tiles.size == 1
                focusTileId != null -> tile.id == focusTileId
                else -> onCanvas(tile) && tiles.none { it !== tile && it.z > tile.z && covers(it, tile) }
            }
        }
        // On TV, the codec's advertised instance count is an upper bound, not a guarantee. Keep
        // one instance in reserve and allocate the remaining slots to the main/audible feeds first.
        val decoderSlots = videoDecoderBudget()
        val decoderEligible = if (isTv) {
            visibleTiles.sortedWith(
                compareBy<Tile> { if (it === main) 0 else if (it.audible) 1 else if (it.channel.isDefault) 2 else 3 }
                    .thenByDescending { it.z }
            ).take(decoderSlots).toSet()
        } else visibleTiles.toSet()

        tiles.forEach { tile ->
            val visible = tile in visibleTiles
            // Laptop mode: the feed that was up top before the one there now keeps its picture
            // going out of sight, ready to return instantly. TV never takes this hidden slot.
            val warm = !visible && !browsing && !inPip && laptopActive && tile.id == previousTopId &&
                thermal < android.os.PowerManager.THERMAL_STATUS_MODERATE && !speedHeld
            if (warm) {
                tile.coolingOff = false
                tile.heldForDecoder = false
                tile.feed.keepVideo()
                return@forEach
            }
            val overDecoderBudget = isTv && visible && tile !in decoderEligible
            val hot = thermal >= android.os.PowerManager.THERMAL_STATUS_CRITICAL && tile !== main
            val held = speedHeld && !inPip && tile !== main
            tile.coolingOff = visible && hot
            tile.heldForSpeed = visible && held && !hot
            tile.heldForDecoder = overDecoderBudget
            val cooling = hot || held || overDecoderBudget
            // The pop-out stays on the rendition it has, ready for coming back.
            if (inPip && visible && !cooling) {
                tile.feed.keepVideo()
                return@forEach
            }
            tile.feed.setVideo(visible && !cooling, (tile.w * scale).toInt(), (tile.h * scale).toInt())
        }
        val pictures = tiles.filter { it.feed.showingVideo }
        pictures.zip(bandwidthShares(pictures.map { it.w * it.h })).forEach { (tile, share) ->
            tile.feed.setBandwidthShare(share)
        }
    }

    /** In laptop mode, the feed that was up top before the one there now. */
    private var previousTopId: Int? = null

    private fun onCanvas(t: Tile) =
        canvasW <= 0f || (t.x < canvasW && t.x + t.w > 0f && t.y < canvasH && t.y + t.h > 0f)

    private fun covers(a: Tile, b: Tile) =
        a.x <= b.x && a.y <= b.y && a.x + a.w >= b.x + b.w && a.y + a.h >= b.y + b.h
    val updater = Updater(context, scope)
    private var cachedDecoderDrmMode: Boolean? = null
    private var cachedDecoderBudget = 1

    private fun videoDecoderBudget(): Int {
        if (!isTv) return 4
        // Base the query on the DRM level actually active in this session. If the TV
        // refuses L3, the players fall back to L1 and the budget must switch to secure codecs.
        val softwareDrm = tiles.isNotEmpty() && tiles.all { it.feed.usesSoftwareDrm }
        val effectiveMode = if (tiles.isEmpty()) settings.softwareDrm else softwareDrm
        if (cachedDecoderDrmMode != effectiveMode) {
            cachedDecoderDrmMode = effectiveMode
            cachedDecoderBudget = queryVideoDecoderBudget(context, effectiveMode, isTv = true)
            android.util.Log.i(
                "ApexviewVideoBudget",
                "TV HEVC decoder budget=$cachedDecoderBudget, actualSoftwareDrm=$effectiveMode",
            )
        }
        return cachedDecoderBudget
    }

    var auth by mutableStateOf(AuthState.CHECKING)
        private set
    var tier by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var pastedToken by mutableStateOf("")

    // Browse.

    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen get() = backStack.last()
    val season = Calendar.getInstance().get(Calendar.YEAR)
    val weekends = mutableStateListOf<WeekendInfo>()
    val liveNow = mutableStateListOf<CatalogItem>()
    val weekendContent = mutableStateMapOf<String, WeekendContent>()
    var homeError by mutableStateOf<String?>(null)
        private set
    var picker by mutableStateOf<FeedPicker?>(null)
        private set
    /** Home's scroll position and each row's, kept while pages come and go. */
    val homeList = LazyListState()
    private val rowStates = mutableMapOf<String, LazyListState>()

    fun rowState(key: String) = rowStates.getOrPut(key) { LazyListState() }

    /** True while browsing on top of feeds that keep playing. */
    var browsing by mutableStateOf(false)

    /** Every content's channel list, for switching a tile's feed later. */
    private val channelsByContent = mutableMapOf<Long, List<Channel>>()
    private val sessionRefs = mutableMapOf<Long, SessionRef>()
    /** Catalog session starts are retained when Media3 on a TV exposes no HLS/DASH program clock. */
    private val catalogStartTimes = mutableMapOf<Long, Long>()
    /** Catalog-confirmed live sessions can use live timing before the video timeline is ready. */
    private val knownLiveContentIds = mutableSetOf<Long>()
    /** Catalog-confirmed replays use the archive without waiting for a device-specific player clock. */
    private val knownArchiveContentIds = mutableSetOf<Long>()
    /** Once F1 moves the live timing socket to the next session, the old session uses its archive. */
    private val completedLiveTimingContentIds = mutableSetOf<Long>()
    /** Session titles by content ID, for the viewer's header and its feed sheet. */
    private val titles = mutableMapOf<Long, String>()
    /** Session artwork by content ID, for the Continue watching card. */
    private val images = mutableMapOf<Long, String?>()

    /**
     * The last few sessions watched, newest first, each with where it left off. From storage, so
     * they survive a crash or a force-close. Shown on home as Continue watching. Before 0.16 only
     * one was kept; that one seeds the list.
     */
    val recents = mutableStateListOf<SavedWatch>().apply {
        addAll(
            prefs.getString(PREF_RECENTS, null)?.let(SavedWatch::listFromJson)
                ?: listOfNotNull(SavedWatch.fromJson(prefs.getString(PREF_SAVED_WATCH, null)))
        )
    }
    /** A resume in progress: applied once its feeds are on screen. */
    private var pendingResume: SavedWatch? = null
    private var saveTicks = 0

    /** The title of what the leading tile is playing. */
    val playingTitle get() = leaderTile()?.let { titles[it.contentId] }.orEmpty()

    // Viewer.

    val tiles = mutableStateListOf<Tile>()
    var layoutMode by mutableStateOf(LayoutMode.FEATURED)
        private set
    /** Bumped to ask the canvas to lay the tiles out again. */
    var layoutRequest by mutableIntStateOf(0)
        private set
    private var layoutDone = 0
    private var topZ = 0f
    private var nextTileId = 0
    /** The tile whose menu is open, if any. */
    var tileMenu by mutableStateOf<Tile?>(null)
        private set
    /** The feed in the big slot of the main + strip layout; double-tap changes it. */
    // State, so what depends on which feed is up top in laptop mode (the way back) redraws.
    private var bigTileId by mutableStateOf<Int?>(null)
    /** After a swap, the tile offering its audio for a few seconds. */
    var audioHint by mutableStateOf<Tile?>(null)
        private set
    /** Bumped on each new offer, to restart its fade timer. */
    var audioHintCount by mutableIntStateOf(0)
        private set
    var mapSheetOpen by mutableStateOf(false)
    var layoutSheetOpen by mutableStateOf(false)
    /**
     * Moving and resizing tiles (and the map) only while unlocked from the top bar, so a stray
     * touch while watching can't knock the layout around.
     */
    var arranging by mutableStateOf(false)
        private set
    /** While arranging: magnetic edges and no overlaps unless pushed hard (Snap), or Free. */
    var snapMode by mutableStateOf(prefs.getBoolean(PREF_SNAP, true))
        private set
    /** True once feeds have been moved by hand (or put back from a saved session). */
    private var handPlaced = false
    /** What a fresh stream starts in; Save layout makes it the custom one. */
    private var defaultLayoutMode =
        prefs.getString(PREF_DEFAULT_LAYOUT, null)?.let { runCatching { LayoutMode.valueOf(it) }.getOrNull() } ?: LayoutMode.FEATURED
    /** The TV preset starts the map in its right-hand slot once, then preserves D-pad moves. */
    private var tvLayoutMapNeedsDefault =
        defaultLayoutMode == LayoutMode.TV && prefs.getString(PREF_MAP_PANEL, null).isNullOrBlank()
    /** A resumed session's feed boxes by channel, applied at its first layout. */
    private var resumeLayout: Pair<Map<Int, FloatArray>, SavedLayout>? = null
    /** Every feed silenced at once; the audio choice is kept for when it's turned back on. */
    var mutedAll by mutableStateOf(false)
        private set
    /** The feed the next car tapped on the map replaces, after it was tapped. */
    var targetTile by mutableStateOf<Tile?>(null)
        private set
    /** Bumped on each new target, to restart its glow timer. */
    var targetCount by mutableIntStateOf(0)
        private set

    fun toggleArranging() {
        arranging = !arranging
        message = if (arranging) {
            if (snapMode) "Drag to move; edges snap. Push hard to overlap. Pinch or use the corner to resize. Tap the lock when done."
            else "Drag to move, pinch or use the corner to resize. Tap the lock when done."
        } else null
        if (!arranging) storeMapPanel()
        showChrome()
    }

    // Shared playback controls, driven by the leading tile.
    var chromeVisible by mutableStateOf(true)
    /** Bumped on every interaction with the viewer; the chrome hides a few seconds after. */
    var chromeTouch by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(true)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var isLive by mutableStateOf(false)
        private set

    // Track map, from F1's timing archive.
    var timing by mutableStateOf<SessionTiming?>(null)
        private set
    var mapStatus by mutableStateOf<String?>(null)
        private set
    /** Recent timing/map diagnostics shown in the TV overlay while data is resolving. */
    var timingDiagnostics by mutableStateOf<List<String>>(emptyList())
        private set
    private var timingDiagnosticStartedAtMs = 0L

    private fun recordTimingDiagnostic(message: String, reset: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (reset || timingDiagnosticStartedAtMs == 0L) {
            timingDiagnosticStartedAtMs = now
            timingDiagnostics = emptyList()
        }
        val elapsed = ((now - timingDiagnosticStartedAtMs).coerceAtLeast(0L) / 1000.0)
        val entry = "%05.1fs  %s".format(java.util.Locale.US, elapsed, message)
        timingDiagnostics = (timingDiagnostics + entry).takeLast(8)
        android.util.Log.i("ApexviewTiming", entry)
    }

    /** Live decoder status for the TV overlay; no codec capability query is repeated here. */
    val tvDecoderDiagnostic: String
        get() = if (!isTv) "Decoder diagnostics: phone" else {
            val visible = tiles.count { it.feed.showingVideo }
            val held = tiles.count { it.heldForDecoder }
            "HEVC decoder budget $cachedDecoderBudget · video $visible/${tiles.size} · held $held · " +
                lastVideoDecoderDiagnostic.take(150)
        }
    /** Edge by default; the last style picked is remembered. */
    var mapMode by mutableStateOf(savedMapMode())
        private set

    /** The driver the pit circle shows a stop for and measures gaps to; null for none. */
    var pitDriver by mutableStateOf<String?>(null)
    /** The pit circle's gaps: to the leader, or (false) to the car ahead on track. */
    var pitGapsToLeader by mutableStateOf(false)
    /** The pit circle runs the other way round from the circuit. */
    var pitCircleFlipped by mutableStateOf(false)

    /** What a stop cost last time it was measured at this circuit, for before anyone's stopped. */
    fun savedPitLoss(circuitKey: Int?): Long? =
        circuitKey?.let { key -> prefs.getLong(PREF_PIT_LOSS + key, 0L).takeIf { it > 0L } }

    fun rememberPitLoss(circuitKey: Int?, lossMs: Long) {
        val key = circuitKey ?: return
        if (prefs.getLong(PREF_PIT_LOSS + key, 0L) != lossMs) prefs.edit().putLong(PREF_PIT_LOSS + key, lossMs).apply()
    }

    private fun savedMapMode() =
        prefs.getString(PREF_MAP_MODE, null)?.let { runCatching { MapMode.valueOf(it) }.getOrNull() } ?: MapMode.EDGE

    /** Videos that aren't sessions (press conferences, shows, highlights): they start with the map off. */
    private val notSessions = mutableSetOf<Long>()
    val mapPanel = MapPanel()
    /** The track map's window in laptop mode, below the hinge; placed and remembered on its own. */
    val laptopMapPanel = MapPanel()
    /** The map window on screen now. */
    val activeMapPanel get() = if (laptopActive) laptopMapPanel else mapPanel
    var mapOpacity by mutableFloatStateOf(0.9f)
        private set
    /** Driver marker radius in dp; smaller defaults keep a crowded circuit readable. */
    var mapDotSizeDp by mutableFloatStateOf(prefs.getFloat(PREF_MAP_DOT_SIZE, 5f))
        private set
    /** Three-letter driver abbreviation size in sp. */
    var mapLabelSizeSp by mutableFloatStateOf(prefs.getFloat(PREF_MAP_LABEL_SIZE, 8f))
        private set
    /** Keeps the map still while the feeds under it are arranged. */
    var mapLocked by mutableStateOf(prefs.getBoolean(PREF_MAP_LOCKED, false))
        private set
    /** Feeds make room for the track map instead of lying under it. */
    var mapReserve by mutableStateOf(prefs.getBoolean(PREF_MAP_RESERVE, false))
        private set
    /** How the reserved map and timing tower share the space beside the video. */
    var overlayLayoutMode by mutableStateOf(
        prefs.getString(PREF_OVERLAY_LAYOUT, null)?.let { runCatching { OverlayLayoutMode.valueOf(it) }.getOrNull() }
            ?: OverlayLayoutMode.DOCKED
    )
        private set
    /** The screen regions currently assigned to the combined video/map/tower layout. */
    var combinedOverlayPlan by mutableStateOf<CombinedOverlayPlan?>(null)
        private set
    val combinedOverlaysDocked get() = combinedOverlayPlan?.floating == false
    val combinedOverlaysFloating get() = combinedOverlayPlan?.floating == true
    /**
     * Correction between the picture and the map, in ms. F1 stamps video a few seconds later
     * than timing (about -7 s on the 2026 Japanese GP replay). The last value set is remembered.
     */
    var mapOffsetMs by mutableLongStateOf(prefs.getLong(PREF_MAP_OFFSET, DEFAULT_MAP_OFFSET_MS))
        private set
    /** True when the stream has no clock and the map's time is a guess the user lines up. */
    var mapClockEstimated by mutableStateOf(false)
        private set
    private var timingContentId: Long? = null

    /** Play sessions by content ID. Every tile of a content shares one. */
    private val sessions = mutableMapOf<Long, MultiPlay>()
    /** Contents whose multiview session F1 refused; their feeds use one session each. */
    private val singleSessionOnly = mutableSetOf<Long>()

    // Sign-in.

    fun restore() {
        val token = TokenStore.load(context)
        if (token == null) auth = AuthState.SIGNED_OUT else signIn(token)
        loadHome()
    }

    fun startLogin() = openLogin()

    fun onLoginFinished() {
        TokenStore.load(context)?.let(::signIn)
    }

    fun usePastedToken() {
        val token = pastedToken.trim()
        if (token.isEmpty()) return
        TokenStore.save(context, token)
        signIn(token)
    }

    private fun signIn(token: String) {
        scope.launch {
            busy = true
            try {
                api.platform = settings.platform
                api.login(token)
                tier = api.tier
                planPromptOpen = !hasVideoPlan
                auth = AuthState.SIGNED_IN
                message = null
                loadHome()
            } catch (e: Exception) {
                auth = AuthState.SIGNED_OUT
                message = "Couldn't sign in: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    /**
     * Whether the signed-in account's F1 TV plan includes video. A free account (REG) signs in
     * fine but gets nothing to play. In the US, Premium comes with Apple TV and reaches the F1 TV
     * account once the Apple account is linked to it; until then it's REG.
     */
    val hasVideoPlan get() = tier.isNotEmpty() && tier !in NO_VIDEO_TIERS

    /** A plan with video but no multiview, like Pro: F1 sends it one feed per session. */
    val singleFeedPlan get() = hasVideoPlan && !api.hasMultiview
    /** The last crash's report, waiting for the person to send it or not. */
    var crashReport by mutableStateOf(CrashReports.pending(context))
        private set

    fun closeCrashReport() {
        crashReport = null
        CrashReports.clear(context)
    }

    /** Explains an account without video, once at sign-in and again if a stream is refused. */
    var planPromptOpen by mutableStateOf(false)

    fun closePlanPrompt(signOutToo: Boolean) {
        planPromptOpen = false
        if (signOutToo) signOut()
    }

    fun signOut() {
        stopAll()
        TokenStore.clear(context)
        LoginCookie.clear()
        api = F1TvApi(userAgent, chromeMajor)
        auth = AuthState.SIGNED_OUT
        tier = ""
        backStack.clear()
        backStack.add(Screen.Home)
    }

    fun choosePlatform(value: String) {
        if (value == settings.platform) return
        settings.updatePlatform(value)
        // Tokens and location are fetched per platform, so sign in again under the new one.
        TokenStore.load(context)?.let(::signIn)
    }

    // Browse.

    fun open(screen: Screen) {
        backStack.add(screen)
        when (screen) {
            is Screen.Weekend -> loadWeekend(screen.weekend)
            is Screen.Collection -> loadCollection(screen.pageId)
            Screen.Archive -> loadArchive()
            else -> Unit
        }
    }

    /** Returns false when there's nowhere further back to go. */
    fun back(): Boolean = when {
        picker != null -> { picker = null; true }
        backStack.size > 1 -> { backStack.removeAt(backStack.lastIndex); true }
        browsing && tiles.isNotEmpty() -> { browsing = false; true }
        else -> false
    }

    /** The weekend for the hero: the one in progress, else the next, else the last. */
    fun featuredWeekend(now: Long = System.currentTimeMillis()): WeekendInfo? =
        weekends.firstOrNull { it.isCurrent(now) }
            ?: weekends.firstOrNull { it.startMs > now }
            ?: weekends.lastOrNull()

    /** Finished and running weekends, newest first. */
    fun pastWeekends(now: Long = System.currentTimeMillis()) =
        weekends.filter { it.startMs <= now }.sortedByDescending { it.startMs }

    /** The latest weekend that has sessions to watch: this one if it's under way, else the last. */
    fun latestWeekend(now: Long = System.currentTimeMillis()) = pastWeekends(now).firstOrNull()

    fun loadHome() {
        scope.launch {
            try {
                api.prepare()
                val calendar = async { api.calendar(season) }
                val live = async { runCatching { api.liveNow() }.getOrDefault(emptyList()) }
                // Each list is swapped in one go, after both answers: home loads twice at start-up
                // (once on opening, again after sign-in), and clearing before the wait let both
                // loads fill the list, every weekend twice.
                val (newWeekends, newLive) = calendar.await() to live.await()
                weekends.clear()
                weekends.addAll(newWeekends)
                liveNow.clear()
                liveNow.addAll(newLive)
                homeError = null
                latestWeekend()?.let(::loadWeekend)
                featuredWeekend()?.takeIf { it != latestWeekend() }?.let(::loadWeekend)
                launch { runCatching { api.home() }.onSuccess { homePage = it } }
                if (lastYearRaces.isEmpty()) launch { runCatching { api.raceReplays(season - 1) }.onSuccess { lastYearRaces.clear(); lastYearRaces.addAll(it) } }
            } catch (e: Exception) {
                homeError = "Couldn't load F1 TV: ${e.message}"
            }
        }
    }

    /**
     * Keeps home current while the app stays open: a session going live or ending changes the
     * carousel and the weekend's schedule. Not while watching, when nothing here is on screen.
     * A failed check leaves things as they are.
     */
    suspend fun refreshLive() {
        if (auth != AuthState.SIGNED_IN) return
        val watching = tiles.isNotEmpty() && !browsing
        // Watching a replay, there's nothing to offer.
        if (watching && !isLive) return
        val newLive = runCatching { api.prepare(); api.liveNow() }.getOrNull() ?: return
        if (watching) {
            offerNextLive(newLive)
            return
        }
        if (newLive.map { it.contentId } == liveNow.map { it.contentId }) return
        liveNow.clear()
        liveNow.addAll(newLive)
        latestWeekend()?.let(::loadWeekend)
    }

    // What's on next: the race after the pre-race show, the post-race show after the race.

    /** Something else live to switch to, offered in a note while watching live. */
    var nextLive by mutableStateOf<CatalogItem?>(null)
        private set
    /** What was live when the session on screen was opened, and for which content. */
    private var liveAtStart: Pair<Long, Set<Long>>? = null
    private val declinedLive = mutableSetOf<Long>()

    /**
     * Offers what's next, once: something that's gone live since this session was opened, or,
     * when what's on screen has gone off air, whatever is live in its place.
     */
    internal fun offerNextLive(live: List<CatalogItem>) {
        val watching = leaderTile()?.contentId ?: return
        val ids = live.map { it.contentId }.toSet()
        val atStart = liveAtStart?.takeIf { it.first == watching }?.second
            ?: ids.also { liveAtStart = watching to it }
        nextLive = pickNextLive(live, watching, atStart, tiles.map { it.contentId }.toSet(), declinedLive)
            ?: nextLive?.takeIf { it.contentId in ids }
    }

    fun declineNextLive() {
        nextLive?.let { declinedLive += it.contentId }
        nextLive = null
    }

    /** Leaves what's on screen for what's next, starting on its main broadcast. */
    fun switchToNextLive() {
        val item = nextLive ?: return
        nextLive = null
        stopAll()
        watch(item, start = true)
    }

    /** Fetched fresh each time, so sessions flip from upcoming to live to replay as the weekend runs. */
    fun loadWeekend(weekend: WeekendInfo) {
        scope.launch {
            runCatching { api.weekend(weekend) }
                .onSuccess {
                    weekendContent[weekend.meetingKey] = it
                    syncReminders()
                }
        }
    }

    // F1 TV's catalog beyond the weekends.

    /** F1 TV's home page: what it features now, and its rows of shows, documentaries and archive races. */
    var homePage by mutableStateOf<HomePage?>(null)
        private set
    /** Show and documentary pages, by F1 TV page ID, fetched when opened. */
    val collections = mutableStateMapOf<Int, CollectionPage>()
    /** Popular races from every season, fetched when the archive page opens. */
    var archive by mutableStateOf<List<CatalogItem>?>(null)
        private set
    /** Last season's race replays, for "last year's race" before a weekend. */
    private val lastYearRaces = mutableStateListOf<CatalogItem>()

    fun loadCollection(pageId: Int) {
        scope.launch { runCatching { api.collection(pageId) }.onSuccess { collections[pageId] = it } }
    }

    fun loadArchive() {
        if (archive != null) return
        scope.launch { runCatching { api.archiveRaces() }.onSuccess { archive = it } }
    }

    /** Plays a catalog item; a show or documentary series opens its page of episodes instead. */
    fun watch(item: CatalogItem, start: Boolean = false) {
        item.pageId?.let { open(Screen.Collection(it, item.title)); return }
        item.startMs.takeIf { it > 0L }?.let { catalogStartTimes[item.contentId] = it }
        // Sessions are titled by their weekend in the viewer ("Mexico City Grand Prix · Race").
        val session = weekendContent.values.any { c -> c.sessions.any { it.contentId == item.contentId } }
        val catalogReplay = item.kind.equals("REPLAY", ignoreCase = true) ||
            (session && !item.live && !item.upcoming && item.startMs > 0L)
        if (item.live) {
            knownArchiveContentIds -= item.contentId
        } else if (catalogReplay && !item.upcoming) {
            // The catalog has already moved this session out of LIVE. Don't make TV wait for a
            // HLS/DASH program clock to learn whether this is a replay.
            knownArchiveContentIds += item.contentId
            knownLiveContentIds -= item.contentId
            completedLiveTimingContentIds -= item.contentId
        }
        // Live events carry their weekend's name as their subtitle.
        val meeting = item.meeting.ifEmpty { if (item.live) item.subtitle.orEmpty() else "" }
        openPicker(item.contentId, if ((session || item.live) && meeting.isNotEmpty()) "$meeting · ${item.title}" else item.title, item.image, start, item.live)
    }

    /** This season's weekend a video belongs to, for its Weekend button. */
    fun weekendOf(item: CatalogItem): WeekendInfo? =
        weekends.firstOrNull { it.name == item.meeting && (item.year == 0 || item.year == season) }

    // Watch history, for the suggestions.

    /** What's been watched, by content ID. Kept to the most recent few hundred. */
    val history = mutableStateMapOf<Long, Seen>().apply { putAll(readHistory()) }

    private fun readHistory(): Map<Long, Seen> = runCatching {
        val json = JSONObject(prefs.getString(PREF_HISTORY, null) ?: return emptyMap())
        json.keys().asSequence().associate { key ->
            val a = json.getJSONArray(key)
            key.toLong() to Seen(a.getLong(0), a.getLong(1), a.getBoolean(2))
        }
    }.getOrDefault(emptyMap())

    /**
     * Notes a few more seconds of [contentId] watched. A session counts as watched once the
     * picture is past its chequered flag, live or replay: the stream runs on well after it (the
     * podium, interviews), and few sit through that. Anything without timing counts at 90%. Live
     * qualifying doesn't count by its flag: each part has one, and more of it may be to come.
     */
    private fun noteSeen(contentId: Long, positionMs: Long, durationMs: Long, live: Boolean) {
        val now = System.currentTimeMillis()
        val before = history[contentId]
        // Saves come every few seconds while watching; a longer gap was time away.
        val gap = before?.let { now - it.at }?.takeIf { it in 0..15_000 } ?: 0
        val watched = (before?.watchedMs ?: 0) + gap
        val flag = timing?.takeIf { timingContentId == contentId && !(live && it.showsPushLaps) }?.finishedUtc
        val pastFlag = flag != null && (mapTimeMs() ?: 0L) >= flag
        val finished = before?.finished == true || pastFlag ||
            (!live && durationMs > 0 && positionMs >= durationMs * 9 / 10)
        history[contentId] = Seen(now, watched, finished)
        if (history.size > MAX_HISTORY) history.entries.sortedBy { it.value.at }.take(history.size - MAX_HISTORY).forEach { history.remove(it.key) }
        val json = JSONObject()
        history.forEach { (id, seen) -> json.put(id.toString(), JSONArray(listOf(seen.at, seen.watchedMs, seen.finished))) }
        prefs.edit().putString(PREF_HISTORY, json.toString()).apply()
    }

    /**
     * Home's carousel: the best things to watch next, in order. What's live; a session left
     * part-way; after a session you watched, what follows it (the post-race show, the press
     * conference, highlights), or the session itself if you haven't; what F1 TV features now;
     * before the next weekend, its build-up and last year's race there, then its schedule; and
     * one race from the archive. Anything watched to the end is left out.
     */
    fun suggestions(now: Long = System.currentTimeMillis()): List<Pick> {
        val picks = mutableListOf<Pick>()
        val used = mutableSetOf<Long>()
        fun fresh(item: CatalogItem) = !item.upcoming && history[item.contentId]?.finished != true && item.pageId == null
        fun add(reason: String, item: CatalogItem) {
            if (item.contentId > 0 && fresh(item) && used.add(item.contentId)) picks += Pick(reason, item = item)
        }

        // Always, watched or not: it's still going.
        liveNow.filter { it.contentId > 0 && used.add(it.contentId) }.forEach { picks += Pick("Live now", item = it) }

        // F1 TV's own picks in its order, ahead of anything from your history.
        homePage?.featured?.forEach { add("Featured on F1 TV", it) }

        val latest = latestWeekend(now)
        val content = latest?.let { weekendContent[it.meetingKey] }
        val last = content?.sessions?.filter { !it.upcoming && !it.live }?.maxByOrNull { it.startMs }
        if (latest != null && content != null && last != null) {
            val stage = last.title.lowercase()
            if (history[last.contentId] == null) {
                add(if (now - last.startMs < 2 * DAY_MS) "In case you missed it" else "Catch up", last)
            } else {
                // What follows the session you watched: its show, its press conference, its highlights.
                fun rank(v: CatalogItem): Int {
                    val t = "${v.title} ${v.genre}".lowercase()
                    return when {
                        "post-$stage" in t && "show" in t -> 0
                        "post-$stage" in t -> 1
                        "$stage in 30" in t || "$stage highlights" in t -> 2
                        else -> -1
                    }
                }
                val after = if ("qualifying" in stage) "After $stage" else "After the $stage"
                content.videos.filter { rank(it) >= 0 }.sortedBy(::rank).forEach { add(after, it) }
            }
        }

        // The next weekend: during one, its next session; before one, its build-up.
        val next = weekends.firstOrNull { it.isCurrent(now) && nextSession(it) != null }
            ?: weekends.firstOrNull { it.startMs > now }
        if (next != null) {
            if (next.startMs - now < 7 * DAY_MS) {
                weekendContent[next.meetingKey]?.videos?.forEach { add("Before the ${next.name}", it) }
                lastYearRaces.firstOrNull { it.meeting == next.name }?.let { add("Last year in ${next.place}", it) }
            }
            val session = nextSession(next)
            picks += Pick(
                when {
                    session != null && next.isCurrent(now) -> "Next: ${session.title} · ${session.subtitle}"
                    else -> "Next race weekend · ${DAY.format(java.util.Date(next.startMs))} to ${DAY.format(java.util.Date(next.endMs))}"
                },
                weekend = next,
            )
        }

        // Your own history comes after what F1 TV and the weekend put first.
        recents.firstOrNull { r -> !r.live && history[r.contentId]?.let { now - it.at < 3 * DAY_MS } == true }?.let {
            if (used.add(it.contentId)) picks += Pick("Pick up where you left off", resume = it)
        }

        // One race from the archive, a different one each day.
        homePage?.rows?.firstOrNull { it.tag == CatalogRow.ARCHIVE }?.items?.filter(::fresh)?.takeIf { it.isNotEmpty() }?.let { races ->
            add("From the archive", races[(now / DAY_MS % races.size).toInt()])
        }
        // The next weekend's schedule always makes it, even after a long build-up.
        val schedule = picks.firstOrNull { it.weekend != null }
        if (schedule != null && picks.indexOf(schedule) >= MAX_PICKS) return picks.take(MAX_PICKS - 1) + schedule
        return picks.take(MAX_PICKS)
    }

    // Past seasons, for the calendar.

    /** Seasons F1 TV has weekends for (2018 on) or archive videos for (1981 on), newest first. */
    val archiveSeasons = (season downTo FIRST_ARCHIVE_SEASON).toList()
    /** The season the calendar shows. */
    var calendarSeason by mutableIntStateOf(season)
        private set
    /** Past seasons' weekends (2018 on), fetched when first shown. */
    val pastWeekends = mutableStateMapOf<Int, List<WeekendInfo>>()
    /** Seasons before F1 TV's weekends: their replays and other videos. */
    val classicVideos = mutableStateMapOf<Int, List<CatalogItem>>()

    fun chooseCalendarSeason(year: Int) {
        calendarSeason = year
        when {
            year == season -> Unit
            year >= FIRST_WEEKEND_SEASON && year !in pastWeekends -> scope.launch {
                runCatching { api.calendar(year) }.onSuccess { pastWeekends[year] = it }
            }
            year < FIRST_WEEKEND_SEASON && year !in classicVideos -> scope.launch {
                runCatching { api.seasonVideos(year) }.onSuccess { classicVideos[year] = it }
            }
        }
    }

    // Session reminders.

    var remindersOn by mutableStateOf(Reminders.enabled(context))
        private set
    var remindersMainOnly by mutableStateOf(Reminders.mainOnly(context))
        private set

    fun chooseReminders(on: Boolean) {
        remindersOn = on
        Reminders.setEnabled(context, on)
        syncReminders()
    }

    fun chooseRemindersMainOnly(only: Boolean) {
        remindersMainOnly = only
        Reminders.setMainOnly(context, only)
        syncReminders()
    }

    /** Sets reminders for every upcoming session the app knows the time of (the weekends loaded). */
    private fun syncReminders() {
        val now = System.currentTimeMillis()
        // A session under way still wants recording; the weekend's end can come before the race does.
        val sessions = weekends.filter { now < it.endMs + 86_400_000L }.flatMap { w ->
            weekendContent[w.meetingKey]?.sessions.orEmpty()
                .filter { it.startMs > 0 }
                .map { Reminders.Session(it.contentId, w.name, it.title, it.startMs) }
        }
        Reminders.sync(context, sessions.filter { it.startMs > now })
        LiveRecording.sync(context, sessions)
    }

    var recordingOn by mutableStateOf(LiveRecording.enabled(context))
        private set
    var recordingAll by mutableStateOf(LiveRecording.allSessions(context))
        private set

    fun chooseRecording(on: Boolean) {
        recordingOn = on
        LiveRecording.setEnabled(context, on)
        syncReminders()
    }

    fun chooseRecordingAll(all: Boolean) {
        recordingAll = all
        LiveRecording.setAllSessions(context, all)
        syncReminders()
    }

    /** The next Formula 1 session of the featured weekend that hasn't started, if any. */
    fun nextSession(weekend: WeekendInfo): CatalogItem? =
        weekendContent[weekend.meetingKey]?.sessions?.firstOrNull { it.upcoming }

    // Choosing feeds.

    /** Opens the feed sheet for a session; feeds already on screen show as picked. */
    /**
     * [start]: begin on the main broadcast straight away rather than waiting for a choice.
     * [live]: on air now (also known from what's live).
     */
    fun openPicker(contentId: Long, title: String, image: String? = null, start: Boolean = false, live: Boolean = false) {
        if (auth != AuthState.SIGNED_IN) {
            message = "Sign in to watch."
            return
        }
        val isKnownLive = live || liveNow.any { it.contentId == contentId }
        if (isKnownLive) {
            knownLiveContentIds += contentId
            knownArchiveContentIds -= contentId
            completedLiveTimingContentIds -= contentId
        }
        val sheet = FeedPicker(contentId, title, isKnownLive)
        titles[contentId] = title
        if (image != null) images[contentId] = image
        picker = sheet
        scope.launch {
            try {
                val list = channelsOf(contentId)
                sheet.channels = list
                val playingThis = tiles.any { it.contentId == contentId }
                if (list.size == 1 && !playingThis) {
                    // Highlights and shows have one feed: no choice to make, just play it. Live,
                    // there's still live or from the start to choose, so it waits for that.
                    sheet.selected.add(list.first())
                    if (start || !sheet.live) watchPicked()
                } else if (!playingThis) {
                    // Nothing playing yet: start with the main broadcast ticked.
                    sheet.selected.add(list.firstOrNull { it.isDefault } ?: list.first())
                    if (start) watchPicked()
                }
            } catch (e: Exception) {
                sheet.error = "Couldn't load this session's feeds: ${e.message}"
            }
        }
    }

    /** A session's feeds, fetched once. */
    private suspend fun channelsOf(contentId: Long): List<Channel> = channelsByContent[contentId] ?: run {
        val video = api.contentVideo(contentId)
        video.session?.let { ref ->
            val catalogStart = catalogStartTimes[contentId]
            sessionRefs[contentId] = if (ref.sessionStartUtcMs == null && catalogStart != null) {
                ref.copy(sessionStartUtcMs = catalogStart)
            } else ref
        }
        if (!video.isSession) notSessions += contentId
        video.channels.ifEmpty { listOf(Channel("Main feed", null, isDefault = true)) }
            .also { channelsByContent[contentId] = it }
    }

    // Saving and resuming.

    /**
     * Records the leading session as it stands. Called every few seconds while watching, when
     * the app goes to the background and when the viewer closes. A replay watched to its last
     * minute is done, so the record is dropped.
     */
    fun saveWatch() {
        val leader = leaderTile() ?: return
        val player = leader.feed.player
        val live = leader.contentId in knownLiveContentIds || player.isCurrentMediaItemLive
        val duration = if (castPlaybackHandedOff) durationMs else player.duration.takeIf { it > 0 } ?: 0L
        val position = if (castPlaybackHandedOff) castFramePositionMs else player.currentPosition
        noteSeen(leader.contentId, position, duration, live)
        if (!live && duration > 0 && position > duration - 60_000) {
            clearSavedWatch()
            return
        }
        val session = tiles.filter { it.contentId == leader.contentId }
        val big = session.firstOrNull { it.id == bigTileId } ?: session.first()
        val layout = if (canvasW > 0f) SavedLayout.of(session.map { tile ->
            protectedCompositionBounds[tile]?.let { Slot(it.x, it.y, it.w) } ?: Slot(tile.x, tile.y, tile.w)
        }, canvasW, canvasH) else null
        val saved = SavedWatch(
            contentId = leader.contentId,
            title = titles[leader.contentId] ?: "",
            image = images[leader.contentId],
            positionMs = position,
            durationMs = duration,
            live = live,
            channels = session.map { it.channel.key() },
            bigChannel = big.channel.key(),
            audioChannel = (session.firstOrNull { it.audible } ?: big).channel.key(),
            layoutMode = (if (layoutMode in setOf(LayoutMode.TILE_TEST, LayoutMode.SINGLE_DECODER_TEST)) LayoutMode.GRID else layoutMode).name,
            mapMode = mapMode.name,
            layout = layout,
        )
        recents.removeAll { it.contentId == saved.contentId }
        recents.add(0, saved)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
        storeRecents()
    }

    private fun clearSavedWatch() {
        val leader = leaderTile() ?: return
        if (recents.removeAll { it.contentId == leader.contentId }) storeRecents()
    }

    private fun storeRecents() {
        prefs.edit().putString(PREF_RECENTS, SavedWatch.listToJson(recents)).remove(PREF_SAVED_WATCH).apply()
    }

    /** Puts a saved session back: same feeds, layout, big tile, audio and moment. */
    fun resumeWatching(saved: SavedWatch) {
        if (auth != AuthState.SIGNED_IN) return
        // Another session on screen gives way to the one being resumed.
        if (tiles.isNotEmpty()) stopAll()
        task("Picking up where you left off") {
            val list = channelsOf(saved.contentId)
            val feeds = saved.channels.mapNotNull { key -> list.firstOrNull { it.key() == key } }
            if (feeds.isEmpty()) throw IOException("those feeds aren't available any more")
            titles[saved.contentId] = saved.title
            images[saved.contentId] = saved.image
            if (saved.live) {
                knownLiveContentIds += saved.contentId
                knownArchiveContentIds -= saved.contentId
                completedLiveTimingContentIds -= saved.contentId
            } else if (sessionRefs.containsKey(saved.contentId)) {
                // This is a saved replay of a session. Do not wait for the TV's player clock.
                knownArchiveContentIds += saved.contentId
                knownLiveContentIds -= saved.contentId
            }
            val restoredLayout = runCatching { LayoutMode.valueOf(saved.layoutMode) }.getOrNull()
            if (restoredLayout != null) layoutMode = restoredLayout
            runCatching { mapMode = MapMode.valueOf(saved.mapMode) }
            if (restoredLayout == LayoutMode.TV) {
                mapMode = MapMode.TRACK
                towerOpen = true
                overlayLayoutMode = OverlayLayoutMode.DOCKED
                tvLayoutMapNeedsDefault = !mapPanel.placed && prefs.getString(PREF_MAP_PANEL, null).isNullOrBlank()
                prefs.edit()
                    .putString(PREF_MAP_MODE, MapMode.TRACK.name)
                    .putBoolean(PREF_TOWER, true)
                    .putString(PREF_OVERLAY_LAYOUT, OverlayLayoutMode.DOCKED.name)
                    .apply()
            }
            // Feeds come back where they were, if the screen is the same shape.
            resumeLayout = saved.layout?.let { layout -> saved.channels.zip(layout.boxes).toMap() to layout }
            pendingResume = saved
            browsing = false
            addFeeds(saved.contentId, feeds)
        }
    }

    /** Opens the F1 TV account site in the browser. */
    fun openAccountPage() {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(ACCOUNT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens the feed sheet for whatever is playing, to add another of its feeds. */
    fun openPickerForPlaying() {
        val leader = leaderTile() ?: return
        openPicker(
            leader.contentId,
            titles[leader.contentId] ?: "Add a feed",
            live = leader.contentId in knownLiveContentIds || liveNow.any { it.contentId == leader.contentId },
        )
    }

    fun closePicker() {
        picker = null
    }

    fun isPlaying(contentId: Long, channel: Channel) = tiles.any { it.contentId == contentId && it.channel == channel }

    /** Feeds there would be on screen if the sheet's picks were applied. */
    fun pickedCount(sheet: FeedPicker) = tiles.size - sheet.removing.size + sheet.selected.size

    /**
     * Feeds already on screen start out picked; tapping one unpicks it to make room. New feeds
     * can be picked until the screen would hold four.
     */
    fun togglePick(channel: Channel) {
        val sheet = picker ?: return
        val selectionLimit = if (layoutMode == LayoutMode.TILE_TEST) 2 else feedSelectionLimit
        val full = pickedCount(sheet) >= selectionLimit
        sheet.note = null
        when {
            isPlaying(sheet.contentId, channel) && channel in sheet.removing ->
                if (full) sheet.note = feedLimitNote(selectionLimit) else sheet.removing.remove(channel)
            isPlaying(sheet.contentId, channel) -> sheet.removing.add(channel)
            channel in sheet.selected -> sheet.selected.remove(channel)
            full -> sheet.note = feedLimitNote(selectionLimit)
            else -> sheet.selected.add(channel)
        }
    }

    /**
     * Applies the sheet. A feed unpicked and another picked in its place take over the same tile,
     * so the layout and the audio stay put; leftovers are removed or added.
     */
    /** [fromStart]: a live session from the beginning of what F1 keeps, rather than at the live edge. */
    fun watchPicked(fromStart: Boolean = false) {
        val sheet = picker ?: return
        if (layoutMode == LayoutMode.TILE_TEST) {
            val count = pickedCount(sheet)
            if (count != 2) {
                sheet.note = "Tile testing needs exactly two feeds from this session. You have $count selected."
                return
            }
            val labels = (
                tiles.filter { it.contentId == sheet.contentId && it.channel !in sheet.removing }.map { it.channel } +
                    sheet.selected
                ).map { channel -> channel.channelId?.let { "channel-$it" } ?: "default-channel" }
            if (labels.size != 2) {
                sheet.note = "Choose exactly two different feeds from the same F1 session."
                return
            }
            val compositionGroup = "content-${sheet.contentId}"
            HevcFeedProfileCapture.selectTileTestFeeds(compositionGroup, labels)
            ProtectedHevcLiveComposition.selectFeeds(compositionGroup, labels)
            if (sheet.selected.isEmpty() && sheet.removing.isEmpty()) {
                if (isTv) startRealF1DashCapture(sheet.contentId, labels)
                picker = null
                browsing = false
                handPlaced = false
                layoutRequest++
                message = if (isTv) {
                    "Reading real F1 HEVC segments for one-decoder composition."
                } else {
                    "Tile test selected. Comparing the two chosen HEVC feeds."
                }
                showChrome()
                return
            }
        }
        if (sheet.selected.isEmpty() && sheet.removing.isEmpty()) return
        // Silent tiles are swapped first, so the audio stays on the main broadcast.
        val freed = tiles.filter { it.contentId == sheet.contentId && it.channel in sheet.removing }
            .sortedBy { it.audible }.toMutableList()
        val adds = sheet.selected.toMutableList()
        while (freed.isNotEmpty() && adds.isNotEmpty()) switchFeed(freed.removeAt(0), adds.removeAt(0))
        freed.forEach(::remove)
        if (adds.isNotEmpty()) addFeeds(sheet.contentId, adds, fromStart)
        picker = null
        browsing = false
    }

    /** Replay composition is a finite on-demand source, not a moving live capture window. */
    private fun startArchiveSingleDecoderComposition(contentId: Long, attempt: Int = 0) {
        if (castPlaybackHandedOff) { applyVideoBudget(); return }
        singleDecoderRetryJob?.cancel(); singleDecoderRetryJob = null
        if (protectedCompositionHost != null && !singleDecoderSceneActive) stopProtectedCompositionPresentation()
        val selected = tiles.filter { it.contentId == contentId }
        if (selected.size !in 2..MAX_SINGLE_DECODER_FEEDS || selected.size != tiles.size ||
            selected.map { it.feed.sourceStream.profileLabel }.distinct().size != selected.size) {
            singleDecoderFailure = "Single decoder needs different feeds from the same archive."
            message = singleDecoderFailure
            if (!singleDecoderSceneActive) {
                decoderMode = settings.decoderMode
                tiles.forEach { it.feed.player.playWhenReady = false }
                applyVideoBudget()
            }
            return
        }
        singleDecoderFailure = null
        val host = protectedCompositionHost?.takeIf { it in selected } ?: selected.firstOrNull { it.audible } ?: selected.first()
        val previousLayout = layoutMode
        val capture = F1ArchiveFeedCapture(userAgent, api.ascendon.orEmpty(), api.entitlement.orEmpty())
        f1DashCaptureJob?.cancel()
        f1DashCapture = capture
        val startup = ArchiveCompositionStartup()
        var transferred = false
        var released = 0
        var prepared: app.apexview.tiled.HevcArchiveVodSession? = null
        var phase = "PREFLIGHT"
        var failure: String? = null
        var useExistingBackend = false
        var retryableFailure = false
        fun diagnostic() {
            if (f1DashCapture != null && f1DashCapture !== capture) return
            singleDecoderDiagnostic = buildString {
                append("Source: F1 Archive · Selected feeds: ${selected.size}\n")
                append("Requested platform: ${selected.map { it.feed.sourceStream.requestedPlatform }.distinct().joinToString()}\n")
                append("Actual manifest: ${if (prepared != null) "HLS → static composed DASH" else "VALIDATING RESPONSE"}\n")
                append("HEVC compatibility / CMAF alignment: ${if (prepared != null) "PASS" else "VALIDATING"}\n")
                prepared?.let { session ->
                    append("Video protection: CLEAR (verified CMAF). Widevine video keys: not required\n")
                    append("Source: ${session.selectedHeight ?: "verified in CMAF"}p · Full feeds: YES\n")
                    append("Composed picture: ${session.width}x${session.height} at ${session.frameRate}fps\n")
                    append("Archive timeline: finite VOD · native audio/video clock\n")
                }
                append("Source decoders released (callbacks): $released\n")
                append("Composed decoder: ${if (transferred) host.feed.activeVideoDecoderName ?: "NOT OBSERVED" else "NOT STARTED"}\n")
                if (transferred) {
                    append("${host.feed.activeVideoDecoderHardware} · Active video decoders (callbacks): $observedActiveVideoDecoders\n")
                    append("Selected video tracks: ${host.feed.selectedVideoTrackCount}\n")
                    append("Scene output: ${sceneOutputSize.first}x${sceneOutputSize.second} · decoder texture: 1\n")
                    append("First frame: ${if (host.feed.hasRenderedFirstFrame) "YES" else "NO"} · Rendered frames: ${host.feed.renderedVideoFrames}\n")
                    append("Player buffered: ${host.feed.player.totalBufferedDuration}ms\n")
                }
                append("Playback: $phase")
                failure?.let { append("\n$it") }
            }
        }
        fun restore() {
            if (f1DashCapture !== capture) return
            if (nativeArchiveTiles.isNotEmpty()) stopProtectedCompositionPresentation()
            else {
                selected.forEach { it.feed.clearProtectedCompositionVideoRole() }
                applyVideoBudget()
            }
            decoderMode = settings.decoderMode
            if (settings.decoderMode == DecoderMode.SINGLE) selected.forEach { it.feed.player.playWhenReady = false }
            layoutRequest++
        }
        val probePositionMs = host.feed.player.currentPosition.coerceAtLeast(0)
        selected.forEach { android.util.Log.i("ApexviewPlaybackOffer", it.feed.sourceStream.offerDiagnostic) }
        // Enforce the selected decoder budget immediately, including while validation runs.
        selected.forEach { it.feed.player.playWhenReady = false }
        applyVideoBudget()
        archiveCompositionStarting = true
        message = SINGLE_DECODER_ACTIVATING
        diagnostic()
        f1DashCaptureJob = scope.launch {
            try {
                startup.start(
                    preflight = {
                        // Capture the selected position once. Slow preparation must not chase an
                        // independently advancing source clock or interrupt working playback.
                        val session = capture.prepareVod(selected.map { F1DashFeedCapture.Feed(it.feed.sourceStream.profileLabel,
                            it.feed.sourceStream.url) }, probePositionMs, host.feed.sourceStream.profileLabel,
                            if (externalDisplayRequested) maxOf(720, settings.videoQuality.targetHeight) else
                                minOf(settings.videoQuality.targetHeight.takeIf { it > 0 } ?: 720,
                                    if (selected.size > 4) 270 else if (selected.size > 2) 540 else 720),
                            settings.commentary, minimumHeight = if (externalDisplayRequested) 720 else 0)
                        try {
                            host.feed.requireArchiveHardwareSupport(session.codedWidth, session.codedHeight, session.frameRate * playbackSpeed)
                            prepared = session
                            diagnostic()
                            session
                        } catch (error: Throwable) { session.close(); throw error }
                    },
                    releaseSources = {
                        val before = selected.sumOf { it.feed.activeVideoDecoderCount }
                        selected.forEach { tile -> tile.feed.setProtectedCompositionVideoRole(false,
                            tile.w.toInt().coerceAtLeast(1), tile.h.toInt().coerceAtLeast(1)) }
                        val results = coroutineScope { selected.map { async { it.feed.awaitVideoDecoderRelease(3_000L) } }.awaitAll() }
                        released = (before - selected.sumOf { it.feed.activeVideoDecoderCount }).coerceAtLeast(0)
                        if (results.any { !it } || observedActiveVideoDecoders != 0) throw IOException("Source video decoder release was not confirmed")
                        android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_HANDOFF preflight=PASS observedReleases=$released activeVideoDecoders=0")
                    },
                    replaceHost = { session ->
                        val handoffPositionMs = host.feed.player.currentPosition.coerceAtLeast(0)
                        sceneOutputSize = 0 to 0
                        nativeArchiveTiles = selected
                        showProtectedCompositionPresentation(host, selected, sceneLayout = true)
                        // One Media3 player now loads both the composed video and selected audio.
                        // Stopped originals perform no downloads and cannot move its clock.
                        selected.forEach { it.feed.suspendForArchiveComposition(); it.feed.setAudible(false) }
                        host.feed.startArchiveVodPlayback(session, handoffPositionMs, playing)
                        transferred = true
                        archiveCompositionStarting = false
                        host.feed.setAudible(!mutedAll && !castingActive)
                        decoderMode = DecoderMode.SINGLE
                        phase = "BUFFERING"
                    },
                    restoreSources = { restore() },
                    dispose = { if (!transferred) it.close() },
                )
                var previousLog = ""
                var lastEvidenceAt = 0L
                var readySince: Long? = null
                while (true) {
                    host.feed.problem?.let { throw IOException("Composed playback failed: $it") }
                    if (host.feed.hasRenderedFirstFrame && observedActiveVideoDecoders > 1) throw IOException("Expected one active video decoder; callbacks report $observedActiveVideoDecoders")
                    if (host.feed.hasRenderedFirstFrame && host.feed.activeVideoDecoderHardware == "hardware NO") throw IOException("Composed video selected a software decoder")
                    phase = when {
                        host.feed.player.playbackState == Player.STATE_ENDED -> "ENDED"
                        browsing -> "VIDEO SUSPENDED WHILE BROWSING"
                        observedActiveVideoDecoders == 0 -> "WAITING FOR VIDEO DECODER"
                        host.feed.buffering -> "BUFFERING"
                        !host.feed.hasRenderedFirstFrame -> "WAITING FOR FIRST FRAME"
                        !playing -> "PAUSED"
                        else -> "ACTIVE"
                    }
                    if ((phase == "ACTIVE" || phase == "PAUSED") && message == SINGLE_DECODER_ACTIVATING) message = null
                    if (!browsing && host.feed.player.playbackState == Player.STATE_READY && !host.feed.hasRenderedFirstFrame) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (readySince == null) readySince = now
                        if (now - requireNotNull(readySince) > 15_000L) throw IOException("Decoder was ready but did not render a first frame")
                    } else readySince = null
                    diagnostic()
                    val log = "$phase decoder=${host.feed.activeVideoDecoderName} activeVideoDecoders=$observedActiveVideoDecoders firstFrame=${host.feed.hasRenderedFirstFrame}"
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (log != previousLog || now - lastEvidenceAt >= 5_000L) {
                        android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_PLAYBACK $log renderedFrames=${host.feed.renderedVideoFrames} " +
                            "selectedVideoTracks=${host.feed.selectedVideoTrackCount} sceneOutput=${sceneOutputSize.first}x${sceneOutputSize.second} " +
                            "codecSurface=${host.feed.composedSurfaceWidth}x${host.feed.composedSurfaceHeight} bufferedMs=${host.feed.player.totalBufferedDuration}")
                        previousLog = log
                        lastEvidenceAt = now
                    }
                    kotlinx.coroutines.delay(500L)
                }
            } catch (backend: app.apexview.tiled.ArchiveVodBackendRequired) {
                useExistingBackend = true
                android.util.Log.i("ApexviewSingleDecoder", "Archive requires existing DASH/protected composition backend; sources unchanged")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (startup.sourceDecodersTouched) restore()
                phase = "CANCELLED"
                diagnostic()
                throw cancelled
            } catch (error: Exception) {
                failure = (error.message ?: error.javaClass.simpleName).replace(Regex("https?://\\S+"), "[URL]").take(400)
                phase = "FAILED"
                retryableFailure = when (error) {
                    is app.apexview.tiled.ArchiveVodMediaException -> error.retryable
                    else -> app.apexview.tiled.CaptureFailure.from(error).retryable
                }
                decoderMode = settings.decoderMode
                singleDecoderFailure = failure
                // Preserve the failure evidence before restore cancels this capture's ownership.
                diagnostic()
                if (startup.sourceDecodersTouched || singleDecoderSceneActive) restore() else {
                    selected.forEach { it.feed.player.playWhenReady = false }; applyVideoBudget()
                }
                message = "Single decoder failed: $failure. Use Retry or select Multiple decoders in Settings."
                android.util.Log.w("ApexviewSingleDecoder", "ARCHIVE_VOD_FAILED reason=$failure")
            } finally {
                if (f1DashCapture === capture) {
                    archiveCompositionStarting = false
                    if (!transferred && !useExistingBackend && settings.decoderMode == DecoderMode.MULTIPLE)
                        selected.filter { it in tiles }.forEach { it.feed.player.playWhenReady = playing }
                    capture.reset(); f1DashCapture = null; f1DashCaptureJob = null
                }
            }
            if (useExistingBackend) startRollingArchiveSingleDecoderComposition(contentId, attempt)
            else if (retryableFailure && attempt < SINGLE_DECODER_AUTO_RETRIES && settings.decoderMode == DecoderMode.SINGLE) {
                val originalIds = selected.map { it.id }
                android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_RETRY requested=AUTO attempt=${attempt + 1} delayMs=${500L * (attempt + 1)}")
                singleDecoderRetryJob = scope.launch {
                    kotlinx.coroutines.delay(500L * (attempt + 1))
                    if (settings.decoderMode == DecoderMode.SINGLE && tiles.map { it.id } == originalIds && !singleDecoderSceneActive) {
                        singleDecoderRetryJob = null
                        startArchiveSingleDecoderComposition(contentId, attempt + 1)
                    }
                }
            }
        }
    }

    /** Validate authorised archive media completely before changing any source decoder. */
    private fun startRollingArchiveSingleDecoderComposition(contentId: Long, attempt: Int = 0) {
        val selectedTiles = tiles.filter { it.contentId == contentId }
        val previousLayout = layoutMode
        if (selectedTiles.size !in 2..6 || selectedTiles.size != tiles.size) {
            singleDecoderFailure = "This protected archive backend supports two to six feeds from one archive. Clear archives can use up to 24 when their composed picture fits the decoder."
            message = singleDecoderFailure
            decoderMode = settings.decoderMode
            tiles.forEach { it.feed.player.playWhenReady = false }
            applyVideoBudget()
            return
        }
        val labels = selectedTiles.map { it.feed.sourceStream.profileLabel }
        if (labels.distinct().size != labels.size) {
            singleDecoderFailure = "Single decoder needs different feeds from the same archive."
            message = singleDecoderFailure
            decoderMode = settings.decoderMode
            tiles.forEach { it.feed.player.playWhenReady = false }
            applyVideoBudget()
            return
        }
        val feeds = selectedTiles.zip(labels).map { (tile, label) ->
            F1DashFeedCapture.Feed(label, tile.feed.sourceStream.url)
        }
        val host = selectedTiles.firstOrNull { !it.audible } ?: selectedTiles.first()
        val audioMaster = selectedTiles.firstOrNull { it !== host && it.audible }
            ?: selectedTiles.first { it !== host }
        val group = "archive-content-$contentId-${java.util.UUID.randomUUID()}"
        f1DashCaptureJob?.cancel()
        val capture = F1ArchiveFeedCapture(userAgent, api.ascendon.orEmpty(), api.entitlement.orEmpty())
        f1DashCapture = capture
        ProtectedHevcLiveComposition.selectFeeds(group, labels)
        val startup = ArchiveCompositionStartup()
        var lastSnapshot: F1DashFeedCapture.Snapshot? = null
        var releasedCount = 0
        var playback = "PREFLIGHT"
        var terminalReason: String? = null
        var retryableFailure = false
        var restored = false
        var preflightPassed = false
        var composedFirstFrameSeen = false
        var composedCodec: String? = null

        fun diagnostics() {
            if (f1DashCapture != null && f1DashCapture !== capture) return
            val snapshot = lastSnapshot
            singleDecoderDiagnostic = buildString {
                append("Source: F1 Archive · Selected feeds: ${selectedTiles.size}\n")
                append("Requested platform: " + selectedTiles.map { it.feed.sourceStream.requestedPlatform }.distinct().joinToString() + "\n")
                append("Player: " + selectedTiles.map { it.feed.sourceStream.playerType }.distinct().joinToString() + "\n")
                labels.forEach { label ->
                    append("$label manifest: ${snapshot?.actualManifests?.get(label)?.name ?: "UNVERIFIED"}\n")
                }
                append("HEVC/CMAF preflight: " + if (preflightPassed) "PASS\n" else "${snapshot?.detail ?: "VALIDATING"}\n")
                append("Media protection: ${capture.protection}\n")
                append("Composition: ${snapshot?.compositionStatus ?: "VALIDATING"}\n")
                snapshot?.outputTiming?.let { timing ->
                    val aheadMs = (timing.endUs / 1000L - audioMaster.feed.player.currentPosition).coerceAtLeast(0L)
                    append("Prepared video ahead: ${aheadMs}ms\n")
                }
                if (startup.hostReplaced) append("Player buffered: ${host.feed.player.totalBufferedDuration}ms\n")
                append("Source decoders released (callbacks): $releasedCount\n")
                append("Composed decoder: ${composedCodec ?: "NOT OBSERVED"}\n")
                append("First composed frame: ${if (composedFirstFrameSeen) "YES" else "NO"}\n")
                append("Playback: $playback")
                terminalReason?.let { append("\n$it") }
            }
        }
        fun restore(replacedHost: Boolean) {
            // A newer layout/test may already own these players after cancelling this job.
            if (restored || f1DashCapture !== capture) return
            restored = true
            if (protectedCompositionHost != null) {
                stopProtectedCompositionPresentation()
            } else if (startup.sourceDecodersTouched) {
                if (replacedHost) host.feed.stopProtectedComposedPlayback(audioMaster.feed.player.currentPosition)
                selectedTiles.forEach { it.feed.clearProtectedCompositionVideoRole() }
                applyVideoBudget()
            }
            composedArchiveOriginMs = null
            composedArchiveFillingBuffer = false
            selectedTiles.filter { it in tiles }.forEach {
                it.feed.player.playWhenReady = settings.decoderMode == DecoderMode.MULTIPLE && playing
            }
            layoutMode = previousLayout
            layoutRequest++
        }
        selectedTiles.forEach { android.util.Log.i("ApexviewPlaybackOffer", it.feed.sourceStream.offerDiagnostic) }
        selectedTiles.forEach { it.feed.player.playWhenReady = false }
        archiveCompositionStarting = true
        diagnostics()
        message = SINGLE_DECODER_ACTIVATING
        f1DashCaptureJob = scope.launch {
            try {
                startup.start(
                    preflight = {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
                        var prepared: ProtectedHevcLiveComposition.OutputWindow? = null
                        while (prepared == null) {
                            val snapshot = capture.captureAtPosition(group, feeds, audioMaster.feed.player.currentPosition.coerceAtLeast(0L))
                            lastSnapshot = snapshot
                            diagnostics()
                            snapshot.failure?.takeUnless { it.retryable }?.let { throw app.apexview.tiled.PermanentCaptureException(it.reason) }
                            val clockUs = Math.multiplyExact(audioMaster.feed.player.currentPosition.coerceAtLeast(0L), 1000L)
                            val durationUs = audioMaster.feed.player.duration.takeIf { it > 0L }?.let { Math.multiplyExact(it, 1000L) }
                            if (ArchiveBufferPolicy.ready(snapshot.outputTiming, clockUs, durationUs, minimumLeadUs = 0L)) {
                                prepared = capture.outputWindow(group)
                            }
                            if (prepared == null) {
                                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                                    throw IOException("Archive preflight timed out: ${snapshot.detail ?: "prepared video has insufficient lead over the archive clock"}")
                                }
                                if (snapshot.capturedFeeds == 0) kotlinx.coroutines.delay(250L)
                            }
                        }
                        prepared.also { window ->
                            val licenses = selectedTiles.map { it.feed.sourceStream.licenseUrl }
                            if (!capture.clearOutput && !protectedFeedsShareLicense(licenses)) {
                                window.zero()
                                throw app.apexview.tiled.PermanentCaptureException("Protected feeds need the same authorised Widevine license endpoint.")
                            }
                            // Video tracks are disabled during preflight, so they may not yet
                            // have requested a license. The composed Media3 source below uses
                            // the host's existing F1 license callback and its preserved PSSH.
                            if (!capture.clearOutput) android.util.Log.i("ApexviewSingleDecoder", "WIDEVINE_KEYS requestedBy=COMPOSED_PLAYER")
                            val pssh = capture.widevinePssh()
                            try {
                                app.apexview.tiled.ProtectedHevcComposedDashPackageBuilder.build(
                                    window, pssh, window.fragments.first().baseDecodeTimeTicks,
                                    System.currentTimeMillis(), clearOutput = capture.clearOutput,
                                ).zero()
                                preflightPassed = true
                            } catch (error: Exception) {
                                window.zero()
                                throw IOException("Composed source initialization failed: ${error.message}")
                            } finally { pssh?.fill(0) }
                        }
                    },
                    releaseSources = {
                        val decodersBefore = selectedTiles.sumOf { it.feed.activeVideoDecoderCount }
                        selectedTiles.forEach { tile ->
                            tile.feed.setProtectedCompositionVideoRole(false,
                                (tile.w * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                                (tile.h * context.resources.displayMetrics.density).toInt().coerceAtLeast(1))
                        }
                        val releaseResults = coroutineScope {
                            selectedTiles.map { tile -> async { tile.feed.awaitVideoDecoderRelease(3_000L) } }.awaitAll()
                        }
                        releasedCount = (decodersBefore - selectedTiles.sumOf { it.feed.activeVideoDecoderCount }).coerceAtLeast(0)
                        if (releaseResults.any { !it }) throw IOException("Decoder release failed for ${releaseResults.count { !it }} source players")
                        android.util.Log.i("ApexviewSingleDecoder", "Preflight PASS. All source players decoder-free; observed releases=$releasedCount.")
                    },
                    replaceHost = { window ->
                        if (host.audible) setAudio(audioMaster)
                        composedArchiveOriginMs = requireNotNull(lastSnapshot?.outputTiming).startUs / 1000L
                        composedArchiveFillingBuffer = true
                        // The validated picture covers this position. Hold the audio clock while
                        // actual queued media fills the initial buffer, then resume both together.
                        selectedTiles.forEach { it.feed.player.playWhenReady = false }
                        host.feed.startProtectedComposedPlayback(window, capture.widevinePssh(),
                            clearOutput = capture.clearOutput,
                            startPositionMs = (audioMaster.feed.player.currentPosition - requireNotNull(composedArchiveOriginMs)).coerceAtLeast(0L),
                            playWhenReady = false)
                        archiveCompositionStarting = false
                        layoutMode = LayoutMode.SINGLE_DECODER_TEST
                        showProtectedCompositionPresentation(host, selectedTiles, audioMaster)
                    },
                    restoreSources = ::restore,
                    dispose = { it.zero() },
                )
                var waitingSince: Long? = null
                while (true) {
                    host.feed.problem?.let { throw IOException("Composed decoder/DRM playback failed: $it") }
                    if (!browsing && !host.feed.hasRenderedFirstFrame) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (waitingSince == null) waitingSince = now
                        if (now - requireNotNull(waitingSince) >= 15_000L) throw IOException("Composed decoder did not render a first frame within 15 seconds")
                    } else waitingSince = null
                    composedFirstFrameSeen = host.feed.hasRenderedFirstFrame
                    host.feed.activeVideoDecoderName?.let { composedCodec = "$it (${host.feed.activeVideoDecoderHardware})" }
                    playback = when {
                        composedArchiveFillingBuffer || host.feed.buffering -> "BUFFERING"
                        composedFirstFrameSeen -> "ACTIVE"
                        else -> "WAITING FOR FIRST FRAME"
                    }
                    if (playback == "ACTIVE" && message == SINGLE_DECODER_ACTIVATING) message = null
                    val snapshot = capture.captureAtPosition(group, feeds, audioMaster.feed.player.currentPosition.coerceAtLeast(0L))
                    lastSnapshot = snapshot
                    snapshot.failure?.takeUnless { it.retryable }?.let { throw IOException(it.reason) }
                    if (snapshot.capturedFeeds > 0) capture.outputWindow(group)?.let {
                        host.feed.publishProtectedComposedOutput(it, capture.widevinePssh())
                    }
                    if (composedArchiveFillingBuffer && ArchiveBufferPolicy.ready(snapshot.outputTiming,
                            Math.multiplyExact(audioMaster.feed.player.currentPosition.coerceAtLeast(0L), 1000L),
                            audioMaster.feed.player.duration.takeIf { it > 0 }?.let { Math.multiplyExact(it, 1000L) })) {
                        composedArchiveFillingBuffer = false
                    }
                    diagnostics()
                    if (audioMaster.feed.player.playbackState == Player.STATE_ENDED) {
                        playback = "ENDED"
                        restore(true)
                        diagnostics()
                        break
                    }
                    if (snapshot.capturedFeeds == 0) kotlinx.coroutines.delay(250L)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (startup.sourceDecodersTouched) restore(startup.hostReplaced)
                playback = "CANCELLED"
                diagnostics()
                throw cancelled
            } catch (error: Exception) {
                // Do not log raw network exceptions or their signed URLs.
                terminalReason = (error.message ?: error.javaClass.simpleName)
                    .replace(Regex("https?://\\S+"), "[redacted URL]").take(400)
                retryableFailure = app.apexview.tiled.CaptureFailure.from(error).retryable
                if (startup.sourceDecodersTouched) restore(startup.hostReplaced) else {
                    selectedTiles.forEach { it.feed.clearProtectedCompositionVideoRole() }
                    applyVideoBudget()
                }
                decoderMode = settings.decoderMode
                singleDecoderFailure = terminalReason
                selectedTiles.forEach { it.feed.player.playWhenReady = false }
                applyVideoBudget()
                playback = "FAILED"
                diagnostics()
                android.util.Log.w("ApexviewSingleDecoder", "Archive composition failed: $terminalReason")
                message = "Single decoder failed: $terminalReason. Use Retry or select Multiple decoders in Settings."
            } finally {
                if (f1DashCapture === capture) {
                    archiveCompositionStarting = false
                    if (!startup.sourceDecodersTouched && settings.decoderMode == DecoderMode.MULTIPLE)
                        selectedTiles.filter { it in tiles }.forEach { it.feed.player.playWhenReady = playing }
                    capture.reset()
                    f1DashCapture = null
                    f1DashCaptureJob = null
                    ProtectedHevcLiveComposition.clearSelection(group)
                }
            }
            if (retryableFailure && attempt < SINGLE_DECODER_AUTO_RETRIES && settings.decoderMode == DecoderMode.SINGLE) {
                val originalIds = selectedTiles.map { it.id }
                android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_RETRY requested=AUTO attempt=${attempt + 1} delayMs=${500L * (attempt + 1)} backend=PROTECTED")
                singleDecoderRetryJob = scope.launch {
                    kotlinx.coroutines.delay(500L * (attempt + 1))
                    if (settings.decoderMode == DecoderMode.SINGLE && tiles.map { it.id } == originalIds && protectedCompositionHost == null) {
                        singleDecoderRetryJob = null
                        startArchiveSingleDecoderComposition(contentId, attempt + 1)
                    }
                }
            }
        }
    }

    /** Start the protected one-decoder path for a normal Android TV live multiview session. */
    private fun startTvLiveCompositionIfEligible(contentId: Long) {
        if (decoderMode == DecoderMode.MULTIPLE || !isTv || layoutMode == LayoutMode.TILE_TEST || layoutMode == LayoutMode.SINGLE_DECODER_TEST) return
        // A feed was added to an already composed session. Restore the source players and layout
        // before rebuilding the selected-feed set, otherwise the old grid host remains detached.
        if (protectedCompositionHost != null) stopProtectedCompositionPresentation()
        val isKnownLive = contentId in knownLiveContentIds || liveNow.any { it.contentId == contentId }
        if (!isKnownLive) return
        val sessionTiles = tiles.filter { it.contentId == contentId }
        if (sessionTiles.size !in 2..6) {
            if (sessionTiles.size > 6) {
                message = "This protected live composition supports up to six feeds on this playback path. Clear archive compositions can use up to 24 when the combined picture fits the decoder."
                sessionTiles.forEach { it.feed.player.playWhenReady = false }
                applyVideoBudget()
            }
            return
        }
        val labels = sessionTiles.map { tile ->
            tile.channel.channelId?.let { "channel-$it" } ?: "default-channel"
        }
        if (labels.distinct().size != labels.size) return
        val group = "content-$contentId"
        ProtectedHevcLiveComposition.selectFeeds(group, labels)
        startRealF1DashCapture(contentId, labels)
    }

    /**
     * F1 Tizen's live grid fetches each feed's init and aligned media segments independently,
     * then stitches them into one HEVC representation. This is the Android/Media3 equivalent
     * acquisition stage: direct DASH DataSource requests only, never an extra player or decoder.
     */
    private fun startRealF1DashCapture(contentId: Long, labels: List<String>) {
        val byLabel = tiles.filter { it.contentId == contentId }.associateBy {
            it.channel.channelId?.let { id -> "channel-$id" } ?: "default-channel"
        }
        val feeds = labels.mapNotNull { label ->
            byLabel[label]?.let { tile ->
                F1DashFeedCapture.Feed(label, tile.feed.sourceStream.url)
            }
        }
        if (feeds.size != labels.size || feeds.size !in 2..6) {
            message = "Could not start F1 tile capture: selected feed URLs are not all available."
            return
        }
        f1DashCaptureJob?.cancel()
        f1DashCapture?.reset()
        val capture = F1DashFeedCapture(
            userAgent = userAgent,
            ascendon = api.ascendon.orEmpty(),
            entitlement = api.entitlement.orEmpty(),
        )
        f1DashCapture = capture
        val group = "content-$contentId"
        val selectedTiles = labels.mapNotNull { byLabel[it] }
        val host = selectedTiles.firstOrNull { !it.audible } ?: selectedTiles.first()
        val audioTile = selectedTiles.firstOrNull { it !== host && it.audible }
        if (audioTile != null && host.audible) setAudio(audioTile)

        f1DashCaptureJob = scope.launch {
            var lastMessage: String? = null
            var composedPlaybackStarted = false
            while (true) {
                val snapshot = capture.captureNext(group, feeds)
                val outputWindow = ProtectedHevcLiveComposition.outputWindow(group)
                if (outputWindow != null) {
                    val pssh = capture.widevinePssh()
                    try {
                        if (!composedPlaybackStarted) {
                            // Keep each source feed audio-only, then wait for its video decoder
                            // to release before preparing the single composed video representation.
                            selectedTiles.forEach { tile ->
                                tile.feed.setProtectedCompositionVideoRole(
                                    enabled = false,
                                    width = (tile.w * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                                    height = (tile.h * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                                )
                            }
                            val released = selectedTiles.all {
                                it.feed.awaitVideoDecoderRelease(2_500L)
                            }
                            if (!released) {
                                outputWindow.zero()
                                pssh?.fill(0)
                                message = "Waiting for the source video decoder to release before starting the composed grid."
                            } else {
                                host.feed.startProtectedComposedPlayback(outputWindow, pssh)
                                showProtectedCompositionPresentation(host, selectedTiles, audioTile)
                                composedPlaybackStarted = true
                            }
                        } else {
                            host.feed.publishProtectedComposedOutput(outputWindow, pssh)
                        }
                    } catch (error: Exception) {
                        outputWindow.zero()
                        pssh?.fill(0)
                        message = "Protected composed playback: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
                val nextMessage = when {
                    snapshot.detail != null -> "F1 feed capture: ${snapshot.detail}"
                    composedPlaybackStarted ->
                        "Single-decoder protected F1 grid playing. ${snapshot.compositionStatus}"
                    snapshot.capturedFeeds > 0 ->
                        "Real F1 CMAF captured from ${snapshot.capturedFeeds} feeds. ${snapshot.compositionStatus}"
                    else -> "F1 feed capture: ${snapshot.compositionStatus}"
                }
                if (nextMessage != lastMessage) {
                    message = nextMessage
                    lastMessage = nextMessage
                }
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    /**
     * Present the composed video as one full-canvas tile. Other selected tiles are hidden visually,
     * but their audio players keep running so the audible broadcast is preserved.
     */
    private fun showProtectedCompositionPresentation(
        host: Tile,
        selected: List<Tile>,
        audioMaster: Tile? = null,
        sceneLayout: Boolean = false,
    ) {
        if (protectedCompositionHost != null) return
        protectedCompositionHost = host
        protectedCompositionAudioMaster = audioMaster
        protectedCompositionBounds = if (sceneLayout) emptyMap() else selected.associateWith { tile ->
            ProtectedTileBounds(tile.x, tile.y, tile.w, tile.h, tile.z)
        }
        selected.forEach { it.protectedCompositionHidden = !sceneLayout && it !== host }
        if (sceneLayout) { layoutRequest++; return }
        host.x = 0f
        host.y = 0f
        host.w = canvasW.takeIf { it > 0f } ?: host.w
        host.h = canvasH.takeIf { it > 0f } ?: host.h
        topZ += 1f
        host.z = topZ
        layoutRequest++
    }

    /** Stop protected capture and restore the original tile arrangement and feed player. */
    fun stopProtectedCompositionPresentation(restorePlayer: Boolean = true, keepSourceVideoDisabled: Boolean = false) {
        val wasStarting = archiveCompositionStarting
        archiveCompositionStarting = false
        f1DashCaptureJob?.cancel()
        f1DashCaptureJob = null
        f1DashCapture?.reset()
        f1DashCapture = null
        val native = nativeArchiveTiles
        val resumePositionMs = if (native.isNotEmpty()) protectedCompositionHost?.feed?.player?.currentPosition
            else protectedCompositionAudioMaster?.feed?.player?.currentPosition
        if (restorePlayer) {
            if (native.isNotEmpty()) native.forEach {
                it.feed.stopProtectedComposedPlayback(resumePositionMs, playing, enableVideo = false)
                it.feed.setAudible(it.audible && !mutedAll && !castingActive)
            }
            else protectedCompositionHost?.feed?.stopProtectedComposedPlayback(resumePositionMs)
        }
        nativeArchiveTiles = emptyList()
        protectedCompositionAudioMaster = null
        composedArchiveOriginMs = null
        composedArchiveFillingBuffer = false
        tiles.forEach { tile ->
            tile.protectedCompositionHidden = false
            tile.feed.clearProtectedCompositionVideoRole()
            protectedCompositionBounds[tile]?.let { bounds ->
                tile.x = bounds.x
                tile.y = bounds.y
                tile.w = bounds.w
                tile.h = bounds.h
                tile.z = bounds.z
            }
        }
        protectedCompositionBounds = emptyMap()
        protectedCompositionHost = null
        ProtectedHevcLiveComposition.clearAll()
        HevcFeedProfileCapture.clearTileTestSelection()
        if (keepSourceVideoDisabled) tiles.forEach { it.feed.setProtectedCompositionVideoRole(false, it.w.toInt().coerceAtLeast(1), it.h.toInt().coerceAtLeast(1)) }
        else applyVideoBudget()
        if (restorePlayer && wasStarting && native.isEmpty() && !keepSourceVideoDisabled && settings.decoderMode == DecoderMode.MULTIPLE) {
            tiles.forEach { it.feed.player.playWhenReady = playing }
        }
        layoutRequest++
    }

    /** Single-decoder selections are still checked against actual renditions and decoder capacity. */
    val feedSelectionLimit get() = if (settings.decoderMode == DecoderMode.SINGLE) MAX_SINGLE_DECODER_FEEDS else MAX_FEEDS
    private fun feedLimitNote(limit: Int) = "Up to $limit feeds can be selected in this mode. Unpick one to make room."
    val roomForFeeds get() = (feedSelectionLimit - tiles.size).coerceAtLeast(0)

    private fun addFeeds(contentId: Long, requested: List<Channel>, fromStart: Boolean = false, added: (Tile) -> Unit = {}) {
        if (requested.isEmpty()) return
        if (roomForFeeds <= 0) {
            message = feedLimitNote(feedSelectionLimit)
            return
        }
        if (settings.decoderMode == DecoderMode.SINGLE && tiles.any { it.contentId != contentId }) {
            message = "Single decoder needs feeds from one archive. Choose Multiple decoders to combine sessions."
            return
        }
        val picks = requested.take(roomForFeeds)
        val dropped = requested.size - picks.size
        task("Starting feeds") {
            val failures = mutableListOf<String>()
            // The catalog already tells us whether this is live or a replay. Start timing
            // immediately instead of waiting for every CONTENT/PLAY request and video prepare.
            // For entries without a catalog classification, the timing job waits for the real
            // Media3 timeline while feed creation proceeds in parallel.
            if (timingContentId != contentId || (timing == null && liveFeed?.isActive != true)) {
                loadTiming(contentId)
            }
            val streams = picks.mapNotNull { channel ->
                try {
                    channel to streamFor(contentId, channel, failures)
                } catch (e: Exception) {
                    failures += "${channel.title}: ${e.message}"
                    null
                }
            }
            if (streams.isEmpty()) {
                if (!hasVideoPlan) {
                    planPromptOpen = true
                    message = null
                } else message = "No feed would start.\n" + failures.joinToString("\n")
                return@task
            }

            // New feeds join at the moment already playing, rather than starting at zero and
            // waiting for the sync loop to jump them forward.
            val joinAt = leaderTile()?.takeIf { it.contentId == contentId && !it.feed.player.isCurrentMediaItemLive }
                ?.feed?.player?.currentPosition
            // A rejected offer leaves the working composition untouched. Change ownership only
            // after the service has supplied an actual replacement source and its join clock.
            if (singleDecoderSceneActive) stopProtectedCompositionPresentation(keepSourceVideoDisabled = true)
            if (settings.decoderMode == DecoderMode.SINGLE) decoderMode = DecoderMode.SINGLE
            val first = tiles.isEmpty()
            val preparingSingle = decoderMode == DecoderMode.SINGLE && (tiles.isNotEmpty() || streams.size > 1)
            if (preparingSingle) tiles.forEach { it.feed.player.playWhenReady = false }
            // A press conference or a show has no cars to follow: its map starts off, without
            // touching the style saved for sessions.
            if (first && pendingResume?.contentId != contentId) mapMode = if (contentId in notSessions) MapMode.OFF else savedMapMode()
            // A feed added while one fills the screen brings the whole layout back.
            focusTileId = null
            streams.forEach { (channel, stream) ->
                val feed = newFeed(stream, startWithVideo = !isTv && (decoderMode != DecoderMode.SINGLE || tiles.isEmpty() && streams.size == 1))
                if (preparingSingle) feed.player.playWhenReady = false
                if (decoderMode == DecoderMode.SINGLE && (tiles.isNotEmpty() || streams.size > 1))
                    feed.setProtectedCompositionVideoRole(false, 1, 1)
                // From the start: the first moment F1 keeps, so nothing after it is seen first.
                if (fromStart && joinAt == null) feed.startFromBeginning()
                joinAt?.let(feed.player::seekTo)
                val tile = Tile(nextTileId++, contentId, channel, feed)
                tiles.add(tile)
                raise(tile)
                added(tile)
            }
            // A fresh start uses the layout picked as default (Save layout makes it the custom one).
            if (first && pendingResume?.contentId != contentId) {
                layoutMode = defaultLayoutMode
                if (layoutMode == LayoutMode.TV) {
                    mapMode = MapMode.TRACK
                    towerOpen = true
                    overlayLayoutMode = OverlayLayoutMode.DOCKED
                    tvLayoutMapNeedsDefault = !mapPanel.placed && prefs.getString(PREF_MAP_PANEL, null).isNullOrBlank()
                    prefs.edit()
                        .putString(PREF_MAP_MODE, MapMode.TRACK.name)
                        .putBoolean(PREF_TOWER, true)
                        .putString(PREF_OVERLAY_LAYOUT, OverlayLayoutMode.DOCKED.name)
                        .apply()
                }
            }
            // Keep the audio where it was; new feeds start muted. The default feed gets it first.
            setAudio(tiles.firstOrNull { it.audible } ?: tiles.firstOrNull { it.channel.isDefault } ?: tiles.first())
            pendingResume?.takeIf { it.contentId == contentId }?.let { saved ->
                pendingResume = null
                tiles.firstOrNull { it.channel.key() == saved.audioChannel }?.let(::setAudio)
                tiles.firstOrNull { it.channel.key() == saved.bigChannel }?.let { bigTileId = it.id }
                // A live session comes back at the live edge; a replay at the saved moment.
                if (!saved.live) tiles.forEach { it.feed.player.seekTo(saved.positionMs) }
            }
            // A feed more means a fresh fit of the layout in use; the map stays where it is.
            handPlaced = false
            layoutRequest++
            showChrome()
            if (dropped > 0) failures += "Left out $dropped feed(s): this mode allows up to $feedSelectionLimit selections"
            message = if (failures.isEmpty()) null else "Some feeds had problems:\n" + failures.joinToString("\n")
            leaderTile()?.let { leader ->
                // A prior timing job can finish while retaining the same content ID. Restart it
                // instead of leaving the TV's map sheet permanently at its initial Loading text.
                if (timingContentId != leader.contentId || (timing == null && liveFeed?.isActive != true)) {
                    loadTiming(leader.contentId)
                }
                applyVideoBudget()
            }
            // Normal Android TV live multiview also uses the composed representation. This is
            // not limited to the two-feed diagnostic tile-test workflow.
            if (decoderMode == DecoderMode.SINGLE && tiles.size >= 2) startArchiveSingleDecoderComposition(contentId)
            else startTvLiveCompositionIfEligible(contentId)
        }
    }

    private fun newFeed(
        stream: PlayInfo,
        startMs: Long = C.TIME_UNSET,
        startWithVideo: Boolean = !isTv,
    ) = makeFeed(stream, startMs, startWithVideo).also { it.setBaseSpeed(playbackSpeed) }

    private fun makeFeed(stream: PlayInfo, startMs: Long, startWithVideo: Boolean) = FeedPlayer(
        context, stream,
        ascendon = api.ascendon.orEmpty(),
        entitlement = api.entitlement.orEmpty(),
        userAgent = userAgent,
        forceL3 = settings.softwareDrm,
        subtitles = settings.subtitles,
        commentary = settings.commentary,
        startWithVideo = startWithVideo && !castPlaybackHandedOff,
        videoQuality = settings.videoQuality,
        startMs = startMs,
    ).also { if (castPlaybackHandedOff) it.suspendForRemotePlayback(true) }

    /**
     * A second PLAY request can end the first session, so every feed of a content comes from one
     * multiview session, as in F1's web player and eXViewer. Falls back to one session per feed
     * if F1 refuses the multiview session.
     */
    private suspend fun streamFor(contentId: Long, channel: Channel, failures: MutableList<String>): PlayInfo {
        if (contentId !in singleSessionOnly && contentId !in sessions) {
            val list = channelsByContent[contentId].orEmpty()
            val default = list.firstOrNull { it.isDefault } ?: list.firstOrNull()
            try {
                sessions[contentId] = api.multiPlay(contentId, default?.channelId)
            } catch (e: Exception) {
                singleSessionOnly += contentId
                failures += "Multiview session refused, using one session per feed: ${e.message}"
            }
        }
        val session = sessions[contentId] ?: return api.contentPlay(contentId, channel.channelId, fallback = true)

        val url = if (channel.channelId == null) session.feeds.values.firstOrNull()
        else session.feeds[channel.channelId]
        return session.stream(
            url ?: throw IOException(
                if (singleFeedPlan) "F1 didn't send this feed to your plan. Watching several at once needs F1 TV Premium."
                else "F1 returned no stream for this channel"
            ),
            channel.channelId,
            contentId
        )
    }

    // Track map.

    /**
     * The map for the session on screen: live timing for a live stream, else the archive. Any
     * failure (no login yet, a dropped download, F1 not answering) tries again after a pause
     * rather than leaving the map empty for the rest of the session, until it loads or the
     * viewer moves on.
     */
    private fun loadTiming(contentId: Long) {
        timingContentId = contentId
        recordTimingDiagnostic("Starting timing load for content=$contentId", reset = true)
        pitDriver = null
        timing = null
        speedsJob?.cancel()
        mapStatus = "Resolving F1 session…"
        liveFeed?.cancel()
        liveFeed = scope.launch {
            var attempt = 0
            while (timingContentId == contentId) {
                val ref = sessionRefs[contentId]
                val token = api.ascendon
                val problem = when {
                    ref == null -> "F1 TV didn't say which session this is"
                    token == null -> "not signed in to F1's timing yet"
                    else -> null
                }
                recordTimingDiagnostic(
                    "Session metadata: " + (ref?.let { "meeting=${it.meetingKey}, session=${it.sessionKey}, year=${it.year}" } ?: "missing") +
                        "; timing token=" + if (token == null) "missing" else "present"
                )
                if (problem != null) recordTimingDiagnostic("BLOCKED: $problem")
                if (problem == null) {
                    // Prefer F1's catalog signal for a known-live session. Otherwise wait for a
                    // real, non-placeholder player window before choosing live timing or archive.
                    val live = catalogTimingSource(
                        knownLive = contentId in knownLiveContentIds,
                        knownArchive = contentId in knownArchiveContentIds,
                        completedLive = contentId in completedLiveTimingContentIds,
                    ) ?: isLiveStream(contentId)
                    if (live == null) {
                        mapStatus = "Waiting for the video stream to identify live timing or replay…"
                        android.util.Log.i("ApexviewTiming", "content=$contentId: player timeline not ready; keeping timing source undecided")
                        kotlinx.coroutines.delay(1_000)
                        continue
                    }
                    recordTimingDiagnostic("Source selected: " + if (live) "LIVE" else "ARCHIVE")
                    if (live) {
                        // Show the transport stage immediately. A TV with no program clock used to
                        // sit on the generic "Loading" label while the socket was still starting.
                        mapStatus = "Connecting to F1 live timing…"
                        android.util.Log.i(
                            "ApexviewTiming",
                            "content=$contentId live source; meeting=${ref!!.meetingKey}, session=${ref.sessionKey}, circuit=${ref.circuitKey}",
                        )
                        // Runs until the viewer moves on, reconnecting itself; back here only
                        // if it failed outright or F1 ends this live timing session.
                        followLive(contentId, ref, token!!)
                        if (timingContentId != contentId) return@launch
                        kotlinx.coroutines.delay(mapRetryDelayMs(attempt++))
                        continue
                    }
                    mapStatus = "Loading F1 timing archive…"
                    try {
                        // When the picture was broadcast: identifies the session if the title can't.
                        val watched = leaderTile()?.takeIf { it.contentId == contentId }?.feed?.wallClockMs()
                        recordTimingDiagnostic(
                            "Archive lookup: videoClock=${watched ?: "unavailable"}, catalogStart=${ref!!.sessionStartUtcMs ?: "unavailable"}"
                        )
                        val loaded = LiveTimingArchive(token!!, userAgent).load(
                            ref = ref!!,
                            watchedUtc = watched,
                            onProgress = { status ->
                                if (timingContentId == contentId) {
                                    mapStatus = status
                                    recordTimingDiagnostic(status)
                                }
                            },
                            onPartial = { partial ->
                                if (timingContentId == contentId) {
                                    rememberOutline(partial)
                                    showTiming(partial)
                                    recordTimingDiagnostic(
                                        "Partial data: drivers=${partial.drivers.size}, laps=${partial.laps.size}, " +
                                            "cars=${partial.tracks.size}, outlinePoints=${partial.outline.size / 2}"
                                    )
                                    mapStatus = when {
                                        partial.outline.size >= 8 -> null
                                        partial.tracks.isEmpty() -> "Loading track positions…"
                                        else -> "Track data loaded; waiting for a usable circuit outline…"
                                    }
                                }
                            },
                        )
                        if (timingContentId != contentId) return@launch
                        rememberOutline(loaded)
                        showTiming(loaded)
                        recordTimingDiagnostic(
                            "Archive complete: drivers=${loaded.drivers.size}, laps=${loaded.laps.size}, " +
                                "cars=${loaded.tracks.size}, outlinePoints=${loaded.outline.size / 2}"
                        )
                        mapStatus = if (loaded.outline.isEmpty()) "Couldn't trace the track; showing cars only" else null
                        return@launch
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (timingContentId != contentId) return@launch
                        recordTimingDiagnostic("ARCHIVE ERROR: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                        mapStatus = "Archive failed: ${e.javaClass.simpleName}: ${e.message}. Retrying…"
                    }
                } else {
                    mapStatus = "Map unavailable: $problem. Trying again…"
                }
                kotlinx.coroutines.delay(mapRetryDelayMs(attempt++))
            }
        }
    }

    private var speedsJob: kotlinx.coroutines.Job? = null
    private var speedsFor: SessionTiming? = null

    /** A replay's car speeds, fetched the first time an onboard wants them. Without them, no speed shows. */
    fun loadSpeeds(session: SessionTiming) {
        if (session.live || speedsFor === session) return
        val token = api.ascendon ?: return
        speedsFor = session
        speedsJob?.cancel()
        speedsJob = scope.launch {
            try {
                session.speeds = LiveTimingArchive(token, userAgent).loadSpeeds(session.radioBase)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ApexviewLive", "car data: ${e.message}")
            }
        }
    }

    /** The map's job for the session on screen: an archive load, or a live feed that runs on. */
    private var liveFeed: kotlinx.coroutines.Job? = null

    /**
     * Whether the stream on screen is live. Known once the player has read the stream's playlist,
     * well before it's ready to play; a slow start used to be taken for a replay after 15 s, and
     * a live session then looked for its map in the archive, where it isn't yet.
     */
    private suspend fun isLiveStream(contentId: Long): Boolean? {
        // FeedPlayer creates its ExoPlayer immediately; the asynchronous part is receiving a usable
        // timeline. A placeholder window is not evidence of a replay and must never trigger an
        // archive lookup for a live session.
        repeat(LIVE_CHECK_TRIES) { attempt ->
            if (timingContentId != contentId) return null
            val player = leaderTile()
                ?.takeIf { it.contentId == contentId }
                ?.feed
                ?.player
            if (player != null) {
                val timeline = player.currentTimeline
                if (!timeline.isEmpty) {
                    val index = player.currentMediaItemIndex.takeIf { it in 0 until timeline.windowCount } ?: 0
                    val window = runCatching { timeline.getWindow(index, Timeline.Window()) }.getOrNull()
                    if (window != null) {
                        val source = liveTimingSourceFor(
                            knownLive = false,
                            hasTimeline = true,
                            placeholder = window.isPlaceholder,
                            playerReportsLive = player.isCurrentMediaItemLive,
                        )
                        if (source != null) {
                            android.util.Log.i(
                                "ApexviewTiming",
                                "content=$contentId: window live=${player.isCurrentMediaItemLive}, placeholder=${window.isPlaceholder}, state=${player.playbackState}",
                            )
                            return source
                        }
                    }
                }
            }
            if (attempt == 20 && timingContentId == contentId) {
                mapStatus = "Waiting for the TV stream's video timeline…"
                recordTimingDiagnostic("No usable player timeline after 5s; checking again")
            }
            kotlinx.coroutines.delay(250)
        }
        recordTimingDiagnostic("No usable video timeline after 10s; source remains undecided")
        return null
    }

    /** Follows a live session through F1's live timing until the viewer closes or moves on. */
    private suspend fun followLive(contentId: Long, ref: SessionRef, token: String) {
        val key = ref.circuitKey
        val known = key?.let { readOutline(it) }
        val feed = LiveTimingFeed(context, token, userAgent, ref, known)
        var kept = known != null
        try {
            feed.run(
                onTiming = { live ->
                    if (timingContentId == contentId) {
                        rememberStartLine(live)
                        showTiming(live)
                        offerVote(contentId, live)
                        // A track traced live is kept, for the replay and the next session there.
                        if (!kept && key != null) feed.newOutline?.let { writeOutline(key, it); kept = true }
                    }
                },
                onStatus = { status ->
                    if (timingContentId == contentId) {
                        mapStatus = status
                        recordTimingDiagnostic("LIVE: $status")
                    }
                },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (timingContentId == contentId) {
                recordTimingDiagnostic("LIVE TIMING ERROR: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                if (e.message?.contains("live timing moved on to another session") == true) {
                    knownLiveContentIds -= contentId
                    completedLiveTimingContentIds += contentId
                    mapStatus = "Live session ended. Loading its timing archive…"
                    android.util.Log.i("ApexviewTiming", "content=$contentId: live timing moved on; switching to archive")
                } else {
                    mapStatus = "Live map unavailable: ${e.message}. Trying again…"
                    android.util.Log.w("ApexviewTiming", "content=$contentId: live timing failed", e)
                }
            }
        }
    }

    /** Puts a session's timing on screen; the first time, the map gets its place. */
    private fun showTiming(loaded: SessionTiming) {
        val first = timing == null
        timing = loaded
        if (!first) return
        // A tower reservation is a direct layout constraint, even if the user previously
        // arranged feeds by hand. A map-only reservation retains its existing hand-layout rule.
        val reserveTvLayout = layoutMode == LayoutMode.TV && !laptopActive
        val reserveTower = towerOpen && towerReserve && !laptopActive
        val reserveMap = mapMode.inWindow && mapReserve
        if (reserveTvLayout || reserveTower || reserveMap && !handPlaced) {
            handPlaced = false
            layoutRequest++
        } else if (canvasW > 0f) placeMapIfNeeded(canvasW, canvasH)
    }

    // Circuits traced from replays, kept per circuit so a live session there has its map at once.

    private fun outlineFile(circuitKey: Int) = java.io.File(context.filesDir, "outlines/$circuitKey.json")

    private fun readOutline(circuitKey: Int): SavedOutline? =
        runCatching { SavedOutline.fromJson(outlineFile(circuitKey).readText()) }.getOrNull()

    private fun writeOutline(circuitKey: Int, outline: SavedOutline) {
        runCatching {
            outlineFile(circuitKey).apply { parentFile?.mkdirs() }.writeText(outline.toJson())
        }
    }

    private fun rememberOutline(loaded: SessionTiming) {
        val key = loaded.circuitKey ?: return
        if (loaded.hasStartFinish && loaded.outline.size >= 8) writeOutline(key, SavedOutline(loaded.outline, true))
    }

    /**
     * The session time (UTC ms) of the leading tile's picture. Uses the stream's own clock when it
     * has one; otherwise guesses from the timing feed's start and lets the user line it up.
     */
    fun mapTimeMs(): Long? {
        val leader = leaderTile()?.feed ?: return null
        val clock = leader.wallClockMs()
        mapClockEstimated = clock == null
        val position = if (castPlaybackHandedOff) castFramePositionMs else leader.player.currentPosition
        val base = if (clock != null) clock + position - leader.player.currentPosition
            else (timing?.startUtcMs ?: return null) + position
        return base + mapOffsetMs
    }

    /**
     * Where the chequered flags go on the scrubber, as positions in the leading feed: lights out
     * (or the session going green) and the chequered flag. The reverse of [mapTimeMs].
     */
    fun flagPositions(): List<Long> {
        val t = timing ?: return emptyList()
        val leader = leaderTile()?.feed ?: return emptyList()
        if (leader.player.isCurrentMediaItemLive) return emptyList()
        val zero = leader.windowStartMs() ?: t.startUtcMs
        return listOfNotNull(t.startedUtc, t.finishedUtc)
            .map { it - mapOffsetMs - zero }
            .filter { it in 0..durationMs }
    }

    /**
     * A start line seen in a race is kept for its circuit, so qualifying and practice there show
     * it too. Fractions of a lap from the finish line, so it carries between sessions.
     */
    private fun rememberStartLine(loaded: SessionTiming) {
        val key = loaded.circuitKey ?: return
        if (!loaded.hasStartFinish) return
        val found = loaded.startLine
        if (found != null) prefs.edit().putFloat(PREF_START_LINE + key, found).apply()
        else if (prefs.contains(PREF_START_LINE + key)) loaded.startLine = prefs.getFloat(PREF_START_LINE + key, 0f)
    }

    /**
     * Tapping a feed while there's a map marks it as the one the next car tapped replaces. It
     * glows for a few seconds; tapping it again lets it go.
     */
    fun targetForMap(tile: Tile) {
        // The pit circle's cars choose its driver rather than an onboard.
        if (timing == null || mapMode == MapMode.OFF || mapMode == MapMode.PIT || isMainTile(tile)) return
        targetTile = if (targetTile === tile) null else tile
        targetCount++
    }

    /**
     * The big feed of main + strip (and the saved layout), or the only feed, which cars picked on
     * the map leave alone: double-tap a small one to bring it up instead. Grid has no main feed.
     */
    fun isMainTile(tile: Tile): Boolean {
        if (tiles.size == 1) return true
        if (layoutMode == LayoutMode.GRID || layoutMode == LayoutMode.TILE_TEST || layoutMode == LayoutMode.SINGLE_DECODER_TEST) return false
        val big = tiles.firstOrNull { it.id == bigTileId } ?: tiles.maxByOrNull { it.w }
        return tile === big
    }

    fun releaseTarget(tile: Tile) {
        if (targetTile === tile) targetTile = null
    }

    /**
     * Puts car [number]'s onboard on screen, always in place of a feed already there, so the
     * layout never moves. The feed tapped just before (glowing) goes first. Without one, the
     * least interesting unpinned onboard: a car in the pits or knocked out first, then one on an
     * out or cool-down lap, then a car on a flying lap; ties go to the tile picked longest ago.
     * Non-onboard feeds (F1 Live, data) and the tile with the audio are kept.
     */
    fun showOnboard(number: String) {
        val leader = leaderTile() ?: return
        val channel = channelsByContent[leader.contentId]?.firstOrNull { it.racingNumber?.toString() == number }
        if (channel == null) {
            message = "No onboard for car $number in this session"
            return
        }
        if (laptopActive) rememberBroadcast()
        // A car whose onboard is up already: tapping it again closes that feed, so the tower and
        // the maps work as on/off switches. Laptop mode's one feed up top comes back instead.
        tiles.firstOrNull { it.channel == channel }?.let {
            if (laptopActive) putOnTop(it) else if (tiles.size > 1) remove(it) else raise(it)
            return
        }
        val now = mapTimeMs() ?: 0L
        // Laptop mode shows one feed: the onboard takes a feed waiting off screen (never the one
        // with the commentary) and comes up top; with only one feed, that one switches.
        if (laptopActive) {
            val spare = tiles.filter { it.id != bigTileId && !it.audible && !it.pinned }
                .minWithOrNull(compareBy<Tile>({ interest(it, now) }, { it.mapPickedAt }))
            if (spare == null && onboardOverCommentary(channel)) return
            val swap = spare ?: tiles.firstOrNull { it.id == bigTileId } ?: return
            swap.mapPickedAt = System.currentTimeMillis()
            switchFeed(swap, channel)
            putOnTop(swap)
            return
        }
        val target = targetTile?.takeIf { it in tiles }
            ?: tiles
                .filter { !it.audible && !it.pinned && !isMainTile(it) }
                .minWithOrNull(compareBy<Tile>({ interest(it, now) }, { it.mapPickedAt }))
        targetTile = null
        // Nothing to swap out, but room for another: the onboard joins as a feed of its own.
        if (target == null && roomForFeeds > 0) {
            addFeeds(leader.contentId, listOf(channel))
            return
        }
        if (target == null) {
            message = if (tiles.size == 1) "Add a feed with +, then tap a car to put its onboard there."
            else if (tiles.none { it.pinned }) "Tap a small feed, then a car, to swap it for the car's onboard."
            else "Every other feed is pinned. Unpin one from its menu (long-press it), or tap a feed and then the car."
            return
        }
        target.mapPickedAt = System.currentTimeMillis()
        switchFeed(target, channel)
    }

    /** Lower is less worth keeping on screen. */
    private fun interest(tile: Tile, now: Long): Int {
        val number = tile.channel.racingNumber ?: return 3
        val state = timing?.states?.get(number.toString())?.at(now) ?: LapState.UNKNOWN
        return when {
            state == LapState.PIT || state == LapState.KNOCKED_OUT -> 0
            timing?.showsPushLaps == true && state == LapState.PUSH -> 2
            else -> 1
        }
    }

    // Tiles.

    fun channelsFor(tile: Tile): List<Channel> = channelsByContent[tile.contentId].orEmpty()

    fun openTileMenu(tile: Tile) {
        tileMenu = tile
    }

    fun closeTileMenu() {
        tileMenu = null
    }

    /** A feed chosen from a tile's own menu. In laptop mode the commentary up top keeps playing under an onboard. */
    fun chooseFeed(tile: Tile, channel: Channel) {
        if (laptopActive && tile.id == bigTileId && onboardOverCommentary(channel)) {
            tileMenu = null
            rememberBroadcast()
            return
        }
        switchFeed(tile, channel)
    }

    /**
     * In laptop mode, with the commentary on the broadcast up top and nothing waiting below: the
     * onboard joins as a feed of its own and comes up top, so the broadcast and its commentary
     * carry on out of sight. False if that doesn't apply, for the caller to switch a feed instead.
     */
    private fun onboardOverCommentary(channel: Channel): Boolean {
        val top = tiles.firstOrNull { it.id == bigTileId } ?: return false
        if (!top.audible || top.channel.racingNumber != null || channel.racingNumber == null || roomForFeeds <= 0) return false
        // Already starting from the last tap.
        if (!busy) addFeeds(top.contentId, listOf(channel)) { putOnTop(it) }
        return true
    }

    /** Puts another channel in the tile, keeping its place, size and audio. */
    fun switchFeed(tile: Tile, channel: Channel) {
        tileMenu = null
        if (channel == tile.channel) return
        val restartComposition = settings.decoderMode == DecoderMode.SINGLE || isTv && f1DashCaptureJob != null
        // The feed up top in laptop mode switched from its own menu: offer the way back too.
        if (laptopActive && tile.id == bigTileId) rememberBroadcast()
        task("Switching to ${channel.title}") {
            val failures = mutableListOf<String>()
            val stream = streamFor(tile.contentId, channel, failures)
            if (tile !in tiles) return@task
            val old = tile.feed
            // Start where the tile was (switching the leader from the start once pulled every
            // other feed back with it), allowing for the time a feed takes to start while playing,
            // and from the beginning of that video chunk: starting mid-chunk means decoding every
            // frame from the chunk's start first, measured at 3 to 4 s against under 1 s from its
            // start. The sync loop then brings it level in one short skip.
            val start = if (old.player.isCurrentMediaItemLive) C.TIME_UNSET else {
                val clock = if (singleDecoderSceneActive) leaderTile()?.feed?.player ?: old.player else old.player
                val target = clock.currentPosition + if (playing) FeedPlayer.typicalStartMs else 0L
                val chunk = FeedPlayer.chunkMs
                if (chunk > 0) target / chunk * chunk else target
            }
            if (settings.decoderMode == DecoderMode.SINGLE) decoderMode = DecoderMode.SINGLE
            if (restartComposition) stopProtectedCompositionPresentation(keepSourceVideoDisabled = decoderMode == DecoderMode.SINGLE)
            val feed = newFeed(stream, start, startWithVideo = decoderMode != DecoderMode.SINGLE && !isTv)
            if (decoderMode == DecoderMode.SINGLE && tiles.size > 1) feed.setProtectedCompositionVideoRole(false, 1, 1)
            feed.setAudible(tile.audible && !mutedAll && !castingActive)
            feed.player.playWhenReady = playing
            tile.feed = feed
            tile.channel = channel
            old.release()
            if (failures.isNotEmpty()) message = failures.joinToString("\n")
            if (restartComposition && decoderMode == DecoderMode.SINGLE && tiles.size >= 2) startArchiveSingleDecoderComposition(tile.contentId)
            else { tiles.forEach { it.feed.clearProtectedCompositionVideoRole() }; applyVideoBudget() }
        }
    }

    /** Lays the feeds out afresh in [mode]. */
    fun chooseLayout(mode: LayoutMode) {
        mapGrabbed = false
        if (mode != layoutMode && !singleDecoderSceneActive && !archiveCompositionStarting && (protectedCompositionHost != null || f1DashCaptureJob != null)) {
            stopProtectedCompositionPresentation()
        }
        if (mode == LayoutMode.SINGLE_DECODER_TEST) { chooseDecoderMode(DecoderMode.SINGLE); return }
        if (mode == LayoutMode.TILE_TEST) {
            if (singleDecoderSceneActive) chooseDecoderMode(DecoderMode.MULTIPLE)
            val leader = leaderTile()
            if (leader == null) {
                message = "Start an F1 session first, then choose Tile testing."
                showChrome()
                return
            }
            if (tiles.any { it.contentId != leader.contentId }) {
                message = "Tile testing compares two feeds from one F1 session. Close feeds from other sessions first."
                showChrome()
                return
            }
            layoutMode = LayoutMode.TILE_TEST
            handPlaced = false
            layoutRequest++
            layoutSheetOpen = false
            showChrome()
            openPicker(
                leader.contentId,
                titles[leader.contentId].orEmpty().ifBlank { "Current F1 session" },
                images[leader.contentId],
                live = isLive,
            )
            return
        }
        val enteringTvLayout = mode == LayoutMode.TV && layoutMode != LayoutMode.TV
        if (!singleDecoderSceneActive) {
            f1DashCaptureJob?.cancel()
            f1DashCaptureJob = null
            f1DashCapture = null
            HevcFeedProfileCapture.clearTileTestSelection()
            ProtectedHevcLiveComposition.clearAll()
        }
        layoutMode = mode
        if (mode == LayoutMode.TV) {
            // TV is a complete preset, not a feed-only arrangement: turn on the track map and
            // tower, put them in the fixed right sidebar, and remember TV as the next session's default.
            defaultLayoutMode = LayoutMode.TV
            mapMode = MapMode.TRACK
            towerOpen = true
            overlayLayoutMode = OverlayLayoutMode.DOCKED
            tvLayoutMapNeedsDefault = enteringTvLayout
            prefs.edit()
                .putString(PREF_DEFAULT_LAYOUT, LayoutMode.TV.name)
                .putString(PREF_MAP_MODE, MapMode.TRACK.name)
                .putBoolean(PREF_TOWER, true)
                .putString(PREF_OVERLAY_LAYOUT, OverlayLayoutMode.DOCKED.name)
                .apply()
        }
        handPlaced = false
        layoutRequest++
        showChrome()
    }

    /** The saved arrangement for [n] feeds, if there is one for a screen this shape. */
    fun savedLayout(n: Int): SavedLayout? =
        SavedLayout.fromJson(prefs.getString(layoutKey(n), null))?.takeIf { canvasW <= 0f || it.fits(canvasW, canvasH) }

    /** Saved layouts are kept per way up: sideways ones carry an L (upright ones predate this). */
    private fun layoutKey(n: Int) = PREF_LAYOUT + n + if (canvasW > canvasH) "L" else ""

    /** Upright or sideways, in words, for messages. */
    val wayUp get() = if (canvasW > canvasH) "sideways" else "upright"

    /**
     * Makes the arrangement on screen the one every stream with this many feeds starts in, map
     * included. The built-in layouts stay as they were.
     */
    fun saveLayoutAsDefault() {
        if (tiles.isEmpty() || canvasW <= 0f) return
        val ordered = bigFirst()
        prefs.edit()
            .putString(layoutKey(ordered.size), SavedLayout.of(ordered.map { Slot(it.x, it.y, it.w) }, canvasW, canvasH).toJson())
            .putString(PREF_DEFAULT_LAYOUT, LayoutMode.CUSTOM.name)
            .apply()
        storeMapPanel()
        defaultLayoutMode = LayoutMode.CUSTOM
        layoutMode = LayoutMode.CUSTOM
        message = "Saved. Streams with ${ordered.size} feed${if (ordered.size == 1) "" else "s"} start like this when the phone is $wayUp."
    }

    fun chooseSnap(on: Boolean) {
        snapMode = on
        prefs.edit().putBoolean(PREF_SNAP, on).apply()
    }

    /** The big feed (double-tapped in, else the one with the audio) first, then the rest. */
    private fun bigFirst(): List<Tile> {
        val big = tiles.firstOrNull { it.id == bigTileId } ?: tiles.firstOrNull { it.audible } ?: tiles.firstOrNull()
        return listOfNotNull(big) + tiles.filter { it !== big }
    }

    /**
     * Lays out only when asked: feeds added or removed, a layout picked, the phone folded or
     * unfolded. Switching a feed (from the menu or a car on the map) never comes through here, so
     * the feed keeps its spot. The track map is never moved by a layout. Both maps lie over the
     * feeds, unless the track map is set to keep clear, when the feeds make room for it.
     */
    fun layoutIfRequested(width: Float, height: Float) {
        if (layoutDone == layoutRequest) return
        layoutDone = layoutRequest
        try {
            layOut(width, height)
        } finally {
            glideNextLayout = false
        }
    }

    private fun layOut(width: Float, height: Float) {
        // Clear a previous screen orientation's reservation before any early-return layout.
        // The combined plan is rebuilt below only when its two feed-clearance switches apply.
        combinedOverlayPlan = null
        val composed = protectedCompositionHost.takeUnless { singleDecoderSceneActive }
        val ordered = composed?.let { listOf(it) } ?: bigFirst()
        if (composed == null) bigTileId = ordered.firstOrNull()?.id

        // Laptop mode: the main feed fills the screen above the hinge; the rest wait off screen,
        // still playing. Timing and the map take the half below.
        hinge?.takeIf { laptopActive }?.let { (top, bottom) ->
            val main = ordered.first()
            placeLaptopMap(width, height, bottom)
            settle(main, Layouts.place(LayoutMode.GRID, 1, width, top).first())
            raise(main)
            // The others slide off to the side, keeping their size, and slide back later.
            tiles.filter { it !== main }.forEach { settle(it, Slot(width + it.w, it.y, it.w)) }
            return
        }

        // One feed filling the screen: it gets the whole canvas, the rest wait off screen.
        // Unfolding to the big screen ends it. When both overlays reserve space, keep the
        // selected main feed in the dedicated feed region instead of bypassing that layout.
        val tvLayoutActive = layoutMode == LayoutMode.TV && !laptopActive
        val combinedReservationsActive =
            tvLayoutActive || (mapMode.inWindow && mapReserve && towerOpen && towerReserve && timing != null && !laptopActive)
        tiles.firstOrNull { it.id == focusTileId }?.takeIf { composed == null && compactCanvas && !combinedReservationsActive }?.let { focus ->
            focus.place(Layouts.place(LayoutMode.GRID, 1, width, height).first())
            raise(focus)
            tiles.filter { it !== focus }.forEach { it.place(Slot(width * 2, 0f, it.w)) }
            return
        }
        focusTileId = null

        // The combined map/tower reservation takes precedence over a saved feed-only layout.
        if (tvLayoutActive || (mapMode.inWindow && mapReserve && towerOpen && towerReserve && timing != null && !laptopActive)) {
            resumeLayout = null
        }
        // Coming back to a session puts its feeds back where they were.
        if (composed == null) resumeLayout?.let { (boxes, layout) ->
            resumeLayout = null
            if (layout.fits(width, height) && ordered.all { it.channel.key() in boxes }) {
                ordered.forEach { tile ->
                    val b = boxes.getValue(tile.channel.key())
                    tile.place(Slot(b[0] * width, b[1] * height, b[2] * width))
                }
                handPlaced = true
                placeMapIfNeeded(width, height)
                return
            }
        }

        // When both reservations are on, give the feeds one main region and arrange the
        // tower and map together beside it. The orientation decides whether the workspace is
        // below the video (portrait) or in a right-hand sidebar (landscape). In floating mode
        // this is their starting workspace: the map can later be dragged over the video area.
        // TV owns its video/timing/map regions even while F1 timing is still connecting. This
        // prevents startup latency from collapsing the TV layout back into a feed-only grid.
        val keepMapClear = tvLayoutActive || (mapMode.inWindow && mapReserve && timing != null)
        val keepTowerClear = towerOpen && (tvLayoutActive || towerReserve && timing != null) && !laptopActive
        if (composed != null && !keepMapClear && !keepTowerClear) {
            placeComposedViewport(composed, Area(0f, 0f, width, height))
            placeMapIfNeeded(width, height)
            return
        }
        if (keepMapClear && keepTowerClear) {
            val panelLayoutMode = if (tvLayoutActive) LayoutMode.TV else layoutMode
            val panelOverlayMode = if (tvLayoutActive) OverlayLayoutMode.DOCKED else overlayLayoutMode
            val plan = Layouts.withCombinedOverlays(panelLayoutMode, ordered.size, width, height, panelOverlayMode)
            combinedOverlayPlan = plan
            if (plan.floating) {
                keepTowerInArea(plan.workspace, width, height)
                placeMapInAreaIfNeeded(plan.workspace, width, height)
            } else {
                towerSpot.x = plan.tower.x
                towerSpot.y = plan.tower.y
                towerSpot.w = plan.tower.w
                towerSpot.h = plan.tower.h
                if (tvLayoutActive) {
                    if (tvLayoutMapNeedsDefault) {
                        mapPanel.place(plan.map)
                        mapPanel.placed = true
                        tvLayoutMapNeedsDefault = false
                        storeMapPanel()
                    } else if (!mapPanel.placed) {
                        mapPanel.restore(prefs.getString(PREF_MAP_PANEL, null), width, height)?.let { mapOpacity = it }
                        if (!mapPanel.placed) mapPanel.place(plan.map)
                        mapPanel.placed = true
                    }
                    keepMapOnScreen()
                } else {
                    if (!mapPanel.placed) {
                        mapPanel.restore(prefs.getString(PREF_MAP_PANEL, null), width, height)?.let { mapOpacity = it }
                    }
                    mapPanel.place(plan.map)
                    mapPanel.placed = true
                }
            }
            handPlaced = false
            if (composed != null) placeComposedViewport(composed, plan.feeds)
            else plan.slots.zip(ordered).forEach { (slot, tile) -> settle(tile, slot) }
            return
        } else {
            combinedOverlayPlan = null
        }
        val custom = if (layoutMode == LayoutMode.CUSTOM && !keepMapClear && !keepTowerClear) {
            savedLayout(ordered.size)?.takeIf { it.fits(width, height) }
        } else null
        val slots = when {
            custom != null -> custom.slots(width, height)
            keepTowerClear -> {
                val density = context.resources.displayMetrics.density
                val minTowerWidth = minOf(TOWER_MIN_WIDTH_DP * density, width)
                val minTowerHeight = minOf(TOWER_MIN_HEIGHT_DP * density, height)
                val maxTowerWidth = (width - 32f * density).coerceAtLeast(minTowerWidth).coerceAtMost(width)
                val maxTowerHeight = (height - 40f * density).coerceAtLeast(minTowerHeight).coerceAtMost(height)
                // FloatingPanel normally restores these dimensions when it first appears.
                // A reserved tower is positioned by the layout solver first, so restore the
                // saved dimensions here before asking it to find the matching free rectangle.
                towerSpot.saved()?.takeIf { it.size >= 4 }?.let { saved ->
                    if (towerSpot.w <= 0f && saved[2] > 0f) towerSpot.w = saved[2] * width
                    if (towerSpot.h <= 0f && saved[3] > 0f) towerSpot.h = saved[3] * height
                }
                val requestedWidth = (towerSpot.w.takeIf { it > 0f }
                    ?: minOf(TOWER_DEFAULT_WIDTH_DP * density, maxTowerWidth))
                    .coerceIn(minTowerWidth, maxTowerWidth)
                val requestedHeight = (towerSpot.h.takeIf { it > 0f }
                    ?: minOf(TOWER_DEFAULT_HEIGHT_DP * density, maxTowerHeight))
                    .coerceIn(minTowerHeight, maxTowerHeight)
                val mapMinSide = if (keepMapClear) minOf(width, height) * 0.22f else null
                val arrangement = Layouts.withOverlays(
                    layoutMode, ordered.size, width, height,
                    requestedWidth, requestedHeight, mapMinSide,
                )
                arrangement.tower?.let { gap ->
                    towerSpot.x = gap.x
                    towerSpot.y = gap.y
                    towerSpot.w = gap.w
                    towerSpot.h = gap.h
                    storeTowerPanel()
                } ?: run {
                    message = "There isn't enough room to keep the timing tower clear of the feeds. Reduce its width or height, then try again."
                }
                arrangement.map?.let { gap ->
                    mapPanel.place(gap)
                    mapPanel.placed = true
                }
                if (keepMapClear && arrangement.map == null) {
                    message = "There isn't enough room to keep both the map and timing tower clear. Try making the tower smaller."
                }
                arrangement.slots
            }
            keepMapClear -> {
                val arrangement = Layouts.withMap(layoutMode, ordered.size, width, height)
                val gap = arrangement.map
                if (gap != null && minOf(gap.w, gap.h) >= minOf(width, height) * 0.22f) {
                    mapPanel.place(gap)
                    mapPanel.placed = true
                }
                arrangement.slots
            }
            // The edge ribbon lies over the feeds' outer edges, like the track map, so sessions
            // without a map (F2, F1 Academy) and maps still loading never leave a bare border.
            else -> Layouts.place(layoutMode, ordered.size, width, height)
        }
        slots.zip(ordered).forEach { (slot, tile) -> settle(tile, slot) }
        placeMapIfNeeded(width, height)
    }

    private fun placeComposedViewport(host: Tile, area: Area) {
        // A composed representation has its own aspect ratio. PlayerView FIT preserves all cells.
        // Do not pass it through Tile.resize(), which forces every source tile back to 16:9.
        host.x = area.x
        host.y = area.y
        host.w = area.w
        host.h = area.h
    }

    /** Restores and clamps the tower to the workspace shared with the map. */
    private fun keepTowerInArea(area: Area, width: Float, height: Float) {
        val density = context.resources.displayMetrics.density
        val saved = towerSpot.saved()
        if (towerSpot.x < 0f && saved != null) {
            towerSpot.x = saved[0] * width
            towerSpot.y = saved[1] * height
            if (saved.size >= 4) {
                if (saved[2] > 0f) towerSpot.w = saved[2] * width
                if (saved[3] > 0f) towerSpot.h = saved[3] * height
            }
        }
        val minWidth = minOf(TOWER_MIN_WIDTH_DP * density, area.w)
        val minHeight = minOf(TOWER_MIN_HEIGHT_DP * density, area.h)
        val defaultWidth = minOf(TOWER_DEFAULT_WIDTH_DP * density, area.w * 0.92f)
        val defaultHeight = minOf(TOWER_DEFAULT_HEIGHT_DP * density, area.h * 0.58f)
        towerSpot.w = (towerSpot.w.takeIf { it > 0f } ?: defaultWidth).coerceIn(minWidth, area.w)
        towerSpot.h = (towerSpot.h.takeIf { it > 0f } ?: defaultHeight).coerceIn(minHeight, area.h)
        if (towerSpot.x < 0f || towerSpot.y < 0f) {
            towerSpot.x = area.x + (area.w - towerSpot.w) / 2f
            towerSpot.y = area.y + (area.h - towerSpot.h) * 0.04f
        }
        towerSpot.x = towerSpot.x.coerceIn(area.x, (area.x + area.w - towerSpot.w).coerceAtLeast(area.x))
        towerSpot.y = towerSpot.y.coerceIn(area.y, (area.y + area.h - towerSpot.h).coerceAtLeast(area.y))
    }

    /** Restores or initially places the map inside the shared floating workspace. */
    private fun placeMapInAreaIfNeeded(area: Area, width: Float, height: Float) {
        if (!mapPanel.placed) {
            mapPanel.restore(prefs.getString(PREF_MAP_PANEL, null), width, height)?.let { mapOpacity = it }
            if (!mapPanel.placed) {
                val panelWidth = area.w * 0.90f
                val panelHeight = area.h * 0.58f
                mapPanel.place(
                    Area(
                        area.x + (area.w - panelWidth) / 2f,
                        area.y + area.h - panelHeight - (area.h - panelHeight) * 0.04f,
                        panelWidth,
                        panelHeight,
                    )
                )
                mapPanel.placed = true
            }
        }
        // In floating mode the saved map is intentionally allowed to sit over the main
        // video area. Keep it on screen, not trapped in the reserved sidebar/workspace.
        keepMapOnScreen()
        expandTowerWhenMapLeavesWorkspace(area)
    }

    /**
     * The track map's first spot: where it was last left (or saved), else a square in the
     * bottom-right corner, over the feeds.
     */
    private fun placeMapIfNeeded(width: Float, height: Float) {
        if (mapPanel.placed) return
        mapPanel.restore(prefs.getString(PREF_MAP_PANEL, null), width, height)?.let { mapOpacity = it }
        if (mapPanel.placed) {
            keepMapOnScreen()
            return
        }
        val side = minOf(width, height) * 0.42f
        mapPanel.place(Area(width - side, height - side, side, side))
        mapPanel.placed = true
    }

    /**
     * The laptop map's first spot: where it was last left, else the right part of the half below
     * the hinge, kept clear of the screen's rounded corners (the timing tower takes the left).
     */
    private fun placeLaptopMap(width: Float, height: Float, hingeBottom: Float) {
        if (laptopMapPanel.placed) return
        laptopMapPanel.restore(prefs.getString(PREF_LAPTOP_MAP, null), width, height)
        if (laptopMapPanel.placed) {
            keepMapOnScreen()
            return
        }
        val d = context.resources.displayMetrics.density
        val gap = 12 * d
        val corner = 40 * d
        val left = width * LAPTOP_TOWER_SHARE + gap
        laptopMapPanel.place(Area(left, hingeBottom + gap, width - left - corner, height - hingeBottom - gap - corner))
        laptopMapPanel.placed = true
    }

    /** The canvas's size in pixels, for keeping things on screen. */
    val canvasSize get() = canvasW to canvasH

    /**
     * Pulls the track map back onto the screen, no bigger than it. Turning the phone for laptop
     * mode swapped the screen's width and height and could leave the map, and its lock, off the
     * right edge with no way to reach it.
     */
    private fun keepMapOnScreen() {
        if (canvasW <= 0f) return
        // Only the one in use: on the narrow cover screen, this pushed laptop mode's map to the left,
        // over the timing tower, where unfolding found it.
        activeMapPanel.let { panel ->
            if (!panel.placed) return
            panel.w = panel.w.coerceAtMost(canvasW)
            panel.h = panel.h.coerceAtMost(canvasH)
            panel.x = panel.x.coerceIn(0f, canvasW - panel.w)
            panel.y = panel.y.coerceIn(0f, canvasH - panel.h)
        }
    }

    /** Keeps the map's place, size, turn and opacity for next time. */
    fun storeMapPanel() {
        val panel = activeMapPanel
        if (canvasW <= 0f || !panel.placed) return
        prefs.edit().putString(if (laptopActive) PREF_LAPTOP_MAP else PREF_MAP_PANEL, panel.toJson(canvasW, canvasH, mapOpacity)).apply()
    }

    private fun requestOverlayLayout() {
        if (canvasW <= 0f || laptopActive) return
        handPlaced = false
        layoutRequest++
    }

    fun chooseMapMode(mode: MapMode) {
        mapGrabbed = false
        if (layoutMode == LayoutMode.TV && mode != MapMode.TRACK) {
            message = "TV layout uses the Track map. Choose another layout to use Edge or Pit."
            return
        }
        if (mode == mapMode) return
        mapMode = mode
        prefs.edit().putString(PREF_MAP_MODE, mode.name).apply()
        if (mapReserve || towerOpen && towerReserve) {
            // Overlay reservations take priority over any hand-placed feed layout.
            requestOverlayLayout()
        }
        if (canvasW > 0f) placeMapIfNeeded(canvasW, canvasH)
    }

    fun chooseMapReserve(on: Boolean) {
        if (mapReserve == on) return
        mapReserve = on
        prefs.edit().putBoolean(PREF_MAP_RESERVE, on).apply()
        if (on) mapPanel.placed = false
        if (on || towerOpen && towerReserve) requestOverlayLayout()
    }

    fun toggleMapLock() {
        mapLocked = !mapLocked
        prefs.edit().putBoolean(PREF_MAP_LOCKED, mapLocked).apply()
        message = if (mapLocked) "Map locked. Feeds underneath can be moved." else "Map unlocked. Drag it by the track line."
    }

    fun rotateMap(degrees: Float) {
        activeMapPanel.rotation = (activeMapPanel.rotation + degrees).mod(360f)
        storeMapPanel()
    }

    fun resetMapRotation() {
        activeMapPanel.rotation = 0f
        storeMapPanel()
    }

    /** Two fingers on the track line while arranging: move, resize and turn the map. */
    fun arrangeMap(pan: androidx.compose.ui.geometry.Offset, zoom: Float, rotation: Float) {
        // Docked overlays stay fixed in the normal layout, but the TV preset exposes D-pad
        // move/resize handles and must let its map be adjusted without unlocking video tiles.
        if (combinedOverlaysDocked && layoutMode != LayoutMode.TV) return
        val panel = activeMapPanel
        panel.x += pan.x
        panel.y += pan.y
        if (zoom != 1f) panel.scale(zoom)
        if (rotation != 0f) panel.rotation = (panel.rotation + rotation).mod(360f)
        if (combinedOverlaysFloating || layoutMode == LayoutMode.TV) {
            // TV map controls may move the map within the screen even though the tower remains
            // docked. Floating mode also expands the tower when the map leaves its workspace.
            keepMapOnScreen()
            if (combinedOverlaysFloating) combinedOverlayPlan?.workspace?.let(::expandTowerWhenMapLeavesWorkspace)
        }
        showChrome()
    }

    private fun expandTowerWhenMapLeavesWorkspace(workspace: Area) {
        val centerX = mapPanel.x + mapPanel.w / 2f
        val centerY = mapPanel.y + mapPanel.h / 2f
        val mapIsInOverlayColumn = centerX in workspace.x..(workspace.x + workspace.w) &&
            centerY in workspace.y..(workspace.y + workspace.h)
        if (!mapIsInOverlayColumn) {
            if (!towerExpandedForMapMove) {
                towerHeightBeforeMapMove = towerSpot.h.takeIf { it > 0f && it < workspace.h }
                towerYBeforeMapMove = towerSpot.y.takeIf { it >= workspace.y && it < workspace.y + workspace.h }
            }
            // Reapply after any screen-size/orientation relayout too. The map may have remained
            // over the video while the app rebuilt its combined overlay plan.
            towerSpot.y = workspace.y
            towerSpot.h = workspace.h
            towerExpandedForMapMove = true
        } else if (towerExpandedForMapMove) {
            towerSpot.h = (towerHeightBeforeMapMove ?: minOf(TOWER_DEFAULT_HEIGHT_DP *
                context.resources.displayMetrics.density, workspace.h)).coerceIn(
                minOf(TOWER_MIN_HEIGHT_DP * context.resources.displayMetrics.density, workspace.h),
                workspace.h,
            )
            towerSpot.y = (towerYBeforeMapMove ?: workspace.y).coerceIn(
                workspace.y, (workspace.y + workspace.h - towerSpot.h).coerceAtLeast(workspace.y),
            )
            towerHeightBeforeMapMove = null
            towerYBeforeMapMove = null
            towerExpandedForMapMove = false
        }
    }

    fun updateMapOpacity(value: Float) {
        mapOpacity = value
    }

    fun updateMapDotSize(value: Float) {
        mapDotSizeDp = value.coerceIn(MIN_MAP_DOT_SIZE, MAX_MAP_DOT_SIZE)
        prefs.edit().putFloat(PREF_MAP_DOT_SIZE, mapDotSizeDp).apply()
    }

    fun updateMapLabelSize(value: Float) {
        mapLabelSizeSp = value.coerceIn(MIN_MAP_LABEL_SIZE, MAX_MAP_LABEL_SIZE)
        prefs.edit().putFloat(PREF_MAP_LABEL_SIZE, mapLabelSizeSp).apply()
    }

    // Moving and resizing tiles by hand.

    // The explanation overlay.

    var helpOpen by mutableStateOf(false)
        private set
    /** Where each labelled control is on screen, filled in as they're laid out. */
    val helpAnchors = mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>()
    /** True once the overlay has been seen; before that it opens by itself. */
    val helpSeen get() = prefs.getBoolean(PREF_HELP_SEEN, false)

    fun showHelp() {
        helpOpen = true
        chromeVisible = true
        prefs.edit().putBoolean(PREF_HELP_SEEN, true).apply()
    }

    fun closeHelp() {
        helpOpen = false
        showChrome()
    }

    // Team radio.

    /** The radio panel is shown; on to start with, then remembered. */
    var radioOpen by mutableStateOf(prefs.getBoolean(PREF_RADIO, true))
        private set
    /** The clip playing, if any. */
    var radioPlaying by mutableStateOf<RadioClip?>(null)
        private set
    /** Where the radio panel sits and how big it is. */
    val radioSpot = FloatingSpot(prefs, PREF_RADIO_SPOT)

    /** The timing tower over the feeds; laptop mode has its own below the hinge. */
    var towerOpen by mutableStateOf(prefs.getBoolean(PREF_TOWER, false))
        private set
    val towerSpot = FloatingSpot(prefs, PREF_TOWER_SPOT)
    var towerOpacity by mutableFloatStateOf(prefs.getFloat(PREF_TOWER_OPACITY, 0.9f))
        private set
    var towerFontScale by mutableFloatStateOf(prefs.getFloat(PREF_TOWER_FONT_SCALE, 1f))
        private set
    var towerLocked by mutableStateOf(prefs.getBoolean(PREF_TOWER_LOCKED, false))
        private set
    var towerReserve by mutableStateOf(prefs.getBoolean(PREF_TOWER_RESERVE, false))
        private set
    /** The tower's preferred height before the floating map moves over the video area. */
    private var towerHeightBeforeMapMove: Float? = null
    private var towerYBeforeMapMove: Float? = null
    private var towerExpandedForMapMove = false

    fun toggleTower() {
        towerOpen = !towerOpen
        prefs.edit().putBoolean(PREF_TOWER, towerOpen).apply()
        if ((towerReserve || layoutMode == LayoutMode.TV) && canvasW > 0f && !laptopActive) {
            handPlaced = false
            layoutRequest++
        }
    }

    fun updateTowerFontScale(value: Float) {
        towerFontScale = value.coerceIn(TOWER_MIN_FONT_SCALE, TOWER_MAX_FONT_SCALE)
        prefs.edit().putFloat(PREF_TOWER_FONT_SCALE, towerFontScale).apply()
    }

    fun updateTowerOpacity(value: Float) {
        towerOpacity = value.coerceIn(0.2f, 1f)
        prefs.edit().putFloat(PREF_TOWER_OPACITY, towerOpacity).apply()
    }

    fun toggleTowerLock() {
        towerLocked = !towerLocked
        prefs.edit().putBoolean(PREF_TOWER_LOCKED, towerLocked).apply()
    }

    fun chooseTowerReserve(on: Boolean) {
        if (towerReserve == on) return
        towerReserve = on
        prefs.edit().putBoolean(PREF_TOWER_RESERVE, on).apply()
        if (towerOpen || mapReserve) requestOverlayLayout()
    }

    fun chooseOverlayLayoutMode(mode: OverlayLayoutMode) {
        if (overlayLayoutMode == mode) return
        overlayLayoutMode = mode
        prefs.edit().putString(PREF_OVERLAY_LAYOUT, mode.name).apply()
        // Re-open the saved floating positions when leaving the fixed, docked arrangement.
        towerSpot.x = -1f
        towerSpot.y = -1f
        towerSpot.w = -1f
        towerSpot.h = -1f
        towerHeightBeforeMapMove = null
        towerYBeforeMapMove = null
        towerExpandedForMapMove = false
        mapPanel.placed = false
        if (mapReserve && towerOpen && towerReserve) requestOverlayLayout()
    }

    /** Width and height are in canvas pixels, like [FloatingSpot]. */
    fun updateTowerSize(widthPx: Float, heightPx: Float) {
        val panel = towerSpot
        if (canvasW <= 0f || canvasH <= 0f) {
            panel.w = widthPx
            panel.h = heightPx
            return
        }
        val density = context.resources.displayMetrics.density
        val workspace = combinedOverlayPlan?.takeIf { it.floating }?.workspace
        val minWidth = minOf(TOWER_MIN_WIDTH_DP * density, workspace?.w ?: canvasW)
        val minHeight = minOf(TOWER_MIN_HEIGHT_DP * density, workspace?.h ?: canvasH)
        val maxWidth = workspace?.w ?: (canvasW - 32f * density).coerceAtLeast(minWidth).coerceAtMost(canvasW)
        val maxHeight = workspace?.h ?: (canvasH - 40f * density).coerceAtLeast(minHeight).coerceAtMost(canvasH)
        val oldWidth = panel.w.takeIf { it > 0f } ?: minOf(TOWER_DEFAULT_WIDTH_DP * density, maxWidth)
        val oldHeight = panel.h.takeIf { it > 0f } ?: minOf(TOWER_DEFAULT_HEIGHT_DP * density, maxHeight)
        val centerX = if (panel.x >= 0f) panel.x + oldWidth / 2f else canvasW / 2f
        val centerY = if (panel.y >= 0f) panel.y + oldHeight / 2f else canvasH / 2f
        panel.w = widthPx.coerceIn(minWidth, maxWidth)
        panel.h = heightPx.coerceIn(minHeight, maxHeight)
        panel.x = (centerX - panel.w / 2f).coerceIn(workspace?.x ?: 0f, ((workspace?.x ?: 0f) + maxWidth - panel.w).coerceAtLeast(workspace?.x ?: 0f))
        panel.y = (centerY - panel.h / 2f).coerceIn(workspace?.y ?: 0f, ((workspace?.y ?: 0f) + maxHeight - panel.h).coerceAtLeast(workspace?.y ?: 0f))
        storeTowerPanel()
    }

    /** Recalculates the feed layout after a tower resize when the feeds are kept clear. */
    fun finishTowerResize() {
        if (towerOpen && towerReserve) requestOverlayLayout()
    }

    fun storeTowerPanel() {
        if (canvasW > 0f && canvasH > 0f && towerSpot.x >= 0f) towerSpot.store(canvasW, canvasH)
    }

    /** Feed rectangles, for finding open space. */
    fun tileSlots() = tiles.map { Slot(it.x, it.y, it.w) }
    private var radioPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    fun toggleRadio() {
        radioOpen = !radioOpen
        prefs.edit().putBoolean(PREF_RADIO, radioOpen).apply()
        if (!radioOpen) stopRadio()
        showChrome()
    }

    /**
     * Plays a clip over the feeds, which go silent until it ends. Tapping the clip that's
     * playing stops it. No audio focus: taking it would pause the feeds.
     */
    fun playRadio(clip: RadioClip) {
        if (radioPlaying?.path == clip.path) {
            stopRadio()
            return
        }
        val base = timing?.radioBase ?: return
        val player = radioPlayer ?: androidx.media3.exoplayer.ExoPlayer.Builder(context).build().also { p ->
            radioPlayer = p
            p.setPlaybackSpeed(playbackSpeed)
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) stopRadio()
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    message = "Couldn't play that radio clip"
                    stopRadio()
                }
            })
        }
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(base + clip.path))
        player.prepare()
        player.play()
        radioPlaying = clip
        tiles.forEach { it.feed.player.volume = if (it.audible && !mutedAll) RADIO_DUCK else 0f }
    }

    // Radio transcripts, from the phone's own speech recogniser.

    private val transcriber by lazy { RadioTranscriber(context) }
    private val transcriptStore = context.getSharedPreferences("radioText", Context.MODE_PRIVATE)
    /** Finished transcripts by clip path; kept, so a replay's radio is only transcribed once. */
    val transcripts = mutableStateMapOf<String, String>().apply {
        transcriptStore.all.forEach { (k, v) -> if (v is String) put(k, v) }
    }
    /** The clip being transcribed now. */
    var transcribing by mutableStateOf<String?>(null)
        private set
    /** The permission note, while transcripts need the viewer to allow something; null otherwise. */
    var transcriptBlocked by mutableStateOf<String?>(null)
        private set
    private val failedClips = mutableSetOf<String>()

    /**
     * Transcribes clips one at a time while the radio panel is open, nearest the picture first
     * (the timing feed runs ahead of the picture, so a clip is usually done before it shows).
     */
    suspend fun transcribeRadio() {
        // Without a recogniser, clips just play.
        if (!transcriber.available) return
        while (true) {
            val t = timing
            val now = mapTimeMs()
            val waiting = transcriptBlocked != null || System.currentTimeMillis() < transcribeAgainAt
            val next = if (t == null || now == null || waiting) null else t.radio
                .filter { it.path !in transcripts && it.path !in failedClips && it.utc <= now + TRANSCRIBE_AHEAD_MS }
                .minByOrNull { abs(it.utc - now) }
            if (next == null || t == null) {
                kotlinx.coroutines.delay(2_000)
                continue
            }
            transcribing = next.path
            try {
                val text = transcriber.transcribe(t.radioBase + next.path, radioHints(t)).ifBlank { NO_WORDS }
                transcripts[next.path] = text
                transcriptStore.edit().putString(next.path, text).apply()
                radioFailuresInRow = 0
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // A clip that runs too long is skipped. (This was caught as a cancellation
                // in 0.23, which ended the whole loop: later clips were never transcribed.)
                android.util.Log.w("ApexviewRadio", "transcribe ${next.path}: timed out")
                failedClips += next.path
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SpeechPermissionNeeded) {
                transcriptBlocked = PERMISSION_NOTE
            } catch (e: Exception) {
                android.util.Log.w("ApexviewRadio", "transcribe ${next.path}: ${e.message}")
                failedClips += next.path
                // Several in a row: it may only be a bad moment for the recogniser, so wait and try
                // those clips again. Nothing to say about it; the clips still play.
                if (++radioFailuresInRow >= 3) {
                    radioFailuresInRow = 0
                    failedClips.clear()
                    transcribeAgainAt = System.currentTimeMillis() + TRANSCRIBE_RETRY_MS
                }
            } finally {
                transcribing = null
            }
        }
    }

    /** Names and teams in this session, plus radio talk, for the recogniser to lean toward. */
    private fun radioHints(t: SessionTiming): List<String> =
        (t.drivers.values.flatMap { listOf(it.lastName, it.firstName) } +
            t.drivers.values.map { it.team }.distinct() + RadioTranscriber.RADIO_WORDS)
            .filter { it.isNotBlank() }

    private var radioFailuresInRow = 0
    private var transcribeAgainAt = 0L

    /** After the user allows the permission the recogniser asked for. */
    fun retryTranscripts() {
        transcriptBlocked = null
        radioFailuresInRow = 0
    }

    fun stopRadio() {
        radioPlayer?.stop()
        radioPlaying = null
        tiles.forEach { it.feed.setAudible(it.audible && !mutedAll && !castingActive) }
    }

    /** True while a finger holds the track map; the feeds under it stay put. */
    var mapGrabbed = false

    private var arrangeRaw: Box16? = null
    private var arrangeOthers: List<Pair<Tile, Box16>> = emptyList()

    private fun arranger() = context.resources.displayMetrics.density.let { d ->
        Arranger(canvasW, canvasH, snap = SNAP_DP * d, breakThrough = BREAK_DP * d, minWidth = Tile.MIN_WIDTH)
    }

    /** A finger (or two) came down on [tile] to move or resize it. */
    fun beginArrange(tile: Tile) {
        raise(tile)
        arrangeRaw = Box16(tile.x, tile.y, tile.w)
        arrangeOthers = tiles.filter { it !== tile }.map { it to Box16(it.x, it.y, it.w) }
    }

    /**
     * Moves the tile by [dx], [dy] and resizes it by [dw] (from a pinch or the corner handle). Free
     * mode goes exactly where the finger says. Snap mode applies the [Arranger]'s rules.
     */
    fun arrangeBy(tile: Tile, dx: Float, dy: Float, dw: Float) {
        // A finger on the map moves the map only, whatever is under it.
        if (mapGrabbed) return
        val raw = (arrangeRaw ?: Box16(tile.x, tile.y, tile.w)).let { it.copy(x = it.x + dx, y = it.y + dy, w = it.w + dw) }
        arrangeRaw = raw
        if (!snapMode || canvasW <= 0f) {
            tile.x = raw.x
            tile.y = raw.y
            tile.resize(raw.w)
        } else {
            val arranger = arranger()
            if (dw != 0f) {
                val (grown, pushed) = arranger.resize(Box16(tile.x, tile.y, tile.w), raw.w, arrangeOthers.map { it.second })
                tile.resize(grown.w)
                arrangeOthers.zip(pushed).forEach { (pair, box) -> pair.first.place(Slot(box.x, box.y, box.w)) }
            }
            if (dx != 0f || dy != 0f) {
                val moved = arranger.move(raw.copy(w = tile.w), tiles.filter { it !== tile }.map { Box16(it.x, it.y, it.w) })
                tile.x = moved.x
                tile.y = moved.y
            }
        }
        showChrome()
    }

    fun endArrange() {
        arrangeRaw = null
        arrangeOthers = emptyList()
        handPlaced = true
        saveWatch()
    }

    fun nudgeMapOffset(stepMs: Long) {
        mapOffsetMs += stepMs
        prefs.edit().putLong(PREF_MAP_OFFSET, mapOffsetMs).apply()
    }

    fun togglePin(tile: Tile) {
        tile.pinned = !tile.pinned
    }

    private var canvasW = 0f
    private var canvasH = 0f

    /**
     * Each screen shape's hand-placed arrangement (by the way up and the screen's size), kept on
     * leaving it so that coming back puts the feeds where they were: tile id to x, y, w as
     * fractions of the screen.
     */
    private val arrangements = mutableMapOf<String, Map<Int, FloatArray>>()

    private fun screenKey(w: Float, h: Float) = (if (w > h) "L" else "P") + (minOf(w, h) / 200f).toInt()

    /**
     * Folding, unfolding or turning the phone: lay the feeds out again for the new screen, or put
     * back how they were last arranged on a screen this shape. The inner screen is nearly square,
     * so turning it changes its size by under 4%; it counts all the same (before 0.42 it only
     * nudged the feeds and they ended up overlapping). Smaller changes (system bars, a window
     * resize) only pull tiles back on screen.
     */
    fun onCanvasResized(width: Float, height: Float) {
        val turned = canvasW > 0f && (canvasW > canvasH) != (width > height)
        val refit = canvasW > 0f && (turned || abs(width - canvasW) > canvasW * 0.1f || abs(height - canvasH) > canvasH * 0.1f)
        if (refit && handPlaced && tiles.isNotEmpty() && focusTileId == null && !laptopActive) {
            arrangements[screenKey(canvasW, canvasH)] =
                tiles.associate { it.id to floatArrayOf(it.x / canvasW, it.y / canvasH, it.w / canvasW) }
        }
        if (refit && mapPanel.placed) {
            // The map keeps its place relative to the screen.
            mapPanel.x *= width / canvasW
            mapPanel.y *= height / canvasH
            mapPanel.w *= width / canvasW
            mapPanel.h *= height / canvasH
        }
        canvasW = width
        canvasH = height
        keepMapOnScreen()
        if (!refit) {
            keepOnScreen(width, height)
            return
        }
        // Back on a screen shape these same feeds were arranged on by hand: put them back.
        val back = arrangements[screenKey(width, height)]
            ?.takeIf { saved -> saved.size == tiles.size && tiles.all { it.id in saved } }
        if (back != null && focusTileId == null && !laptopActive) {
            tiles.forEach { t ->
                val b = back.getValue(t.id)
                t.place(Slot(b[0] * width, b[1] * height, b[2] * width))
            }
            handPlaced = true
        } else {
            handPlaced = false
            layoutRequest++
        }
    }

    /**
     * Back from the pop-out with every feed bunched in a corner, however that came about: they were
     * laid out for its small window, so they get a fresh fit. Any real layout reaches further.
     */
    fun refitIfShrunk() {
        if (tiles.isEmpty() || canvasW <= 0f || arranging || laptopActive || focusTileId != null) return
        val reach = SHRUNK_REACH
        if (tiles.maxOf { it.x + it.w } < canvasW * reach && tiles.maxOf { it.y + it.h } < canvasH * reach) {
            arrangements.remove(screenKey(canvasW, canvasH))
            handPlaced = false
            layoutRequest++
        }
    }

    /** Pulls tiles back so each one is at least partly on screen. */
    fun keepOnScreen(width: Float, height: Float) {
        // Feeds tucked away behind a full-screen one stay tucked away.
        tiles.filter { if (laptopActive) it.id == bigTileId else focusTileId == null || it.id == focusTileId }.forEach { tile ->
            if (tile.w > width) tile.resize(width)
            tile.x = tile.x.coerceIn(-tile.w / 2, width - tile.w / 2)
            tile.y = tile.y.coerceIn(0f, height - tile.h / 2)
        }
    }

    fun raise(tile: Tile) {
        topZ += 1f
        tile.z = topZ
    }

    /** Moves the audio to [tile]. Choosing a feed to listen to also ends Mute all. */
    fun setAudio(tile: Tile, byUser: Boolean = false) {
        if (byUser) mutedAll = false
        castRelay?.let { relay ->
            val selected = tiles.map { it.feed.sourceStream.profileLabel }
            if (selected.size == relay.session.sourceLabels.size && selected.toSet() == relay.session.sourceLabels.toSet()) {
                relay.selectAudio(tile.feed.sourceStream.profileLabel)
                googleCastLoadedUrl = null
                runCatching {
                    if (localChosen) loadLocalCastMedia(relay) else loadGoogleCastMedia(relay)
                }.onFailure { error ->
                    android.util.Log.w("PitwallCast", DiagnosticRedaction.redact(error.stackTraceToString()))
                    castStatus = "Unable to update TV audio. Use Retry casting or reconnect the TV."
                    message = castStatus
                }
            } else {
                // Removing the audible feed changes the selected set before its replacement relay
                // is ready. The scene-update job rebuilds that set using the new audible tile below.
                castStatus = "Updating selected feeds and audio on the TV…"
            }
        }
        if (castPlaybackHandedOff) {
            if (byUser) mutedAll = false
            tiles.forEach { it.audible = it === tile }
            return
        }
        if (singleDecoderSceneActive) {
            val owner = protectedCompositionHost?.feed ?: return
            if (!owner.selectArchiveAudio(tile.feed.sourceStream.profileLabel)) {
                message = "This audio feed is unavailable in the current composition."
                return
            }
            if (byUser) mutedAll = false
            tiles.forEach { it.audible = it === tile; it.feed.setAudible(false) }
            owner.setAudible(!mutedAll && !castingActive)
            return
        }
        if (byUser) mutedAll = false
        tiles.forEach {
            it.audible = it === tile
            it.feed.setAudible(it.audible && !mutedAll && !castingActive)
        }
    }

    /** Silences every feed, keeping track of which one had the audio for when it's turned back on. */
    fun toggleMuteAll() {
        mutedAll = !mutedAll
        if (castingActive) {
            if (localChosen) localConnection?.send(app.apexview.cast.local.FcastProtocol.SET_VOLUME, JSONObject().put("volume", if (mutedAll) 0 else 1))
            else googleCastRemoteClient?.setStreamMute(mutedAll)
        }
        if (nativeArchiveTiles.isNotEmpty()) protectedCompositionHost?.feed?.setAudible(!mutedAll && !castingActive)
        else tiles.forEach { it.feed.setAudible(it.audible && !mutedAll && !castingActive) }
        showChrome()
    }

    /**
     * Double-tap: the tile trades places with the biggest one. The audio stays where it was,
     * normally the main broadcast; the tile offers its own audio for a few seconds instead.
     */
    /**
     * On a narrow screen (the cover screen, where feeds stack), a double-tap fills the screen
     * with that feed and tucks the rest away (still playing); double-tap again to bring them
     * back. On the wide screen it swaps the feed into the big spot.
     */
    fun doubleTap(tile: Tile) {
        when {
            // Laptop mode shows one feed: a double-tap on an onboard goes back to the broadcast.
            // (Swapping would trade places with a feed waiting off screen and leave the top black.)
            laptopActive -> if (laptopReturnChannel != null) returnToBroadcast()
            focusTileId != null -> {
                focusTileId = null
                layoutRequest++
            }
            compactCanvas && tiles.size > 1 -> {
                focusTileId = tile.id
                layoutRequest++
            }
            else -> swapIntoBig(tile)
        }
    }

    // Laptop mode: the phone half-open with the hinge across, the stream above it and timing below.

    /** Where the hinge runs across the screen (top, bottom), while half-open like a laptop. */
    var hinge by mutableStateOf<Pair<Float, Float>?>(null)
        private set
    /** Laptop mode is wanted when the phone is in that position. Off until asked. */
    var laptopEnabled by mutableStateOf(prefs.getBoolean(PREF_LAPTOP, false))
        private set
    /** The one-time question, showing. */
    var laptopPromptOpen by mutableStateOf(false)
        private set

    /** Laptop mode is on screen now. */
    val laptopActive get() = laptopEnabled && hinge != null && tiles.isNotEmpty()

    /** Onboards have no clock of their own; the main feed and International carry F1's graphics. */
    val countdownOverScreen get() = !laptopActive &&
        tiles.none { (focusTileId == null || it.id == focusTileId) && it.channel.hasBroadcastGraphics }

    /** From the activity: the fold's posture changed. */
    fun updatePosture(newHinge: Pair<Float, Float>?) {
        val was = laptopActive
        hinge = newHinge
        // The first time the phone is held like a laptop while watching, ask.
        if (newHinge != null && tiles.isNotEmpty() && !prefs.getBoolean(PREF_LAPTOP_ASKED, false)) laptopPromptOpen = true
        if (laptopActive != was) {
            // Fold and hinge news arrive apart, so laptop mode may look active on a screen that
            // isn't its own. Going into it puts its map back where it was last left.
            if (laptopActive) laptopMapPanel.placed = false
            relayoutGliding()
        }
    }

    fun answerLaptopPrompt(use: Boolean) {
        laptopPromptOpen = false
        prefs.edit().putBoolean(PREF_LAPTOP_ASKED, true).apply()
        chooseLaptop(use)
    }

    fun chooseLaptop(on: Boolean) {
        val was = laptopActive
        laptopEnabled = on
        prefs.edit().putBoolean(PREF_LAPTOP, on).apply()
        if (laptopActive != was) relayoutGliding()
    }

    /** The feed in the big spot (up top in laptop mode), for highlighting it elsewhere. */
    val bigTileIdForUi get() = bigTileId

    /** The next layout moves feeds along the app's curve instead of jumping them. */
    private var glideNextLayout = false

    private fun relayoutGliding() {
        glideNextLayout = true
        layoutRequest++
    }

    /** Puts a feed in its slot: straight there, or gliding when the layout asked to. */
    private fun settle(tile: Tile, slot: Slot) {
        if (glideNextLayout) glide(tile, slot) else tile.place(slot)
    }

    /** In laptop mode, puts [tile] in the one spot above the hinge. */
    private fun putOnTop(tile: Tile) {
        if (bigTileId != tile.id) previousTopId = bigTileId
        bigTileId = tile.id
        layoutRequest++
        // Its picture back now, not at the next half-second check.
        applyVideoBudget()
    }

    /** In laptop mode, the broadcast (F1 Live, International...) that was up top before an onboard. */
    var laptopReturn by mutableStateOf<Pair<Int, Channel>?>(null)
        private set

    /** Notes the broadcast on top, once, as an onboard is about to replace it. */
    private fun rememberBroadcast() {
        val top = tiles.firstOrNull { it.id == bigTileId } ?: return
        if (top.channel.racingNumber == null) laptopReturn = top.id to top.channel
    }

    /**
     * The broadcast to go back to while an onboard is up top: the one it replaced, else (a session
     * resumed on an onboard) a broadcast feed waiting below, else the session's main feed.
     */
    private fun broadcastBehind(): Pair<Int, Channel>? {
        val top = tiles.firstOrNull { it.id == bigTileId }?.takeIf { laptopActive && it.channel.racingNumber != null } ?: return null
        laptopReturn?.let { return it }
        tiles.filter { it.channel.racingNumber == null }.sortedByDescending { it.audible }.firstOrNull()?.let { return it.id to it.channel }
        val main = channelsByContent[top.contentId]?.let { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull { it.racingNumber == null } }
        return main?.let { top.id to it }
    }

    /** The broadcast to offer going back to: only while an onboard is up top. */
    val laptopReturnChannel: Channel?
        get() = broadcastBehind()?.second

    /**
     * Back to the broadcast that was up top: its feed if it's still playing below, else the top
     * feed switches back to it.
     */
    fun returnToBroadcast() {
        val (tileId, channel) = broadcastBehind() ?: return
        laptopReturn = null
        val waiting = tiles.firstOrNull { it.id == tileId && it.channel == channel } ?: tiles.firstOrNull { it.channel == channel }
        if (waiting != null) putOnTop(waiting)
        else tiles.firstOrNull { it.id == bigTileId }?.let { switchFeed(it, channel) }
    }

    // Reminders asked for from home's Notify me: the calendar opens with its reminder settings up.
    private var remindersAsked = false

    fun openReminders() {
        remindersAsked = true
        open(Screen.Calendar)
    }

    /** True once after [openReminders]. */
    fun takeRemindersRequest(): Boolean = remindersAsked.also { remindersAsked = false }

    /** The feed filling a narrow screen after a double-tap, if any. */
    var focusTileId by mutableStateOf<Int?>(null)
        private set

    /**
     * A phone-sized screen (shorter side under 600dp): the cover screen, upright or sideways. The
     * inner screen is far bigger either way.
     */
    private val compactCanvas get() =
        canvasW > 0f && minOf(canvasW, canvasH) / context.resources.displayMetrics.density < COMPACT_DP

    fun swapIntoBig(tile: Tile) {
        val big = tiles.maxByOrNull { it.w } ?: return
        if (big === tile) return
        val tileSlot = Slot(tile.x, tile.y, tile.w)
        val bigSlot = Slot(big.x, big.y, big.w)
        glide(tile, bigSlot)
        glide(big, tileSlot)
        bigTileId = tile.id
        handPlaced = true
        raise(tile)
        if (!tile.audible) {
            audioHint = tile
            audioHintCount++
        }
    }

    fun dismissAudioHint() {
        audioHint = null
    }

    // Driver of the Day: a note near the end of a live Grand Prix.

    /** The note, showing. Once per race, and never with the setting off. */
    var votePromptOpen by mutableStateOf(false)
        private set
    /** The first note ever also offers to turn them off, without a trip to Settings. */
    var votePromptFirst by mutableStateOf(false)
        private set

    /** On each live update: 6 laps to go, or the race just finished, in a Grand Prix watched live. */
    private fun offerVote(contentId: Long, live: SessionTiming) {
        if (!settings.votePrompt || votePromptOpen || !live.isGrandPrix) return
        if (prefs.getLong(PREF_VOTE_ASKED, 0L) == contentId) return
        val lap = live.currentLap ?: return
        val total = live.totalLaps ?: return
        val finished = live.finishedUtc
        // Laps to go as the lap board counts them: the lap under way is one of them.
        if (finished == null && (lap <= 0 || total - lap + 1 > VOTE_LAPS_LEFT)) return
        // Joined long after the flag: the vote has closed.
        if (finished != null && System.currentTimeMillis() - finished > VOTE_OPEN_AFTER_MS) return
        votePromptFirst = !prefs.getBoolean(PREF_VOTE_SEEN, false)
        prefs.edit().putLong(PREF_VOTE_ASKED, contentId).putBoolean(PREF_VOTE_SEEN, true).apply()
        votePromptOpen = true
    }

    /**
     * Opens F1's vote as a Chrome sheet over part of the screen (up from the bottom, or down the
     * side when unfolded), so the race plays on behind it without leaving the app. A browser that
     * can't do sheets opens it full screen. Popping the race out and then opening the page, as
     * 0.57 did, got the app flagged by Play Protect.
     */
    fun vote(activity: android.app.Activity) {
        votePromptOpen = false
        val screen = activity.resources.displayMetrics
        val sheet = CustomTabsIntent.Builder()
            .setInitialActivityHeightPx((screen.heightPixels * VOTE_SHEET_HEIGHT).toInt(), CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
            .setInitialActivityWidthPx((screen.widthPixels * VOTE_SHEET_WIDTH).toInt())
            .setShowTitle(true)
            .build()
        sheet.intent.data = Uri.parse(VOTE_URL)
        // Chrome only makes a sheet of a tab launched for a result; there's no result to read.
        @Suppress("DEPRECATION")
        runCatching {
            activity.startActivityForResult(sheet.intent, VOTE_REQUEST)
            voteSheetOpen = true
        }.onFailure {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VOTE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** F1's vote is open as a sheet over the app; on a narrow screen the feeds move up clear of it. */
    var voteSheetOpen by mutableStateOf(false)
        private set

    fun onResumed() {
        voteSheetOpen = false
    }

    fun dismissVotePrompt(forGood: Boolean = false) {
        votePromptOpen = false
        if (forGood) settings.updateVotePrompt(false)
    }

    // Pop-out (picture-in-picture) when leaving the app while watching.

    var inPip by mutableStateOf(false)
        private set

    /** Pop out only from the viewer itself, not from browsing or an open sheet. */
    val canPip get() = !castPlaybackHandedOff && !externalDisplayRequested && tiles.isNotEmpty() && !browsing && picker == null

    /** The feed the pop-out shows: the big tile in main + strip, else the top-left one. */
    fun pipTile(): Tile? =
        if (layoutMode == LayoutMode.FEATURED) tiles.firstOrNull { it.id == bigTileId } ?: tiles.maxByOrNull { it.w }
        else tiles.minWithOrNull(compareBy<Tile>({ it.y }, { it.x }))

    /**
     * In the pop-out only the shown feed and the one with the audio keep playing; the rest pause
     * to save battery, and the sync loop catches them up on return.
     */
    fun setPip(on: Boolean) {
        inPip = on
        if (castPlaybackHandedOff) return
        val shown = pipTile()
        tiles.filter { !singleDecoderSceneActive || it === protectedCompositionHost }.forEach { tile ->
            tile.feed.player.playWhenReady = playing && (singleDecoderSceneActive || !on || tile === shown || tile.audible)
        }
        if (!on) showChrome()
    }

    /** Leaving the app without a pop-out (or closing the pop-out) pauses everything. */
    fun pauseForBackground() {
        if (castPlaybackHandedOff || externalDisplayActive || externalDisplayRequested && android.os.SystemClock.elapsedRealtime() < externalRequestDeadline) { saveWatch(); return }
        saveWatch()
        if (tiles.isNotEmpty() && playing) togglePlay()
    }

    /** Moves a tile to a new place and size along the app's one easing curve. */
    private fun glide(tile: Tile, to: Slot) {
        val from = Slot(tile.x, tile.y, tile.w)
        // animate() needs the display's frame clock, which only Compose's UI dispatcher carries;
        // the plain lifecycle scope has none and crashed the double-tap swap in 0.9 and 0.10.
        scope.launch(AndroidUiDispatcher.Main) {
            animate(0f, 1f, animationSpec = tween(Motion.SCREEN_MS, easing = EaseOut)) { f, _ ->
                tile.x = from.x + (to.x - from.x) * f
                tile.y = from.y + (to.y - from.y) * f
                tile.resize(from.w + (to.w - from.w) * f)
            }
        }
    }

    fun remove(tile: Tile) {
        singleDecoderRetryJob?.cancel(); singleDecoderRetryJob = null
        // A preflight also owns the selected set, before a composed host exists. Cancel it
        // when a feed is removed so it cannot hand off a released player afterwards.
        if (protectedCompositionHost != null || archiveCompositionStarting || f1DashCaptureJob != null)
            stopProtectedCompositionPresentation(keepSourceVideoDisabled = settings.decoderMode == DecoderMode.SINGLE)
        if (audioHint === tile) audioHint = null
        if (targetTile === tile) targetTile = null
        tileMenu = null
        tile.feed.release()
        tiles.remove(tile)
        if (tiles.isEmpty()) {
            stopAll()
            return
        }
        if (focusTileId == tile.id) focusTileId = null
        if (tile.audible) setAudio(tiles.first())
        if (bigTileId == tile.id) bigTileId = null
        // The rest close up to fill the space.
        handPlaced = false
        layoutRequest++
        if (settings.decoderMode == DecoderMode.SINGLE && tiles.all { it.contentId == tile.contentId }) decoderMode = DecoderMode.SINGLE
        if (decoderMode == DecoderMode.SINGLE && tiles.size >= 2) startArchiveSingleDecoderComposition(tile.contentId)
        else {
            singleDecoderFailure = null
            tiles.forEach { it.feed.clearProtectedCompositionVideoRole(); it.feed.player.playWhenReady = playing }
            applyVideoBudget()
        }
    }

    fun stopAll() {
        stopExternalDisplay()
        clearExternalDisplayRequest()
        singleDecoderRetryJob?.cancel(); singleDecoderRetryJob = null
        singleDecoderFailure = null
        // Save the receiver clock before the casting/local-host ownership is cleared.
        saveWatch()
        stopCasting(false)
        stopProtectedCompositionPresentation(restorePlayer = false)
        votePromptOpen = false
        nextLive = null
        liveAtStart = null
        f1DashCaptureJob?.cancel()
        f1DashCaptureJob = null
        f1DashCapture = null
        ProtectedHevcLiveComposition.clearAll()
        HevcFeedProfileCapture.clearTileTestSelection()
        tiles.forEach { it.feed.release() }
        tiles.clear()
        decoderMode = settings.decoderMode
        sessions.clear()
        singleSessionOnly.clear()
        browsing = false
        tileMenu = null
        targetTile = null
        helpOpen = false
        helpAnchors.clear()
        mapSheetOpen = false
        layoutSheetOpen = false
        arranging = false
        mutedAll = false
        playing = true
        timing = null
        timingContentId = null
        speedsJob?.cancel()
        speedsFor = null
        radioPlayer?.release()
        radioPlayer = null
        radioPlaying = null
        liveFeed?.cancel()
        liveFeed = null
        mapStatus = null
    }

    // Chrome: the controls fade away while watching and come back on touch.

    fun showChrome() {
        chromeVisible = true
        chromeTouch++
    }

    fun toggleChrome() {
        if (chromeVisible) chromeVisible = false else showChrome()
    }

    /** On a TV there's no touch: no dragging feeds around, and Back steps the controls away first. */
    // Match the same Leanback detection used by the focus-ring and remote controls. Some TV
    // launchers report NORMAL from UiModeManager even though the device exposes the TV feature.
    val isTv = isTvDevice(context)
    /** Whether something in the viewer has the remote's focus; arrows then move between controls. */
    var controlsFocused by mutableStateOf(false)
    /** Asks the viewer to put the remote's focus on play/pause. */
    var wantPlayFocus by mutableStateOf(false)

    /**
     * A remote's or keyboard's key while watching. With nothing focused, left and right skip and
     * the other arrows and select bring up the controls on play/pause. With a control focused the
     * keys do their usual moving and pressing, and only keep the controls up. Sheets are windows
     * of their own, so they never get here.
     */
    fun onRemoteKey(event: android.view.KeyEvent): Boolean {
        if (tiles.isEmpty() || browsing || inPip) return false
        val down = event.action == android.view.KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (first) togglePlay()
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> if (first && !playing) togglePlay()
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> if (first && playing) togglePlay()
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) remoteSkip(event, 10_000)
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) remoteSkip(event, -10_000)
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (helpOpen) {
                    if (first) closeHelp()
                } else if (controlsFocused) {
                    // The focused slider/map handle owns its D-pad keys even after the chrome
                    // timeout. Never reinterpret its left/right presses as media seeking.
                    if (down && !chromeVisible) showChrome()
                    return false
                } else when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (down) remoteSkip(event, -10_000)
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) remoteSkip(event, 10_000)
                    else -> if (first) {
                        showChrome()
                        wantPlayFocus = true
                    }
                }
            }
            else -> return false
        }
        return true
    }

    /** Holding the key repeats fast; every feed seeking on each repeat would only stall them. */
    private fun remoteSkip(event: android.view.KeyEvent, deltaMs: Long) {
        if (event.repeatCount % REMOTE_SKIP_EVERY == 0) skip(deltaMs)
    }

    // Shared playback. The first tile leads; the others follow it.

    private fun leaderTile() = if (nativeArchiveTiles.isNotEmpty()) protectedCompositionHost
        else protectedCompositionAudioMaster ?: tiles.firstOrNull()

    fun togglePlay() {
        playing = !playing
        if (castingActive) {
            remoteCastClock.update(castFramePositionMs, playing, playbackSpeed, durationMs)
            if (localChosen) localConnection?.send(if (playing) app.apexview.cast.local.FcastProtocol.RESUME else app.apexview.cast.local.FcastProtocol.PAUSE)
            else googleCastRemoteClient?.let { remote -> if (playing) remote.play() else remote.pause() }
            if (castPlaybackHandedOff) { showChrome(); return }
        }
        if (archiveCompositionStarting) { showChrome(); return }
        // In the pop-out, only the shown feed and the audio keep playing (see setPip).
        val shown = if (inPip) pipTile() else null
        tiles.filter { !singleDecoderSceneActive || it === protectedCompositionHost }.forEach {
            it.feed.player.playWhenReady = playing && (singleDecoderSceneActive || !inPip || it === shown || it.audible)
        }
        showChrome()
    }

    /** From the player itself, not positionMs: in the pop-out the sync loop that updates it isn't running. */
    /**
     * How fast every feed plays, 1 being normal: they all change together, so they stay in
     * step. Kept for the next video too.
     */
    var playbackSpeed by mutableStateOf(prefs.getFloat(PREF_SPEED, 1f).takeIf { it in SPEEDS } ?: 1f)
        private set
    var speedSheetOpen by mutableStateOf(false)
    var qualitySheetOpen by mutableStateOf(false)

    fun chooseVideoQuality(value: VideoQuality) {
        settings.updateVideoQuality(value)
        tiles.forEach { it.feed.setVideoQuality(value) }
        qualitySheetOpen = false
        showChrome()
    }

    fun chooseSpeed(value: Float) {
        if (castingActive) {
            remoteCastClock.update(castFramePositionMs, playing && !castRemoteBuffering, value, durationMs)
            if (localChosen) localConnection?.send(app.apexview.cast.local.FcastProtocol.SET_SPEED, JSONObject().put("speed", value))
            else googleCastRemoteClient?.setPlaybackRate(value.toDouble())
        }
        val slower = value < playbackSpeed
        playbackSpeed = value
        prefs.edit().putFloat(PREF_SPEED, value).apply()
        tiles.forEach { it.feed.setBaseSpeed(value) }
        radioPlayer?.setPlaybackSpeed(value)
        // Slower might be fine with every picture: try it (unless this phone already couldn't).
        if (slower && speedHeld) {
            speedHeld = false
            if (value <= 1f) message = null
        }
        resetStrain()
        applyVideoBudget()
        showChrome()
    }

    /**
     * Sped up, only the main feed shows its picture: set when this phone couldn't keep every
     * feed smooth at this speed. The others keep their sound and their place, so their pictures
     * come back in step.
     */
    var speedHeld by mutableStateOf(false)
        private set

    /** The slowest speed each number of feeds has struggled at, while the app's been running. */
    private val struggledAt = mutableMapOf<Int, Float>()
    /** Frames dropped across the feeds in each of the last few seconds. */
    private val dropsBySecond = ArrayDeque<Int>()
    private var lastDropTotal = -1
    private var strainTicks = 0
    private var steadyFeeds: List<FeedPlayer> = emptyList()
    private var steadySinceMs = 0L

    private fun resetStrain() {
        dropsBySecond.clear()
        lastDropTotal = -1
    }

    /**
     * Sped up with several pictures, whether the phone keeps up: if it keeps dropping frames,
     * only the main feed's picture plays from then on. Some phones and TVs manage every feed at
     * double speed, others don't; what counts is how this one does.
     */
    private fun watchStrain() {
        val pictures = tiles.filter { it.feed.showingVideo }
        if (playbackSpeed <= 1f || speedHeld || pictures.size < 2) {
            resetStrain()
            return
        }
        // Feeds starting, buffering or catching up drop frames anyway: only a steady few
        // seconds of the same feeds playing count.
        val now = System.currentTimeMillis()
        val feeds = pictures.map { it.feed }
        if (feeds != steadyFeeds || pictures.any { it.feed.player.playbackState != Player.STATE_READY || !it.feed.player.isPlaying }) {
            steadyFeeds = feeds
            steadySinceMs = now
            resetStrain()
            return
        }
        if (now - steadySinceMs < STEADY_MS) return
        if (++strainTicks % 2 != 0) return
        val total = pictures.sumOf { it.feed.player.videoDecoderCounters?.droppedBufferCount ?: 0 }
        val last = lastDropTotal
        lastDropTotal = total
        if (last < 0) return
        dropsBySecond.addLast((total - last).coerceAtLeast(0))
        if (dropsBySecond.size > STRAIN_WINDOW_S) dropsBySecond.removeFirst()
        if (dropsBySecond.count { it >= STRAIN_DROPS } < STRAIN_SECONDS) return
        struggledAt[tiles.size] = minOf(struggledAt[tiles.size] ?: Float.MAX_VALUE, playbackSpeed)
        speedHeld = true
        resetStrain()
        applyVideoBudget()
        message = "This phone can't keep ${tiles.size} feeds smooth at ${speedLabel(playbackSpeed)}, so only the main " +
            "feed's picture plays while sped up. Back to 1× brings the rest back."
    }

    fun skip(deltaMs: Long) {
        val leader = leaderTile() ?: return
        seekTo((if (castPlaybackHandedOff) castFramePositionMs else leader.feed.player.currentPosition) + deltaMs)
    }

    fun seekTo(targetMs: Long) {
        if (castingActive) {
            val target = if (durationMs > 0) targetMs.coerceIn(0L, durationMs) else targetMs.coerceAtLeast(0L)
            remoteCastClock.update(target, playing, playbackSpeed, durationMs)
            if (localChosen) localConnection?.send(app.apexview.cast.local.FcastProtocol.SEEK, JSONObject().put("time", target / 1000.0))
            else googleCastRemoteClient?.seek(target)
            if (castPlaybackHandedOff) { positionMs = target; showChrome(); return }
        }
        val restartRollingComposition = composedArchiveOriginMs != null && decoderMode == DecoderMode.SINGLE
        if (composedArchiveOriginMs != null) {
            stopProtectedCompositionPresentation()
            layoutMode = LayoutMode.GRID
            singleDecoderDiagnostic = singleDecoderDiagnostic?.replace("Playback: ACTIVE", "Playback: STOPPED FOR SEEK")
        }
        val leader = leaderTile() ?: return
        // A live stream's length can be unknown; then only the start bounds the seek (the player
        // stops at the live edge itself). Clamping to an unknown length sent skips back to 0.
        val duration = leader.feed.player.duration.takeIf { it > 0 } ?: durationMs.takeIf { it > 0 }
        val target = if (duration != null) targetMs.coerceIn(0L, duration) else targetMs.coerceAtLeast(0L)
        if (singleDecoderSceneActive) protectedCompositionHost?.feed?.prioritizeArchiveSeek(target)
        val shift = target - leader.feed.player.currentPosition
        // Every tile moves by the same amount, so their offsets from each other survive the seek.
        tiles.filter { nativeArchiveTiles.isEmpty() || it === protectedCompositionHost }.forEach { it.feed.player.seekTo((it.feed.player.currentPosition + shift).coerceAtLeast(0L)) }
        positionMs = target
        if (restartRollingComposition) startArchiveSingleDecoderComposition(leader.contentId)
        showChrome()
    }

    fun goLive() {
        tiles.forEach { it.feed.player.seekToDefaultPosition() }
        showChrome()
    }

    /**
     * Runs twice a second. Followers of the same content line up with the leader by stream clock
     * when both streams carry one, else by position. Far off: jump. Slightly off: play a little
     * faster or slower until caught up, the way eXViewer does.
     */
    fun syncTick() {
        if (castPlaybackHandedOff) {
            positionMs = remoteCastClock.positionMs()
            tiles.forEach { it.feed.suspendForRemotePlayback(true) }
            if (++saveTicks % 10 == 0) saveWatch()
            return
        }
        val leaderTile = leaderTile() ?: return
        val leader = leaderTile.feed
        composedArchiveOriginMs?.let {
            protectedCompositionHost?.let { host ->
                val sourcesMayPlay = ArchiveBufferPolicy.audioMayPlay(playing,
                    host.feed.player.playbackState == Player.STATE_READY, composedArchiveFillingBuffer)
                tiles.filter { it !== host && it.contentId == leaderTile.contentId }
                    .forEach { it.feed.player.playWhenReady = sourcesMayPlay }
                host.feed.player.playWhenReady = playing && leader.player.playbackState == Player.STATE_READY && !composedArchiveFillingBuffer
            }
        }
        leader.driftMs = null
        leader.setSpeed(1f)
        positionMs = leader.player.currentPosition
        durationMs = leader.player.duration.takeIf { it > 0 } ?: 0L
        isLive = leader.player.isCurrentMediaItemLive
        if (leader.player.playbackState != Player.STATE_READY) return
        // Faster than real time runs into the live edge, where there's nothing more to play.
        if (isLive && playbackSpeed > 1f && durationMs > 0 && durationMs - positionMs < LIVE_EDGE_MS) {
            chooseSpeed(1f)
            message = "Caught up to live, so back to normal speed."
        }
        // Twice a second, so every 10th tick is a save every 5 s.
        if (++saveTicks % 10 == 0) saveWatch()
        watchStrain()

        composedArchiveOriginMs?.let { origin ->
            protectedCompositionHost?.feed?.syncComposedArchivePosition(leader.player.currentPosition - origin, playing && !composedArchiveFillingBuffer)
        }
        val leaderClock = leader.wallClockMs()
        tiles.filter { it !== leaderTile && it !== protectedCompositionHost && it !in nativeArchiveTiles }.forEach { tile ->
            val feed = tile.feed
            if (tile.contentId != leaderTile.contentId || feed.player.playbackState != Player.STATE_READY) {
                feed.driftMs = null
                feed.setSpeed(1f)
                return@forEach
            }
            val clock = feed.wallClockMs()
            val drift = if (leaderClock != null && clock != null) clock - leaderClock
            else feed.player.currentPosition - leader.player.currentPosition
            feed.driftMs = drift

            when {
                abs(drift) > SEEK_DRIFT_MS -> {
                    feed.setSpeed(1f)
                    feed.player.seekTo((feed.player.currentPosition - drift).coerceAtLeast(0L))
                }
                abs(drift) > NUDGE_DRIFT_MS && playing -> feed.setSpeed(if (drift > 0) 1f - NUDGE else 1f + NUDGE)
                else -> feed.setSpeed(1f)
            }
        }
    }

    fun dismissMessage() {
        message = null
    }

    private fun task(what: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            val progress = "$what..."
            message = progress
            try {
                block()
                if (message == progress) message = null
            } catch (e: Exception) {
                message = "$what failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    companion object {
        private const val SINGLE_DECODER_ACTIVATING = "Single decoder activation in progress."
        private const val SINGLE_DECODER_AUTO_RETRIES = 2
        private const val SEEK_DRIFT_MS = 2000L
        private const val NUDGE_DRIFT_MS = 150L
        private const val NUDGE = 0.1f
        /** Frames dropped across the feeds in a second that counts as struggling... */
        private const val STRAIN_DROPS = 8
        /** ...in this many of the last [STRAIN_WINDOW_S] seconds. */
        private const val STRAIN_SECONDS = 3
        private const val STRAIN_WINDOW_S = 5
        /** How long the same feeds have to have been playing before dropped frames count. */
        private const val STEADY_MS = 10_000L
        const val MAX_FEEDS = 4
        const val MAX_SINGLE_DECODER_FEEDS = 24
        private const val MAX_RECENTS = 4
        /** F1 TV's race weekends start with 2018; before that, a season is a set of videos. */
        const val FIRST_WEEKEND_SEASON = 2018
        private const val FIRST_ARCHIVE_SEASON = 1981
        private const val PREF_LAPTOP = "laptopMode"
        private const val PREF_LAPTOP_ASKED = "laptopAsked"
        private const val COMPACT_DP = 600f
        private const val PREF_RADIO = "radioOpen"
        private const val PREF_HELP_SEEN = "helpSeen"
        private const val PREF_RADIO_SPOT = "radioSpot"
        private const val PREF_TOWER = "towerOpen"
        private const val PREF_TOWER_SPOT = "towerSpot"
        private const val PREF_TOWER_OPACITY = "towerOpacity"
        private const val PREF_TOWER_FONT_SCALE = "towerFontScale"
        private const val PREF_TOWER_LOCKED = "towerLocked"
        private const val PREF_TOWER_RESERVE = "towerReserve"
        const val TOWER_DEFAULT_WIDTH_DP = 380f
        const val TOWER_DEFAULT_HEIGHT_DP = 720f
        const val TOWER_MIN_WIDTH_DP = 300f
        const val TOWER_MIN_HEIGHT_DP = 260f
        const val TOWER_MIN_FONT_SCALE = 0.7f
        const val TOWER_MAX_FONT_SCALE = 1.5f
        private const val PREF_SPEED = "playbackSpeed"
        /** The playback speeds on offer. */
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        /** This close to the live edge counts as live: speeding up would only run into it. */
        private const val LIVE_EDGE_MS = 30_000L
        /** Clips this far ahead of the picture get transcribed early, so they're ready on time. */
        private const val TRANSCRIBE_AHEAD_MS = 120_000L
        const val NO_WORDS = "(no words recognised)"
        const val PERMISSION_NOTE =
            "Android's speech recogniser needs the microphone permission to transcribe. The app never records you."
        private const val TRANSCRIBE_RETRY_MS = 120_000L
        /** A minute of waiting for a stream to say whether it's live. */
        private const val LIVE_CHECK_TRIES = 240

        /** How long the map waits before trying again, after its [attempt]th failure (from 0). */
        internal fun mapRetryDelayMs(attempt: Int): Long = MAP_RETRY_MS.getOrElse(attempt) { MAP_RETRY_MS.last() }
        private val MAP_RETRY_MS = listOf(3_000L, 10_000L, 30_000L, 60_000L)
        /** The feeds' volume while a radio clip plays: silent, so the radio is clear. */
        private const val RADIO_DUCK = 0f
        private const val PREF_RECENTS = "recents"
        private const val PREF_HISTORY = "history"
        private val NO_VIDEO_TIERS = setOf("ANONYMOUS", "REG")
        private const val REMOTE_SKIP_EVERY = 5
        private const val SHRUNK_REACH = 0.8f
        private const val MAX_HISTORY = 300
        private const val MAX_PICKS = 7
        private const val DAY_MS = 86_400_000L
        private val DAY = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
        private const val PREF_LAYOUT = "layout"
        private const val PREF_DEFAULT_LAYOUT = "defaultLayout"
        private const val PREF_SNAP = "snap"
        private const val PREF_MAP_PANEL = "mapPanel"
        private const val PREF_LAPTOP_MAP = "laptopMapPanel"
        /** How much of the width below the hinge the timing tower takes in laptop mode. */
        const val LAPTOP_TOWER_SHARE = 0.52f
        private const val PREF_MAP_LOCKED = "mapLocked"
        private const val PREF_MAP_RESERVE = "mapReserve"
        private const val PREF_OVERLAY_LAYOUT = "overlayLayoutMode"
        private const val PREF_MAP_DOT_SIZE = "mapDotSizeDp"
        private const val PREF_MAP_LABEL_SIZE = "mapLabelSizeSp"
        private const val MIN_MAP_DOT_SIZE = 3f
        private const val MAX_MAP_DOT_SIZE = 8f
        private const val MIN_MAP_LABEL_SIZE = 7f
        private const val MAX_MAP_LABEL_SIZE = 12f
        private const val PREF_START_LINE = "startLine"
        private const val PREF_PIT_LOSS = "pitLoss"
        // Snap mode: edges pull together inside 12dp; a push past 56dp goes through.
        private const val SNAP_DP = 12f
        private const val BREAK_DP = 56f
        private const val PREF_MAP_OFFSET = "mapOffsetMs"
        private const val DEFAULT_MAP_OFFSET_MS = -7_000L
        private const val PREF_SAVED_WATCH = "savedWatch"
        private const val PREF_MAP_MODE = "mapMode"
        private const val ACCOUNT_URL = "https://account.formula1.com/"
        private const val VOTE_URL = "https://www.formula1.com/en/page/vote"
        private const val PREF_VOTE_ASKED = "voteAskedFor"
        private const val PREF_VOTE_SEEN = "voteSeen"
        /**
         * F1's own "vote now" posts in 2026 came with 8 to 15 laps to go, so by 6 the vote is
         * surely open.
         */
        private const val VOTE_LAPS_LEFT = 6
        /** The vote sheet's share of the screen: its height from the bottom, its width at the side. */
        private const val VOTE_SHEET_HEIGHT = 0.6f
        const val VOTE_SHEET_WIDTH = 0.45f
        private const val VOTE_REQUEST = 4057
        /** F1 names its Driver of the Day soon after the flag; past this the vote is long shut. */
        private const val VOTE_OPEN_AFTER_MS = 15 * 60_000L
    }
}
