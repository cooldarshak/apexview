package app.apexview.cast.local

import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.apexview.*
import app.apexview.R
import app.apexview.cast.CastSceneProtocol
import app.apexview.cast.CastTimingProtocol
import app.apexview.cast.CastTimingReceiver
import app.apexview.cast.CastTimingLayer
import app.apexview.scene.SceneCodecTimestampCorrelation
import app.apexview.scene.SingleDecoderSceneView
import app.apexview.scene.ProtectedDecoderSceneView
import app.apexview.scene.VideoScene
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

/** An opt-in foreground TV receiver. Exactly one ExoPlayer/video renderer, irrespective of windows. */
class PitwallReceiverActivity : ComponentActivity() {
    private var status by mutableStateOf("Starting local receiver…")
    private var receiverName by mutableStateOf("Pitwall TV ${Build.MODEL}")
    private var senderName by mutableStateOf("Phone")
    private var pending by mutableStateOf<JSONObject?>(null)
    private var scene by mutableStateOf<VideoScene?>(null)
    private var active by mutableStateOf(false)
    private var generation by mutableIntStateOf(0)
    private var player: ExoPlayer? by mutableStateOf(null)
    private var receiverTrackSelector: androidx.media3.exoplayer.trackselection.DefaultTrackSelector? = null
    // Context is not attached during Activity field initialization.
    // Restore per-TV caption preferences only after super.onCreate().
    private var captionsOn by mutableStateOf(false)
    private var captionLanguage = CaptionPolicy.AUTO
    private lateinit var captionSettings: Settings
    private val captionAppearance get() = captionSettings.captionAppearance
    private var receiverCaptionTrackCount by mutableIntStateOf(0)
    private var receiverCaptionTracks by mutableStateOf<List<CaptionTrackOption>>(emptyList())
    private var captionOwnerControl = false
    private var receiverControlsFocused by mutableStateOf(false)
    private var captionSettingsMenu by mutableStateOf(false)
    private var pendingNativeAudio: Int? = null
    private var submittedNativeAudio: Int? = null
    private var confirmedNativeAudio: Int? = null
    private var nativeProgrammeAudio = false
    private var receiverCaptionReady by mutableStateOf(false)
    private var directQuality = VideoQuality.AUTO
    private var directQualityOffers = org.json.JSONArray()
    private var directActualInput: Pair<Int, Int>? = null

    private fun isBakedPlayback() = playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optBoolean("pitwallBaked") == true

    private fun requestReceiverCaptions(enabled: Boolean, language: String) {
        if (isBakedPlayback()) {
            if (!captionOwnerControl || playbackId().isEmpty()) {
                status = "Change baked captions on the phone."
                return
            }
            connection?.send(FcastProtocol.UI_CONTROL, CaptionControlProtocol.forLoad(enabled, language, playbackId(), captionAppearance))
            return // Wait for the actual phone renderer's acknowledged preference.
        }
        setReceiverCaptions(enabled, language)
        if (captionOwnerControl && playbackId().isNotEmpty())
            connection?.send(FcastProtocol.UI_CONTROL, CaptionControlProtocol.forLoad(enabled, language, playbackId(), captionAppearance))
    }
    private fun saveReceiverCaptionAppearance() {
        captionSettings.saveCaptionAppearance()
        if (captionOwnerControl && playbackId().isNotEmpty()) {
            connection?.send(FcastProtocol.UI_CONTROL,
                CaptionControlProtocol.forLoad(captionsOn, captionLanguage, playbackId(), captionAppearance))
        }
    }

    private fun setReceiverCaptions(enabled: Boolean, language: String) {
        captionsOn = enabled
        captionLanguage = CaptionPolicy.normalizeLanguage(language)
        captionSettings.apply {
            updateSubtitles(enabled)
            updateCaptionLanguage(captionLanguage)
        }
        player?.let { updateReceiverCaptions(it, it.currentTracks) }
        Log.i("PitwallReceiver", "CC_STATE enabled=$enabled language=$captionLanguage offered=$receiverCaptionTrackCount")
    }
    private fun updateReceiverCaptions(current: ExoPlayer, tracks: androidx.media3.common.Tracks) {
        val programme = confirmedNativeAudio.takeIf { pendingNativeAudio == null }
        val candidates = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter { index ->
                group.isTrackSupported(index) && (!nativeProgrammeAudio ||
                    programme != null && CaptionPolicy.castProgramme(group.getTrackFormat(index).id) == programme)
            }.map { group to it }
        }
        val formats = candidates.map { (group, index) -> group.getTrackFormat(index) }
        receiverCaptionTracks = CaptionPolicy.tracks(formats.map { it.language to it.label })
        receiverCaptionTrackCount = receiverCaptionTracks.size
        val preference = if (captionsOn && !isBakedPlayback()) CaptionPolicy.preferredIndex(
            captionLanguage, "en", formats.map { it.language },
            formats.map { it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 },
        ) else null
        val selected = preference?.let(candidates::get)
        receiverCaptionReady = selected?.let { (group, index) -> group.isTrackSelected(index) } == true
        val previous = current.trackSelectionParameters
        val updated = previous.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selected == null)
            .apply {
                selected?.let { (group, index) ->
                    setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index))
                }
            }.build()
        if (updated != previous) current.trackSelectionParameters = updated
    }

    private var sceneView: SingleDecoderSceneView? = null
    private var protectedSceneView: ProtectedDecoderSceneView? = null
    private var sceneCodecCorrelation: SceneCodecTimestampCorrelation? = null
    private var receiverCadence: AdaptivePlaybackCadenceObserver? = null
    private var receiverDisplayOwner: Any? = null

    private fun setReceiverOutputDisplay(owner: Any, displayId: Int?) {
        if (displayId == null) {
            if (receiverDisplayOwner !== owner) return
            receiverDisplayOwner = null
            receiverCadence?.activeDisplayId = -1
        } else {
            receiverDisplayOwner = owner
            receiverCadence?.activeDisplayId = displayId
        }
    }
    private var widevine: ReceiverWidevine? = null
    private var activeReceiverAllocator: DefaultAllocator? = null
    private var receiverMemorySnapshot: (() -> Unit)? = null
    private var secureOutput by mutableStateOf(false)
    private var loadingJob: kotlinx.coroutines.Job? = null
    private var loadEpoch = 0L
    private var loadingRequested = false
    private var desiredPlaying = true
    private var desiredSpeed by mutableFloatStateOf(1f)
    private var desiredVolume = 1f
    private var standardView: PlayerView? = null
    private val httpDelegate = lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build() }
    private val receiverHttp by httpDelegate
    private var server: ServerSocket? = null
    private var advertisement: PitwallAdvertisement? = null
    private var connection: FcastConnection? = null
    private var pairingCode by mutableStateOf<String?>(null)
    private var televisionSelectionCode by mutableStateOf<String?>(null)
    private var pairingTimeout: kotlinx.coroutines.Job? = null
    private var pendingManualInitial: JSONObject? = null
    private val timingState = CastTimingReceiver()
    private var timingPeer = false
    private var timingReported = false
    private var lastTimingHealthAtMs = -1L
    private var approved = false
    private var versionReceived = false
    private var pitwallPeer = false
    private var playData: JSONObject? = null
    private var renderingReported = false
    private var destroyed = false
    private var listeningJob: kotlinx.coroutines.Job? = null
    private var decoderCount = 0
    private var controlsVisible by mutableStateOf(true)
    private var controlsJob: kotlinx.coroutines.Job? = null

    private var frameDiagnostics: app.apexview.WindowFrameDiagnostics? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.apexview.VideoThermalBudget.loadPreference(this)
        captionSettings = Settings(this)
        captionsOn = captionSettings.subtitles
        captionLanguage = captionSettings.captionLanguage
        DiagnosticLogs.startIfEnabled(applicationContext)
        if (DiagnosticLogs.isEnabled(applicationContext)) {
            frameDiagnostics = app.apexview.WindowFrameDiagnostics(window, role = "receiver")
        }
        CrashReports.install(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AppTheme { ReceiverScreen() } }
        lifecycleScope.launch {
            while (true) {
                delay(1_000)
                if (versionReceived) connection?.send(FcastProtocol.PING)
                if (approved) sendPlayback()
                if (approved && timingPeer && active) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastTimingHealthAtMs < 0L || now - lastTimingHealthAtMs >= 5_000L) {
                        val h = timingState.health(now)
                        val motionFrame = timingState.frame
                        val videoPositionMs = controllerPosition()
                        val mapClockDeltaMs = motionFrame?.let { videoPositionMs - it.positionMs } ?: Long.MIN_VALUE
                        val mapFutureHeadroomMs = motionFrame?.cars?.firstOrNull()?.let { car ->
                            (car.later.size + 1) * CastTimingProtocol.POSITION_STEP_MS
                        } ?: 0L
                        val mapPredictionSaturated = motionFrame != null &&
                            mapClockDeltaMs > mapFutureHeadroomMs && mapFutureHeadroomMs > 0L
                        Log.i("PitwallReceiver", "TIMING_RX_HEALTH " +
                            "frames=${h.framesAccepted} tables=${h.tablesAccepted} " +
                            "rejected=${h.framesRejected} tableMismatch=${h.tableVersionRejects} " +
                            "epochMismatch=${h.clockEpochRejects} geometryMismatch=${h.geometryRejects} " +
                            "staleSequence=${h.sequenceRejects} recoveredTableAhead=${h.aheadTableFramesRecovered} " +
                            "frameAgeMs=${h.lastFrameAgeMs} tableAgeMs=${h.lastTableAgeMs} " +
                            "epoch=${h.clockEpoch} mapClockDeltaMs=${if (motionFrame == null) -1L else mapClockDeltaMs} " +
                            "mapFutureHeadroomMs=$mapFutureHeadroomMs mapPredictionSaturated=$mapPredictionSaturated")
                        // Optional diagnostic subtype within the already negotiated timing
                        // channel. Older senders ignore it, and no media/DRM wire format changes.
                        connection?.send(FcastProtocol.TIMING, JSONObject()
                            .put("kind", "health").put("playbackId", playbackId())
                            .put("frames", h.framesAccepted).put("tables", h.tablesAccepted)
                            .put("rejected", h.framesRejected)
                            .put("tableMismatch", h.tableVersionRejects)
                            .put("recoveredTableAhead", h.aheadTableFramesRecovered)
                            .put("epochMismatch", h.clockEpochRejects)
                            .put("frameAgeMs", h.lastFrameAgeMs)
                            .put("tableAgeMs", h.lastTableAgeMs).put("epoch", h.clockEpoch)
                            .put("mapClockDeltaMs", if (motionFrame == null) -1L else mapClockDeltaMs)
                            .put("mapFutureHeadroomMs", mapFutureHeadroomMs)
                            .put("mapPredictionSaturated", mapPredictionSaturated))
                        lastTimingHealthAtMs = now
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (listeningJob?.isActive != true) startListening()
    }
    private fun startListening() {
        listeningJob = lifecycleScope.launch(Dispatchers.IO) {
            var ownedSocket: ServerSocket? = null
            try {
                val identity = PitwallTls.identity()
                val listening = try { PitwallTls.controlServer(identity, FcastProtocol.PORT) }
                    catch (_: java.io.IOException) { PitwallTls.controlServer(identity, 0) }
                ownedSocket = listening
                withContext(Dispatchers.Main) {
                    if (destroyed) { listening.close(); return@withContext }
                    server = listening
                    televisionSelectionCode = PitwallPairingCode.televisionIdentity(identity.certificate.encoded)
                    advertisement = PitwallAdvertisement(this@PitwallReceiverActivity, listening.localPort, receiverName) { name, ok ->
                        if (ok) { receiverName = name; if (!active) status = "Ready. Choose this TV from Cast on your phone." }
                        else status = name
                    }
                }
                while (!listening.isClosed) {
                    val socket = listening.accept()
                    var delivered = false
                    try {
                        if (socket.inetAddress.isSiteLocalAddress || socket.inetAddress.isLinkLocalAddress) {
                            val secured = PitwallTls.accept(identity, socket)
                            withContext(Dispatchers.Main) { accept(secured); delivered = true }
                        }
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        Log.w("PitwallReceiver", "TLS_HANDSHAKE_REJECTED type=${failure.javaClass.simpleName}")
                    } finally { if (!delivered) runCatching { socket.close() } }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { if (!destroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) status = "Receiver stopped: ${error.javaClass.simpleName}. Reopen to retry." }
            } finally { runCatching { ownedSocket?.close() } }
        }
    }

    private fun accept(secured: PitwallTls.Provisional) {
        val socket = secured.socket
        if (destroyed || connection != null || !(socket.inetAddress.isSiteLocalAddress || socket.inetAddress.isLinkLocalAddress)) {
            socket.close(); return
        }
        approved = false; versionReceived = false; pitwallPeer = false; captionOwnerControl = false; timingPeer = false; senderName = "Phone"
        pendingManualInitial = null
        lateinit var next: FcastConnection
        next = FcastConnection(lifecycleScope, socket, { packet ->
            if (connection === next) handle(packet)
        }, { reason ->
            if (connection === next) {
                connection = null; pending = null; approved = false; versionReceived = false
                pairingCode = null; pairingTimeout?.cancel(); pairingTimeout = null
                pendingManualInitial = null
                stopPlayback()
                status = "Phone disconnected. Ready for another connection."
                Log.i("PitwallReceiver", "CONNECTION_CLOSED reason=$reason")
            }
        }, secureSession = secured.session)
        connection = next
        if (PitwallRememberedPeers.contains(this, secured.session.trustKey)) {
            startApprovedConnection(next)
            // Display the code without prompting, in case the phone forgot us.
            status = "Remembered phone. Code: ${secured.session.code}"
            return
        }
        requestPairingConfirmation(next, secured.session.code)
    }

    private fun requestPairingConfirmation(next: FcastConnection, code: String) {
        pairingCode = code
        status = "Compare the pairing code on your phone."
        pairingTimeout?.cancel()
        pairingTimeout = lifecycleScope.launch {
            delay(90_000)
            if (connection === next && pairingCode != null) {
                pairingCode = null
                next.close()
            }
        }
    }

    // Persist deliberate disconnect across receiver activity recreation.
    private fun disconnectPhone() {
        val owner = connection ?: return
        owner.secureSession?.trustKey?.let { PitwallRememberedPeers.requireManualReconnect(this, it) }
        if (!approved) { owner.close(); return }
        approved = false
        stopPlayback()
        // Tell an updated phone not to retry a deliberate local disconnect.
        owner.send(FcastProtocol.ERROR, JSONObject().put("code", "USER_DISCONNECT")
            .put("message", "The TV ended the connection."))
        lifecycleScope.launch {
            delay(250)
            if (connection === owner) owner.close()
        }
    }

    private fun confirmPairing(matches: Boolean) {
        val next = connection ?: return
        if (pairingCode == null) return
        pairingCode = null
        pairingTimeout?.cancel(); pairingTimeout = null
        val initial = pendingManualInitial
        pendingManualInitial = null
        if (!matches) { next.close(); return }
        if (initial != null) {
            // A legacy remembered sender has no authenticated retry marker.
            // Physical confirmation is required after a deliberate disconnect,
            // before advertising receiver readiness or accepting any playback.
            PitwallRememberedPeers.allowManualReconnect(this, requireNotNull(next.secureSession).trustKey)
            approved = true
            acceptInitial(next, initial)
            sendReceiverInitial(next)
        } else startApprovedConnection(next)
    }

    private fun startApprovedConnection(next: FcastConnection) {
        check(connection === next && next.secureSession != null)
        approved = true
        next.start(handshakeTimeoutMs = 100_000)
        next.send(FcastProtocol.VERSION, JSONObject().put("version", 3))
        status = "Paired securely. Waiting for playback request…"
    }

    private fun sendReceiverInitial(owner: FcastConnection) {
        owner.send(FcastProtocol.INITIAL, JSONObject().put("displayName", receiverName)
            .put("appName", "Pitwall TV").put("appVersion", BuildConfig.VERSION_NAME).put("pitwallScene", 1).put("pitwallOverlays", 1)
            .put("pitwallWidevine", 1).put("pitwallDirect", 1).put("pitwallCaptions", 1).put("pitwallCaptionOwnerControl", 1)
            .put("pitwallMapPredictionSteps", CastTimingProtocol.MAX_PREDICTION_STEPS)
            .put("pitwallCaptionTrackMetadata", 1).put("pitwallDirectQuality", 1).put("pitwallRememberedPeers", 1))
    }

    private fun acceptInitial(owner: FcastConnection, body: JSONObject) {
        check(connection === owner && approved && versionReceived && !pitwallPeer)
        owner.handshakeComplete()
        senderName = body.optString("displayName").take(80).ifBlank { "Phone" }
        pitwallPeer = body.optInt("pitwallScene") == 1
        timingPeer = body.optInt("pitwallOverlays") == 1
        captionOwnerControl = body.optInt("pitwallCaptionOwnerControl") == 1
        if (body.optInt("pitwallRememberedPeers") == 1) {
            owner.secureSession?.let { PitwallRememberedPeers.remember(this, it.trustKey) }
        }
        status = "Paired securely. Waiting for playback request…"
    }

    private suspend fun handle(packet: FcastProtocol.Packet) {
        val body = packet.body
        try {
            when (packet.opcode) {
                FcastProtocol.VERSION -> {
                    require(!versionReceived) { "Duplicate FCast handshake" }
                    require(body != null && body.getInt("version") >= 2) { "FCast version 2 or 3 is required" }
                    versionReceived = true
                    val owner = requireNotNull(connection)
                    if (!PitwallRememberedPeers.requiresManualReconnect(this, requireNotNull(owner.secureSession).trustKey))
                        sendReceiverInitial(owner)
                }
                FcastProtocol.PING -> connection?.send(FcastProtocol.PONG)
                FcastProtocol.PONG -> Unit
                FcastProtocol.INITIAL -> {
                    require(approved && versionReceived && !pitwallPeer && body?.optInt("pitwallScene") == 1)
                    val owner = requireNotNull(connection)
                    val peer = requireNotNull(owner.secureSession).trustKey
                    val initial = requireNotNull(body)
                    val blocked = PitwallRememberedPeers.requiresManualReconnect(this, peer)
                    if (blocked) {
                        when (initial.opt("pitwallAutomaticRetry")) {
                            true -> {
                                approved = false
                                owner.close()
                                return
                            }
                            false -> PitwallRememberedPeers.allowManualReconnect(this, peer)
                            else -> {
                                approved = false
                                pendingManualInitial = initial
                                requestPairingConfirmation(owner, requireNotNull(owner.secureSession).code)
                                return
                            }
                        }
                    }
                    acceptInitial(owner, initial)
                    if (blocked) sendReceiverInitial(owner)
                }
                FcastProtocol.PLAY -> {
                    require(approved && versionReceived && pitwallPeer && body != null)
                    validatePlay(body)
                    play(body)
                }
                else -> {
                    if (!approved) return
                    when (packet.opcode) {
                        FcastProtocol.PAUSE -> { desiredPlaying = false; player?.pause() }
                        FcastProtocol.RESUME -> { desiredPlaying = true; player?.play() }
                        FcastProtocol.STOP -> stopPlayback()
                        FcastProtocol.SEEK -> player?.let { current ->
                            val target = (number(body, "time", 0.0, 604800.0) * 1000).toLong()
                            val origin = streamMetadata()?.getLong("originMs")
                            current.seekTo(ReceiverTimeline.windowSeek(target, origin, windowOffsetMs(current), current.duration))
                        }
                        FcastProtocol.SET_SPEED -> { desiredSpeed = number(body, "speed", 0.25, 4.0).toFloat(); player?.setPlaybackSpeed(desiredSpeed) }
                        FcastProtocol.SET_VOLUME -> { desiredVolume = number(body, "volume", 0.0, 1.0).toFloat(); player?.volume = desiredVolume }
                        FcastProtocol.UI_CONTROL -> when (body?.optString("action")) {
                            "captions" -> {
                                if (!CaptionControlProtocol.matchesLoad(requireNotNull(body), playbackId(), captionOwnerControl)) return
                                val requested = CaptionControlProtocol.decode(body)
                                requested.appearance?.let {
                                    captionSettings.previewCaptionAppearance(it)
                                    captionSettings.saveCaptionAppearance()
                                }
                                setReceiverCaptions(requested.enabled, requested.language)
                            }
                            "directQuality" -> {
                                if (!isDirectPlayback()) return
                                val requested = DirectQualityProtocol.decode(requireNotNull(body), playbackId()) ?: return
                                if (requested != directQuality) {
                                    directQuality = requested
                                    player?.let { applyDirectQuality(it, it.currentTracks) }
                                }
                            }
                            "nativeAudio" -> {
                                val index = NativeAudioControl.decode(requireNotNull(body), playbackId()) ?: return
                                if (playData?.optJSONObject("metadata")?.optJSONObject("custom")?.has("pitwallAudio") != true) return
                                pendingNativeAudio = index
                                submittedNativeAudio = null
                                player?.let {
                                    selectNativeAudio(it, it.currentTracks)
                                    updateReceiverCaptions(it, it.currentTracks)
                                }
                            }
                        }
                        FcastProtocol.TIMING -> if (timingPeer && body != null) {
                            try { timingState.acceptAsync(body, playbackId()) }
                            catch (error: Exception) { Log.w("PitwallReceiver", "Timing display message rejected: ${error.javaClass.simpleName}") }
                            return // The periodic playback update carries the clock independently of overlay updates.
                        }
                        FcastProtocol.SCENE -> {
                            require(pitwallPeer && body != null)
                            val loadId = body.optString("playbackId")
                            if (loadId.isNotEmpty() && loadId != playbackId()) return
                            val old = scene ?: return // The incoming PLAY already carries its first layout.
                            val decoded = CastSceneProtocol.decode(body)
                            val next = if (isDirectPlayback()) decoded.copy(atlasWidth = old.atlasWidth, atlasHeight = old.atlasHeight,
                                atlasColumns = 1, sourceCodedWidth = old.sourceCodedWidth, sourceCodedHeight = old.sourceCodedHeight,
                                sourceDisplayWidth = old.sourceDisplayWidth, sourceDisplayHeight = old.sourceDisplayHeight) else decoded
                            val changed = app.apexview.cast.CastSceneTransitionPolicy.valid(
                                old, next, direct = isDirectPlayback()
                            )
                            if (changed && !next.sameEncodedAtlasAs(old)) {
                                Log.i("PitwallReceiver", "SCENE_ADAPTIVE_ATLAS_CHANGED " +
                                    "old=${old.atlasWidth}x${old.atlasHeight} " +
                                    "new=${next.atlasWidth}x${next.atlasHeight} " +
                                    "sources=${next.sources} view=ONE_DECODER")
                            }
                            scene = next
                        }
                        else -> Unit // Unknown optional messages are not executed.
                    }
                    sendPlayback()
                }
            }
        } catch (error: Exception) {
            val loadId = if (packet.opcode == FcastProtocol.PLAY) {
                body?.optJSONObject("metadata")?.optJSONObject("custom")?.optString("playbackId").orEmpty()
            } else playbackId()
            fail(error.message ?: "Invalid receiver message", loadId)
        }
    }

    private fun number(body: JSONObject?, key: String, min: Double, max: Double): Double =
        requireNotNull(body).getDouble(key).also { require(it.isFinite() && it in min..max) { "Invalid $key" } }

    private fun validatePlay(body: JSONObject) {
        val peer = requireNotNull(connection).socket.inetAddress
        if (ReceiverLicenseRequest.direct(body, pitwallPeer) == null) {
            val origin = ReceiverMediaPolicy.validate(body.getString("url"), peer)
            require(origin.scheme == "https" && connection?.secureSession != null) { "Pitwall media requires paired TLS" }
            ReceiverMediaPolicy.validateResource(body.getString("url"), peer, origin, pitwallPeer)
        }
        require((body.optJSONObject("headers")?.length() ?: 0) == 0) { "Authorization headers must stay on the phone" }
        require(body.optString("content").isBlank()) { "Inline manifests are not supported" }
        require(body.getString("container").lowercase() in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml", "video/mp4")) { "Unsupported media type" }
        val requestedScene = body.optJSONObject("metadata")?.optJSONObject("custom")?.optJSONObject("pitwallScene")
        if (pitwallPeer) require(requestedScene != null) { "Phone layout is required; raw-atlas fallback is disabled" }
        requestedScene?.let {
            require(pitwallPeer) { "Pitwall layout support was not negotiated" }
            CastSceneProtocol.decode(it)
        }
        val custom = body.optJSONObject("metadata")?.optJSONObject("custom")
        if (custom?.has("pitwallQualityHeight") == true) {
            require(custom.optBoolean("pitwallDirect")) { "Quality metadata is for direct playback only" }
            DirectQualityProtocol.parseHeight(custom.get("pitwallQualityHeight"))
        }
        if (body.has("time")) number(body, "time", 0.0, 604800.0)
        if (body.has("speed")) number(body, "speed", 0.25, 4.0)
        if (body.has("volume")) number(body, "volume", 0.0, 1.0)
    }

    private fun play(body: JSONObject) {
        validatePlay(body)
        stopPlayback()
        val epoch = loadEpoch
        playData = body; loadingRequested = true
        directQuality = body.optJSONObject("metadata")?.optJSONObject("custom")?.let {
            if (it.has("pitwallQualityHeight")) DirectQualityProtocol.parseHeight(it.get("pitwallQualityHeight")) else null
        } ?: VideoQuality.AUTO
        desiredPlaying = true; desiredSpeed = body.optDouble("speed", 1.0).toFloat(); desiredVolume = body.optDouble("volume", 1.0).toFloat()
        val license = body.optJSONObject("metadata")?.optJSONObject("custom")?.optJSONObject("pitwallLicense")
        if (license == null) { startPlayer(body); return }
        require(pitwallPeer) { "Protected playback needs Pitwall negotiation" }
        val request = ReceiverLicenseRequest.decode(license)
        status = "Authorizing protected feeds with this TV's F1 subscription…"
        loadingJob = lifecycleScope.launch {
            var owned: ReceiverWidevine? = null
            try {
                owned = ReceiverWidevine.authorize(this@PitwallReceiverActivity, request)
                if (epoch != loadEpoch || !approved || destroyed) return@launch
                widevine = owned; secureOutput = owned.secureOutput; owned = null
                if (secureOutput) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                startPlayer(body)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { if (epoch == loadEpoch) fail(error.message ?: "TV Widevine authorization failed") }
            finally { owned?.close(); if (epoch == loadEpoch) loadingJob = null }
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun selectNativeAudio(current: ExoPlayer, tracks: androidx.media3.common.Tracks) {
        val requested = pendingNativeAudio ?: return
        if (tracks.groups.isEmpty()) return
        val id = NativeAudioControl.representationId(requested)
        val choice = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) && group.getTrackFormat(it).id == id }
                .map { group to it }
        }.singleOrNull()
        if (choice != null) {
            val (group, index) = choice
            if (!group.isTrackSelected(index) && submittedNativeAudio != requested) {
                submittedNativeAudio = requested
                current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
                    .build()
                return // Acknowledge only an observed Media3 selection.
            }
        }
        val selected = choice?.let { (group, index) -> group.isTrackSelected(index) } == true
        if (selected) confirmedNativeAudio = requested
        pendingNativeAudio = null; submittedNativeAudio = null
        connection?.send(FcastProtocol.UI_CONTROL, NativeAudioControl.acknowledgment(playbackId(), requested, selected))
        Log.i("PitwallReceiver", "NATIVE_AUDIO_TRACK_SELECTION index=$requested selected=$selected")
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applyDirectQuality(current: ExoPlayer, tracks: androidx.media3.common.Tracks) {
        if (!isDirectPlayback() || player !== current) return
        val selector = receiverTrackSelector ?: return
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val offers = groups.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.mapNotNull { index ->
                group.getTrackFormat(index).let { format ->
                    (format.width to format.height).takeIf { format.width > 0 && format.height > 0 }
                }
            }
        }.distinct()
        directQualityOffers = DirectQualityProtocol.encodeOffers(offers)
        val wanted = DirectQualityProtocol.manualHeight(directQuality, offers)
        val choice = if (wanted == null) null else groups.mapNotNull { group ->
            val indices = (0 until group.length).filter { index ->
                val format = group.getTrackFormat(index)
                group.isTrackSupported(index) && format.height == wanted &&
                    format.width in 1..directQuality.maxWidth
            }
            if (indices.isEmpty()) null else group to indices
        }.maxByOrNull { (group, indices) ->
            indices.maxOf { group.getTrackFormat(it).bitrate }
        }
        // The generic Player builder does not expose DefaultTrackSelector's
        // strict constraint policy. Use the selector owned by this exact load.
        val params = selector.buildUponParameters()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setMaxVideoSize(if (directQuality == VideoQuality.AUTO) 1920 else directQuality.maxWidth,
                if (directQuality == VideoQuality.AUTO) 1080 else directQuality.maxHeight)
            .setExceedVideoConstraintsIfNecessary(false)
            .apply {
                choice?.let { (group, indices) ->
                    setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, indices))
                }
            }.build()
        if (params != selector.parameters) selector.parameters = params
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startPlayer(body: JSONObject) {
        val custom = body.optJSONObject("metadata")?.optJSONObject("custom")
        val direct = custom?.optBoolean("pitwallDirect") == true
        submittedNativeAudio = null
        pendingNativeAudio = custom?.optInt("pitwallAudio", -1)?.takeIf { it in 0..5 }
        nativeProgrammeAudio = pendingNativeAudio != null
        confirmedNativeAudio = null
        receiverCaptionReady = false
        val data: DataSource.Factory = if (direct) requireNotNull(widevine).mediaHttp else {
        val peer = requireNotNull(connection).socket.inetAddress
        val origin = ReceiverMediaPolicy.validate(body.getString("url"), peer)
        val tls = requireNotNull(connection?.secureSession)
        val pinnedHttp = receiverHttp.newBuilder()
            .sslSocketFactory(tls.socketFactory, tls.trustManager)
            .hostnameVerifier { host, _ -> host.equals(peer.hostAddress?.substringBefore('%'), true) }
            .build()
        val upstream = OkHttpDataSource.Factory(pinnedHttp)
        val scopedPitwallPeer = pitwallPeer
        val data = DataSource.Factory {
            val source = upstream.createDataSource()
            object : DataSource by source {
                override fun open(spec: DataSpec): Long {
                    ReceiverMediaPolicy.validateResource(spec.uri.toString(), peer, origin, scopedPitwallPeer)
                    return source.open(spec)
                }
            }
        }
            data
        }
        val nextScene = body.optJSONObject("metadata")?.optJSONObject("custom")?.optJSONObject("pitwallScene")?.let(CastSceneProtocol::decode)
        val correlation = SceneCodecTimestampCorrelation()
        val cadence = AdaptivePlaybackCadenceObserver(this, correlation).apply { composedSingle = nextScene != null }
        val renderers = ThinningRenderersFactory(this, cadence).setEnableDecoderFallback(false)
            .setMediaCodecSelector(ReceiverCodecSelector())
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)
        if (direct) selector.parameters = selector.buildUponParameters()
            .setMaxVideoSize(if (directQuality == VideoQuality.AUTO) 1920 else directQuality.maxWidth,
                if (directQuality == VideoQuality.AUTO) 1080 else directQuality.maxHeight)
            .setExceedVideoConstraintsIfNecessary(false)
            .apply {
                if (isTclG09()) {
                    setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)
                    setForceHighestSupportedBitrate(true)
                }
            }.build()
        val receiverAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val next = ExoPlayer.Builder(this, renderers).setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data).apply { widevine?.sessions?.let { key -> setDrmSessionManagerProvider { key } } })
            .setLoadControl(DefaultLoadControl.Builder().setAllocator(receiverAllocator).setBufferDurationsMs(5_000, 15_000, 1_000, 2_000).build())
            .build()
        activeReceiverAllocator = receiverAllocator
        app.apexview.Media3MemoryOwners.register(receiverAllocator, "receiver")
        receiverCadence = cadence
        receiverDisplayOwner = null
        sceneCodecCorrelation = correlation
        next.setVideoFrameMetadataListener(object : VideoFrameMetadataListener {
            override fun onVideoFrameAboutToBeRendered(
                presentationTimeUs: Long, releaseTimeNs: Long, format: Format,
                mediaFormat: MediaFormat?,
            ) {
                correlation.onCodecFrame(presentationTimeUs, releaseTimeNs, System.nanoTime())
            }
        })
        receiverTrackSelector = selector
        scene = nextScene; player = next; active = true; generation++; renderingReported = false; decoderCount = 0
        var receiverAudioDecoders = 0
        val receiverLoads = app.apexview.Media3LoadLedger()
        fun updateReceiverMemoryOwner() {
            if (player !== next) return
            app.apexview.Media3MemoryOwners.update(receiverAllocator, "RECEIVER", decoderCount,
                receiverAudioDecoders, next.mediaItemCount,
                if (next.currentTracks.groups.any { it.isSelected })
                    next.totalBufferedDuration.coerceAtLeast(0) else -1L,
                -1L, receiverLoads.snapshot())
        }
        receiverMemorySnapshot = ::updateReceiverMemoryOwner
        next.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadStarted(eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData) {
                if (player === next && mediaLoadData.dataType == C.DATA_TYPE_MEDIA)
                    receiverLoads.start(loadEventInfo.loadTaskId, mediaLoadData.trackType)
            }
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData) {
                if (player === next) receiverLoads.finish(loadEventInfo.loadTaskId, loadEventInfo.bytesLoaded)
            }
            override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData) {
                if (player === next) receiverLoads.finish(loadEventInfo.loadTaskId, loadEventInfo.bytesLoaded)
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException, wasCanceled: Boolean) {
                if (player === next && wasCanceled)
                    receiverLoads.finish(loadEventInfo.loadTaskId, loadEventInfo.bytesLoaded)
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (player !== next) return
                receiverAudioDecoders++
                updateReceiverMemoryOwner()
            }
            override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                if (player !== next) return
                receiverAudioDecoders = (receiverAudioDecoders - 1).coerceAtLeast(0)
                updateReceiverMemoryOwner()
            }
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime, format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                if (player !== next) return
                if (direct) directActualInput = (format.width to format.height).takeIf {
                    format.width > 0 && format.height > 0
                }
                @Suppress("DEPRECATION")
                val displayHz = windowManager.defaultDisplay.refreshRate
                Log.i("PitwallReceiver", "VIDEO_FORMAT size=${format.width}x${format.height} " +
                    "fps=${format.frameRate} displayHz=$displayHz mime=${format.sampleMimeType}")
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (player !== next) return
                correlation.reset()
                decoderCount++
                updateReceiverMemoryOwner()
                Log.i("PitwallReceiver", "VIDEO_DECODER_INIT name=$decoderName active=$decoderCount sources=${scene?.sources ?: 1}")
                if (decoderCount > 1) fail("Receiver allocated more than one video decoder")
            }
            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                if (player !== next) return
                correlation.reset()
                decoderCount = (decoderCount - 1).coerceAtLeast(0)
                updateReceiverMemoryOwner()
                Log.i("PitwallReceiver", "VIDEO_DECODER_RELEASE name=$decoderName active=$decoderCount")
            }
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                if (player === next) Log.w("PitwallReceiver", "VIDEO_DROPPED frames=$droppedFrames elapsedMs=$elapsedMs positionMs=${controllerPosition()}")
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                // For a scene, wait for the GLES output swap, not merely codec output to its texture.
                if (player === next && (scene == null || surfacePlayback(scene, direct))) reportRendering()
            }
        })
        next.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (player !== next) return
                if (direct) applyDirectQuality(next, tracks)
                selectNativeAudio(next, tracks)
                updateReceiverCaptions(next, tracks)
                updateReceiverMemoryOwner()
                Log.i("PitwallReceiver", "CC_TRACKS count=$receiverCaptionTrackCount")
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (player !== next || reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return
                val target = controllerPosition()
                timingState.onSeek(target + oldPosition.positionMs - newPosition.positionMs, target)
                correlation.reset()
                sendPlayback()
            }

            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
                if (player === next) desiredSpeed = parameters.speed
            }

            override fun onPlayerError(error: PlaybackException) { if (player === next) fail("TV playback failed: ${error.errorCodeName}") }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (player !== next || size.width <= 0 || size.height <= 0) return
                val model = scene ?: return
                if (direct) {
                    scene = model.copy(atlasWidth = size.width, atlasHeight = size.height, atlasColumns = 1,
                        sourceCodedWidth = size.width, sourceCodedHeight = size.height,
                        sourceDisplayWidth = size.width, sourceDisplayHeight = size.height)
                } else if (size.width != model.atlasWidth || size.height != model.atlasHeight) fail("Video dimensions do not match the phone layout")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== next) return
                updateReceiverMemoryOwner()
                Log.i("PitwallReceiver", "GC_RECEIVER_ALLOCATOR generation=$generation state=$state " +
                    "media3InUseBytes=${receiverAllocator.totalBytesAllocated}")
                sendPlayback()
                if (state == Player.STATE_ENDED) stopPlayback()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (player === next) sendPlayback() }
        })
        next.trackSelectionParameters = next.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setPreferredTextLanguage(CaptionPolicy.textLanguage(captionLanguage, "en"))
            .setSelectUndeterminedTextLanguage(captionsOn)
            .build()
        val originMs = body.optJSONObject("metadata")?.optJSONObject("custom")?.optJSONObject("pitwallStream")?.optLong("originMs") ?: 0L
        val ownSource = if (direct) requireNotNull(widevine).source else null
        val mediaUri = ownSource?.url ?: body.getString("url")
        val mime = if (ownSource == null) body.getString("container") else if (ManifestProtocol.fromUrl(mediaUri) == ManifestProtocol.HLS || ownSource.streamType.contains("HLS", true)) "application/x-mpegURL" else "application/dash+xml"
        val item = MediaItem.Builder().setUri(mediaUri).setMimeType(mime).build()
        val start = app.apexview.cast.CastRecoveryPolicy.receiverStartPosition(
            body.optJSONObject("metadata")?.optJSONObject("custom")?.optBoolean("pitwallBaked") == true,
            (body.optDouble("time", 0.0) * 1000).toLong(), originMs)
        if (start == null) next.setMediaItem(item) else next.setMediaItem(item, start)
        next.setPlaybackSpeed(desiredSpeed)
        next.volume = desiredVolume
        next.playWhenReady = desiredPlaying
        loadingRequested = false
        next.prepare()
        playData = body
        status = "Loading ${nextScene?.sources ?: 1} feed${if (nextScene?.sources == 1) "" else "s"}…"
        connection?.send(FcastProtocol.PLAY_UPDATE, JSONObject().put("generationTime", System.currentTimeMillis()).put("playData", body))
        sendPlayback()
    }

    private fun reportRendering() {
        if (!active || renderingReported) return
        renderingReported = true
        showReceiverControls()
        status = if (scene == null) "Playing on this TV" else "Playing ${scene!!.sources} feeds in the phone layout"
        val model = scene
        Log.i("PitwallReceiver", "SCENE_RENDERING_CONFIRMED sources=${model?.sources ?: 1} " +
            "windows=${model?.windows?.size ?: 1} atlas=${model?.atlasWidth ?: 0}x${model?.atlasHeight ?: 0} " +
            "renderer=${if (surfacePlayback(model, isDirectPlayback())) "DIRECT_SURFACE" else if (secureOutput) "PROTECTED_GLES" else "CLEAR_GLES"} " +
            "videoDecoders=$decoderCount")
        if (pitwallPeer) connection?.send(FcastProtocol.RENDERING, JSONObject().put("sources", scene?.sources ?: 1)
            .put("playbackId", playbackId()))
    }
    private fun showReceiverControls() {
        controlsVisible = true
        controlsJob?.cancel()
        controlsJob = lifecycleScope.launch { delay(4_000); if (!receiverControlsFocused && !captionSettingsMenu) controlsVisible = false }
    }
    // A whole-picture single feed needs no GPU atlas extraction. The native video surface
    // handles buffer presentation while Compose draws the overlays above it.
    private fun surfacePlayback(model: app.apexview.scene.VideoScene?, direct: Boolean): Boolean =
        direct || (model != null && model.sources == 1 && model.windows.size == 1 &&
            model.atlasWidth == model.sourceDisplayWidth && model.atlasHeight == model.sourceDisplayHeight)

    private fun playbackId() = playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optString("playbackId").orEmpty()
    private fun isDirectPlayback() = playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optBoolean("pitwallDirect") == true
    private fun streamMetadata() = playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optJSONObject("pitwallStream")
    private fun windowOffsetMs(current: ExoPlayer): Long =
        if (current.currentTimeline.isEmpty) 0L else current.currentTimeline
            .getWindow(current.currentMediaItemIndex, Timeline.Window()).positionInFirstPeriodMs

    private fun controllerPosition(): Long {
        val current = player ?: return 0
        val origin = streamMetadata()?.getLong("originMs") ?: return current.currentPosition
        return ReceiverTimeline.position(origin, windowOffsetMs(current), current.currentPosition)
    }
    private fun controllerSeek(targetMs: Long) {
        if (streamMetadata() != null) connection?.send(FcastProtocol.UI_CONTROL,
            JSONObject().put("action", "seek").put("time", targetMs / 1000.0).put("playbackId", playbackId()))
        else player?.let { it.seekTo(ReceiverTimeline.seek(targetMs, it.duration)) }
    }
    private fun sendPlayback() {
        if (!approved) return
        receiverMemorySnapshot?.invoke()
        val current = player
        connection?.send(FcastProtocol.PLAYBACK_UPDATE, JSONObject().put("generationTime", System.currentTimeMillis())
            .put("playbackId", playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optString("playbackId").orEmpty())
            .put("clockEpoch", timingState.clockEpoch)
            .put("state", if (loadingRequested) if (desiredPlaying) 1 else 2 else if (!active || current?.playbackState == Player.STATE_ENDED) 0 else if (current?.playWhenReady == true) 1 else 2)
            .put("time", if (loadingRequested) playData?.optDouble("time", 0.0) ?: 0.0 else controllerPosition() / 1000.0)
            .put("duration", (streamMetadata()?.optLong("durationMs") ?: current?.duration ?: 0L).coerceAtLeast(0L) / 1000.0)
            .put("speed", current?.playbackParameters?.speed ?: 1f)
            .put("buffering", loadingRequested || current?.playbackState == Player.STATE_BUFFERING)
            .put("finished", current?.playbackState == Player.STATE_ENDED)
            .put("captionTracks", CaptionTrackProtocol.encode(receiverCaptionTracks)).apply {
                streamMetadata()?.takeIf { it.has("wallClockBaseMs") }?.let {
                    put("wallClockMs", Math.addExact(it.getLong("wallClockBaseMs"), controllerPosition()))
                }
                if (isDirectPlayback()) {
                    put("directQualityOffers", directQualityOffers)
                    put("directQualityRequested", directQuality.targetHeight)
                    directActualInput?.let { (w, h) -> put("directQualityActual", org.json.JSONArray().put(w).put(h)) }
                }
                if (isDirectPlayback() && current != null && !current.currentTimeline.isEmpty) {
                    val start = current.currentTimeline.getWindow(current.currentMediaItemIndex, Timeline.Window()).windowStartTimeMs
                    if (start != C.TIME_UNSET) put("wallClockMs", start + current.currentPosition)
                }
            })
        connection?.send(FcastProtocol.VOLUME_UPDATE, JSONObject().put("generationTime", System.currentTimeMillis()).put("volume", current?.volume ?: 1f))
    }
    private fun stopPlayback() {
        ++loadEpoch
        loadingRequested = false
        loadingJob?.cancel(); loadingJob = null
        controlsJob?.cancel(); controlsJob = null
        receiverControlsFocused = false; captionSettingsMenu = false
        active = false
        timingState.clear(); timingReported = false; lastTimingHealthAtMs = -1L
        receiverCaptionTracks = emptyList(); receiverCaptionTrackCount = 0
        directQuality = VideoQuality.AUTO; directQualityOffers = org.json.JSONArray(); directActualInput = null
        pendingNativeAudio = null; submittedNativeAudio = null
        val clearScene = sceneView.also { sceneView = null }
        val protectedScene = protectedSceneView.also { protectedSceneView = null }
        val correlation = sceneCodecCorrelation.also { sceneCodecCorrelation = null }
        val standard = standardView.also { standardView = null }
        val previous = player.also { player = null }
        receiverMemorySnapshot = null
        receiverTrackSelector = null
        val previousAuthorization = widevine.also { widevine = null }
        val previousAllocator = activeReceiverAllocator.also { activeReceiverAllocator = null }
        fun retire(owner: String, release: () -> Unit) {
            try { release() }
            catch (error: Exception) {
                Log.e("PitwallReceiver", "RECEIVER_RELEASE_FAILED owner=$owner type=${error.javaClass.simpleName}")
            }
        }
        // Scene release detaches the codec producer on this application looper.
        // A failed scene retirement must not retain the player or its DRM owner.
        retire("clear-scene") { clearScene?.release() }
        retire("protected-scene") { protectedScene?.release() }
        retire("correlation") { correlation?.shutdown() }
        receiverCadence?.activeDisplayId = -1
        receiverCadence = null
        receiverDisplayOwner = null
        retire("standard-view") { standard?.player = null }
        retire("player") { previous?.release() }
        retire("allocator-registration") { previousAllocator?.let { app.apexview.Media3MemoryOwners.unregister(it) } }
        retire("allocator-trim") { previousAllocator?.trim() }
        retire("DRM") { previousAuthorization?.close() }
        secureOutput = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        scene = null; playData = null; renderingReported = false
        status = "Ready. Choose this TV from Cast on your phone."
    }
    private fun fail(reason: String, loadId: String = playbackId()) {
        val safe = DiagnosticRedaction.redact(reason).take(200)
        connection?.send(FcastProtocol.ERROR, JSONObject().put("message", safe).apply {
            if (loadId.isNotEmpty()) put("playbackId", loadId)
        })
        stopPlayback(); pending = null; status = safe
        Log.w("PitwallReceiver", safe)
    }

    @Composable private fun ReceiverScreen() {
        val model = scene
        val current = player
        Box(Modifier.fillMaxSize().background(Ink.background), contentAlignment = Alignment.Center) {
            if (active && current != null) {
                val viewGeneration = generation
                key(viewGeneration) {
                    if (model != null) {
                        // Fit the entire phone canvas on the TV, preserving its window proportions.
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val ratio = model.width / model.height
                            val modifier = if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio)
                                else Modifier.fillMaxWidth().aspectRatio(ratio)
                            Box(modifier) {
                            if (surfacePlayback(model, isDirectPlayback())) BoxWithConstraints(Modifier.fillMaxSize()) {
                                val scale = constraints.maxWidth / model.width
                                val density = androidx.compose.ui.platform.LocalDensity.current
                                model.windows.singleOrNull()?.let { cell ->
                                    AndroidView(factory = { context ->
                                        (android.view.LayoutInflater.from(context).inflate(R.layout.player_surface, null) as PlayerView).apply {
                                            standardView = this; player = current; useController = false
                                            setEnableComposeSurfaceSyncWorkaround(true)
                                            (videoSurfaceView as? android.view.SurfaceView)?.setSecure(secureOutput)
                                        }
                                    }, update = {
                                        it.player = current
                                        it.subtitleView?.apply {
                                            applyCaptionAppearance(captionAppearance)
                                            visibility = if (receiverCaptionReady) android.view.View.VISIBLE else android.view.View.INVISIBLE
                                        }
                                    }, onRelease = {
                                        if (standardView === it) standardView = null; it.player = null
                                    }, modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset((cell.x * scale).toInt(), (cell.y * scale).toInt()) }
                                        .requiredSize(with(density) { (cell.width * scale).toDp() }, with(density) { (cell.height * scale).toDp() }))
                                }
                            }
                            else if (secureOutput) AndroidView(factory = { ProtectedDecoderSceneView(it).apply {
                                protectedSceneView = this
                                val displayOwner = this
                                onDisplaySelected = { displayId ->
                                    if (this@PitwallReceiverActivity.player === current && generation == viewGeneration)
                                        setReceiverOutputDisplay(displayOwner, displayId)
                                }
                                onFailure = { reason -> if (this@PitwallReceiverActivity.player === current && generation == viewGeneration) fail(reason) }
                                onFirstFrame = { if (this@PitwallReceiverActivity.player === current && generation == viewGeneration) reportRendering() }
                                bind(current, model, desiredSpeed, sceneCodecCorrelation)
                            } }, update = { it.bind(current, model, desiredSpeed, sceneCodecCorrelation) }, onRelease = {
                                if (protectedSceneView === it) protectedSceneView = null
                                it.release()
                            }, modifier = Modifier.fillMaxSize())
                            else AndroidView(factory = { SingleDecoderSceneView(it).apply {
                                sceneView = this
                                val displayOwner = this
                                onDisplaySelected = { displayId ->
                                    if (this@PitwallReceiverActivity.player === current && generation == viewGeneration)
                                        setReceiverOutputDisplay(displayOwner, displayId)
                                }
                                onFailure = { reason -> if (this@PitwallReceiverActivity.player === current && generation == viewGeneration) fail(reason) }
                                onFirstFrame = { if (this@PitwallReceiverActivity.player === current && generation == viewGeneration) reportRendering() }
                                bind(current, model, desiredSpeed, sceneCodecCorrelation)
                            } }, update = { it.bind(current, model, desiredSpeed, sceneCodecCorrelation) }, onRelease = {
                                if (sceneView === it) sceneView = null
                                it.release()
                            }, modifier = Modifier.fillMaxSize())
                            if (!surfacePlayback(model, isDirectPlayback())) {
                                ComposedCaptionOverlay(current, captionsOn && receiverCaptionReady, captionAppearance, Modifier.fillMaxSize())
                            }
                            if (playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optBoolean("pitwallBaked") != true)
                            CastTimingLayer(timingState, model.width, model.height, remember { { controllerPosition() } }, { number, pit ->
                                connection?.send(FcastProtocol.UI_CONTROL, JSONObject().put("action", if (pit) "pitDriver" else "driver").put("number", number))
                            }, { rows, cars ->
                                if (!timingReported && (rows > 0 || cars > 0)) {
                                    timingReported = true
                                    window.decorView.post {
                                        if (active && timingPeer) connection?.send(FcastProtocol.TIMING, JSONObject().put("kind", "drawn")
                                            .put("playbackId", playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optString("playbackId"))
                                            .put("rows", rows).put("cars", cars))
                                        Log.i("PitwallReceiver", "TIMING_UI_DRAWN rows=$rows cars=$cars")
                                    }
                                }
                            }, nativeRequested = captionSettings.nativeRustStitching)
                            }
                        }
                    } else AndroidView(factory = { PlayerView(it).apply { standardView = this; player = current; useController = true } },
                        update = {
                            it.subtitleView?.apply {
                                applyCaptionAppearance(captionAppearance)
                                visibility = if (receiverCaptionReady) android.view.View.VISIBLE else android.view.View.INVISIBLE
                            }
                        },
                        onRelease = { if (standardView === it) standardView = null; it.player = null }, modifier = Modifier.fillMaxSize())
                }
            } else Column(
                Modifier.fillMaxWidth().widthIn(max = 680.dp)
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Ink.surface)
                    .padding(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("Pitwall TV", style = Type.largeTitle)
                Text(receiverName, style = Type.title2)
                Text(status, style = Type.body, color = Ink.secondary)
                Text("On your phone, choose Cast and select this TV. Keep the receiver open. Protected and live F1 feeds use the authorized F1 TV account signed in here.",
                    style = Type.subhead)
                if (connection == null) televisionSelectionCode?.let { code ->
                    Text("First connection: enter this TV identity code in the phone's Cast picker.", style = Type.caption)
                    Text(code, style = Type.headline)
                }
                if (connection != null) SecondaryButton("Disconnect phone") { disconnectPhone() }
                SecondaryButton("Forget paired phones") {
                    PitwallRememberedPeers.forgetAll(this@PitwallReceiverActivity)
                    disconnectPhone()
                    status = "Paired phones forgotten. Confirm the code on the next connection."
                }
                SecondaryButton("Exit receiver") { finish() }
            }
            if (active && !renderingReported) Text(status, style = Type.subhead, modifier = Modifier.align(Alignment.BottomCenter).background(Ink.surface).padding(12.dp))
            if (active && renderingReported && controlsVisible) Row(
                Modifier.align(Alignment.BottomCenter).padding(8.dp)
                    .background(Ink.surface.copy(alpha = .94f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .onFocusChanged { receiverControlsFocused = it.hasFocus
                        if (controlsVisible) showReceiverControls() }
                    .focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("OK: play/pause   ←/→: seek   Back: disconnect", style = Type.caption)
                if (captionsOn && receiverCaptionTrackCount == 0 && !isBakedPlayback()) {
                    Text("No F1 caption track currently offered", style = Type.caption)
                }
                SecondaryButton(if (captionsOn) "CC on" else "CC off") {
                    requestReceiverCaptions(!captionsOn, captionLanguage)
                }
                if (!isBakedPlayback() || captionOwnerControl) {
                    SecondaryButton("Caption settings") { captionSettingsMenu = true }
                }
            }
        }
        if (captionSettingsMenu) AlertDialog(
            onDismissRequest = { closeCaptionSettings() },
            title = { Text("Caption settings") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    CaptionAppearanceControls(captionAppearance, captionSettings::previewCaptionAppearance,
                        ::saveReceiverCaptionAppearance)
                    if (!isBakedPlayback() && receiverCaptionTracks.isNotEmpty()) {
                        Text("Caption language", style = Type.subhead)
                        TextButton(onClick = { requestReceiverCaptions(true, CaptionPolicy.AUTO); closeCaptionSettings(save = false) }) { Text("Automatic") }
                        receiverCaptionTracks.forEach { track ->
                            TextButton(onClick = { requestReceiverCaptions(true, track.language); closeCaptionSettings(save = false) }) { Text(track.label) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { closeCaptionSettings() }) { Text("Close") } }
        )
        pairingCode?.let { code -> app.apexview.cast.PitwallPairingDialog(code, ::confirmPairing, receiver = true) }
        // A successful physical pairing authorizes playback on this connection.
        // Protected media still obtains the TV's own F1 entitlement.
    }
    private fun closeCaptionSettings(save: Boolean = true) {
        if (save) saveReceiverCaptionAppearance()
        captionSettingsMenu = false
        showReceiverControls()
    }

    private fun deny() { pending = null; connection?.close() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val current = player
        if (active && pending == null && !captionSettingsMenu && current != null) {
            showReceiverControls()
            if (receiverControlsFocused && keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_ENTER))
                return super.onKeyDown(keyCode, event)
            when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (event.repeatCount == 0) { if (current.playWhenReady) current.pause() else current.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { current.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { current.pause(); return true }
            KeyEvent.KEYCODE_CAPTIONS -> {
                if (event.repeatCount == 0) requestReceiverCaptions(!captionsOn, captionLanguage)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { controllerSeek((controllerPosition() - 10_000).coerceAtLeast(0)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { controllerSeek(controllerPosition() + 10_000); return true }
        }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onStop() {
        advertisement?.close(); advertisement = null
        televisionSelectionCode = null
        runCatching { server?.close() }; server = null
        listeningJob?.cancel(); listeningJob = null
        val previous = connection; connection = null; previous?.close()
        pending = null; approved = false; versionReceived = false
        pendingManualInitial = null
        pairingCode = null; pairingTimeout?.cancel(); pairingTimeout = null
        stopPlayback()
        super.onStop()
    }
    override fun onDestroy() {
        frameDiagnostics?.close()
        frameDiagnostics = null
        destroyed = true; advertisement?.close(); runCatching { server?.close() }
        connection?.close(); connection = null; stopPlayback()
        if (httpDelegate.isInitialized()) {
            receiverHttp.dispatcher.cancelAll()
            receiverHttp.connectionPool.evictAll()
            receiverHttp.dispatcher.executorService.shutdown()
        }
        super.onDestroy()
    }
}
