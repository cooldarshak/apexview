package app.apexview.cast.local

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.apexview.*
import app.apexview.R
import app.apexview.cast.CastSceneProtocol
import app.apexview.cast.CastTimingReceiver
import app.apexview.cast.CastTimingLayer
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
    private var sceneView: SingleDecoderSceneView? = null
    private var protectedSceneView: ProtectedDecoderSceneView? = null
    private var widevine: ReceiverWidevine? = null
    private var secureOutput by mutableStateOf(false)
    private var loadingJob: kotlinx.coroutines.Job? = null
    private var loadEpoch = 0L
    private var loadingRequested = false
    private var desiredPlaying = true
    private var desiredSpeed = 1f
    private var desiredVolume = 1f
    private var standardView: PlayerView? = null
    private val httpDelegate = lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build() }
    private val receiverHttp by httpDelegate
    private var server: ServerSocket? = null
    private var advertisement: PitwallAdvertisement? = null
    private var connection: FcastConnection? = null
    private val timingState = CastTimingReceiver()
    private var timingPeer = false
    private var timingReported = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogs.startIfEnabled(applicationContext)
        CrashReports.install(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AppTheme { ReceiverScreen() } }
        lifecycleScope.launch {
            while (true) {
                delay(1_000)
                if (versionReceived) connection?.send(FcastProtocol.PING)
                if (approved) sendPlayback()
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
                val listening = try { ServerSocket(FcastProtocol.PORT, 2) } catch (_: java.io.IOException) { ServerSocket(0, 2) }
                ownedSocket = listening
                withContext(Dispatchers.Main) {
                    if (destroyed) { listening.close(); return@withContext }
                    server = listening
                    advertisement = PitwallAdvertisement(this@PitwallReceiverActivity, listening.localPort, receiverName) { name, ok ->
                        if (ok) { receiverName = name; if (!active) status = "Ready. Choose this TV from Cast on your phone." }
                        else status = name
                    }
                }
                while (!listening.isClosed) {
                    val socket = listening.accept()
                    var delivered = false
                    try { withContext(Dispatchers.Main) { accept(socket); delivered = true } }
                    finally { if (!delivered) runCatching { socket.close() } }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { if (!destroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) status = "Receiver stopped: ${error.javaClass.simpleName}. Reopen to retry." }
            } finally { runCatching { ownedSocket?.close() } }
        }
    }

    private fun accept(socket: Socket) {
        if (destroyed || connection != null || !(socket.inetAddress.isSiteLocalAddress || socket.inetAddress.isLinkLocalAddress)) {
            socket.close(); return
        }
        approved = false; versionReceived = false; pitwallPeer = false; senderName = "Phone"
        lateinit var next: FcastConnection
        next = FcastConnection(lifecycleScope, socket, { packet ->
            if (connection === next) handle(packet)
        }, { reason ->
            if (connection === next) {
                connection = null; pending = null; approved = false; versionReceived = false
                stopPlayback()
                status = "Phone disconnected. Ready for another connection."
                Log.i("PitwallReceiver", "CONNECTION_CLOSED reason=$reason")
            }
        })
        connection = next; next.start()
        next.send(FcastProtocol.VERSION, JSONObject().put("version", 3))
        status = "Phone connected. Waiting for playback request…"
    }

    private fun handle(packet: FcastProtocol.Packet) {
        val body = packet.body
        try {
            when (packet.opcode) {
                FcastProtocol.VERSION -> {
                    require(!versionReceived) { "Duplicate FCast handshake" }
                    require(body != null && body.getInt("version") >= 2) { "FCast version 2 or 3 is required" }
                    versionReceived = true
                    connection?.send(FcastProtocol.INITIAL, JSONObject().put("displayName", receiverName)
                        .put("appName", "Pitwall TV").put("appVersion", BuildConfig.VERSION_NAME).put("pitwallScene", 1).put("pitwallOverlays", 1).put("pitwallWidevine", 1).put("pitwallDirect", 1))
                }
                FcastProtocol.PING -> connection?.send(FcastProtocol.PONG)
                FcastProtocol.PONG -> Unit
                FcastProtocol.INITIAL -> {
                    require(versionReceived)
                    senderName = body?.optString("displayName")?.take(80)?.ifBlank { "Phone" } ?: "Phone"
                    pitwallPeer = body?.optInt("pitwallScene") == 1
                    timingPeer = body?.optInt("pitwallOverlays") == 1
                }
                FcastProtocol.PLAY -> {
                    require(versionReceived && body != null)
                    validatePlay(body)
                    if (approved) play(body) else { pending = body; status = "Allow $senderName to play on this TV?" }
                }
                else -> {
                    if (!approved) return
                    when (packet.opcode) {
                        FcastProtocol.PAUSE -> { desiredPlaying = false; player?.pause() }
                        FcastProtocol.RESUME -> { desiredPlaying = true; player?.play() }
                        FcastProtocol.STOP -> stopPlayback()
                        FcastProtocol.SEEK -> player?.seekTo((number(body, "time", 0.0, 604800.0) * 1000).toLong())
                        FcastProtocol.SET_SPEED -> { desiredSpeed = number(body, "speed", 0.25, 4.0).toFloat(); player?.setPlaybackSpeed(desiredSpeed) }
                        FcastProtocol.SET_VOLUME -> { desiredVolume = number(body, "volume", 0.0, 1.0).toFloat(); player?.volume = desiredVolume }
                        FcastProtocol.TIMING -> if (timingPeer && body != null) {
                            try { timingState.accept(body, playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optString("playbackId").orEmpty()) }
                            catch (error: Exception) { Log.w("PitwallReceiver", "Timing display message rejected: ${error.javaClass.simpleName}") }
                            return // The periodic playback update carries the clock independently of overlay updates.
                        }
                        FcastProtocol.SCENE -> {
                            require(pitwallPeer && scene != null && body != null)
                            val decoded = CastSceneProtocol.decode(body)
                            val old = scene!!
                            val next = if (isDirectPlayback()) decoded.copy(atlasWidth = old.atlasWidth, atlasHeight = old.atlasHeight,
                                atlasColumns = 1, sourceCodedWidth = old.sourceCodedWidth, sourceCodedHeight = old.sourceCodedHeight,
                                sourceDisplayWidth = old.sourceDisplayWidth, sourceDisplayHeight = old.sourceDisplayHeight) else decoded
                            require(next.copy(windows = old.windows, width = old.width, height = old.height) == old) { "Stream geometry changed; reload the stream" }
                            scene = next
                        }
                        else -> Unit // Unknown optional messages are not executed.
                    }
                    sendPlayback()
                }
            }
        } catch (error: Exception) { fail(error.message ?: "Invalid receiver message") }
    }

    private fun number(body: JSONObject?, key: String, min: Double, max: Double): Double =
        requireNotNull(body).getDouble(key).also { require(it.isFinite() && it in min..max) { "Invalid $key" } }

    private fun validatePlay(body: JSONObject) {
        val peer = requireNotNull(connection).socket.inetAddress
        if (ReceiverLicenseRequest.direct(body, pitwallPeer) == null) ReceiverMediaPolicy.validate(body.getString("url"), peer)
        require((body.optJSONObject("headers")?.length() ?: 0) == 0) { "Authorization headers must stay on the phone" }
        require(body.optString("content").isBlank()) { "Inline manifests are not supported" }
        require(body.getString("container").lowercase() in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml", "video/mp4")) { "Unsupported media type" }
        val requestedScene = body.optJSONObject("metadata")?.optJSONObject("custom")?.optJSONObject("pitwallScene")
        if (pitwallPeer) require(requestedScene != null) { "Phone layout is required; raw-atlas fallback is disabled" }
        requestedScene?.let {
            require(pitwallPeer) { "Pitwall layout support was not negotiated" }
            CastSceneProtocol.decode(it)
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
    private fun startPlayer(body: JSONObject) {
        val direct = body.optJSONObject("metadata")?.optJSONObject("custom")?.optBoolean("pitwallDirect") == true
        val data: DataSource.Factory = if (direct) requireNotNull(widevine).mediaHttp else {
        val peer = requireNotNull(connection).socket.inetAddress
        val origin = ReceiverMediaPolicy.validate(body.getString("url"), peer)
        val upstream = OkHttpDataSource.Factory(receiverHttp)
        val data = DataSource.Factory {
            val source = upstream.createDataSource()
            object : DataSource by source {
                override fun open(spec: DataSpec): Long {
                    val uri = ReceiverMediaPolicy.validate(spec.uri.toString(), peer)
                    require(uri.scheme == origin.scheme && uri.port == origin.port) { "Media resource left the phone relay" }
                    return source.open(spec)
                }
            }
        }
            data
        }
        val nextScene = body.optJSONObject("metadata")?.optJSONObject("custom")?.optJSONObject("pitwallScene")?.let(CastSceneProtocol::decode)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(false)
            .setMediaCodecSelector(ReceiverCodecSelector())
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)
        if (direct) selector.parameters = selector.buildUponParameters()
            .setMaxVideoSize(1920, 1080).setExceedVideoConstraintsIfNecessary(false).build()
        val next = ExoPlayer.Builder(this, renderers).setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data).apply { widevine?.sessions?.let { key -> setDrmSessionManagerProvider { key } } })
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 1_000, 2_000).build())
            .build()
        scene = nextScene; player = next; active = true; generation++; renderingReported = false; decoderCount = 0
        next.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (player !== next) return
                decoderCount++
                Log.i("PitwallReceiver", "VIDEO_DECODER_INIT name=$decoderName active=$decoderCount sources=${scene?.sources ?: 1}")
                if (decoderCount > 1) fail("Receiver allocated more than one video decoder")
            }
            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                if (player !== next) return
                decoderCount = (decoderCount - 1).coerceAtLeast(0)
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
            override fun onPlayerError(error: PlaybackException) { if (player === next) fail("TV playback failed: ${error.errorCodeName}") }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (player !== next) return
                val model = scene ?: return
                if (direct) {
                    scene = model.copy(atlasWidth = size.width, atlasHeight = size.height, atlasColumns = 1,
                        sourceCodedWidth = size.width, sourceCodedHeight = size.height,
                        sourceDisplayWidth = size.width, sourceDisplayHeight = size.height)
                } else if (size.width != model.atlasWidth || size.height != model.atlasHeight) fail("Video dimensions do not match the phone layout")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== next) return
                sendPlayback()
                if (state == Player.STATE_ENDED) stopPlayback()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (player === next) sendPlayback() }
        })
        next.trackSelectionParameters = next.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        val originMs = body.optJSONObject("metadata")?.optJSONObject("custom")?.optJSONObject("pitwallStream")?.optLong("originMs") ?: 0L
        val ownSource = if (direct) requireNotNull(widevine).source else null
        val mediaUri = ownSource?.url ?: body.getString("url")
        val mime = if (ownSource == null) body.getString("container") else if (ManifestProtocol.fromUrl(mediaUri) == ManifestProtocol.HLS || ownSource.streamType.contains("HLS", true)) "application/x-mpegURL" else "application/dash+xml"
        next.setMediaItem(MediaItem.Builder().setUri(mediaUri).setMimeType(mime).build(),
            ((body.optDouble("time", 0.0) * 1000).toLong() - originMs).coerceAtLeast(0))
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
        Log.i("PitwallReceiver", "SCENE_RENDERING_CONFIRMED sources=${scene?.sources ?: 1} videoDecoders=$decoderCount")
        if (pitwallPeer) connection?.send(FcastProtocol.RENDERING, JSONObject().put("sources", scene?.sources ?: 1)
            .put("playbackId", playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optString("playbackId").orEmpty()))
    }
    private fun showReceiverControls() {
        controlsVisible = true
        controlsJob?.cancel()
        controlsJob = lifecycleScope.launch { delay(4_000); controlsVisible = false }
    }
    // A whole-picture single feed needs no GPU atlas extraction. The native video surface
    // handles buffer presentation while Compose draws the overlays above it.
    private fun surfacePlayback(model: app.apexview.scene.VideoScene?, direct: Boolean): Boolean =
        direct || (model != null && model.sources == 1 && model.windows.size == 1 &&
            model.atlasWidth == model.sourceDisplayWidth && model.atlasHeight == model.sourceDisplayHeight)

    private fun isDirectPlayback() = playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optBoolean("pitwallDirect") == true
    private fun streamMetadata() = playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optJSONObject("pitwallStream")
    private fun controllerPosition(): Long {
        val current = player ?: return 0
        if (isDirectPlayback()) return current.currentPosition
        val info = streamMetadata() ?: return current.currentPosition
        val offset = if (current.currentTimeline.isEmpty) 0L else current.currentTimeline.getWindow(current.currentMediaItemIndex, Timeline.Window()).positionInFirstPeriodUs / 1000
        return ReceiverTimeline.position(info.getLong("originMs"), offset, current.currentPosition)
    }
    private fun controllerSeek(targetMs: Long) {
        if (streamMetadata() != null) connection?.send(FcastProtocol.UI_CONTROL, JSONObject().put("action", "seek").put("time", targetMs / 1000.0))
        else player?.let { it.seekTo(ReceiverTimeline.seek(targetMs, it.duration)) }
    }
    private fun sendPlayback() {
        if (!approved) return
        val current = player
        connection?.send(FcastProtocol.PLAYBACK_UPDATE, JSONObject().put("generationTime", System.currentTimeMillis())
            .put("playbackId", playData?.optJSONObject("metadata")?.optJSONObject("custom")?.optString("playbackId").orEmpty())
            .put("state", if (loadingRequested) if (desiredPlaying) 1 else 2 else if (!active || current?.playbackState == Player.STATE_ENDED) 0 else if (current?.playWhenReady == true) 1 else 2)
            .put("time", if (loadingRequested) playData?.optDouble("time", 0.0) ?: 0.0 else controllerPosition() / 1000.0)
            .put("duration", (streamMetadata()?.optLong("durationMs") ?: current?.duration ?: 0L).coerceAtLeast(0L) / 1000.0)
            .put("speed", current?.playbackParameters?.speed ?: 1f)
            .put("buffering", loadingRequested || current?.playbackState == Player.STATE_BUFFERING)
            .put("finished", current?.playbackState == Player.STATE_ENDED).apply {
                streamMetadata()?.takeIf { it.has("wallClockBaseMs") }?.let {
                    put("wallClockMs", Math.addExact(it.getLong("wallClockBaseMs"), controllerPosition()))
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
        active = false
        timingState.clear(); timingReported = false
        sceneView?.release(); sceneView = null
        protectedSceneView?.release(); protectedSceneView = null
        standardView?.player = null; standardView = null
        val previous = player; player = null
        previous?.release()
        widevine?.close(); widevine = null; secureOutput = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        scene = null; playData = null; renderingReported = false
        status = "Ready. Choose this TV from Cast on your phone."
    }
    private fun fail(reason: String) {
        val safe = DiagnosticRedaction.redact(reason).take(200)
        connection?.send(FcastProtocol.ERROR, JSONObject().put("message", safe))
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
                                    }, update = { it.player = current }, onRelease = {
                                        if (standardView === it) standardView = null; it.player = null
                                    }, modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset((cell.x * scale).toInt(), (cell.y * scale).toInt()) }
                                        .requiredSize(with(density) { (cell.width * scale).toDp() }, with(density) { (cell.height * scale).toDp() }))
                                }
                            }
                            else if (secureOutput) AndroidView(factory = { ProtectedDecoderSceneView(it).apply {
                                protectedSceneView = this
                                onFailure = { reason -> if (this@PitwallReceiverActivity.player === current && generation == viewGeneration) fail(reason) }
                                onFirstFrame = { if (this@PitwallReceiverActivity.player === current && generation == viewGeneration) reportRendering() }
                                bind(current, model)
                            } }, update = { it.bind(current, model) }, onRelease = {
                                if (protectedSceneView === it) protectedSceneView = null
                                it.release()
                            }, modifier = Modifier.fillMaxSize())
                            else AndroidView(factory = { SingleDecoderSceneView(it).apply {
                                sceneView = this
                                onFailure = { reason -> if (this@PitwallReceiverActivity.player === current && generation == viewGeneration) fail(reason) }
                                onFirstFrame = { if (this@PitwallReceiverActivity.player === current && generation == viewGeneration) reportRendering() }
                                bind(current, model)
                            } }, update = { it.bind(current, model) }, onRelease = {
                                if (sceneView === it) sceneView = null
                                it.release()
                            }, modifier = Modifier.fillMaxSize())
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
                            })
                            }
                        }
                    } else AndroidView(factory = { PlayerView(it).apply { standardView = this; player = current; useController = true } },
                        onRelease = { if (standardView === it) standardView = null; it.player = null }, modifier = Modifier.fillMaxSize())
                }
            } else Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(receiverName, style = Type.title1)
                Text(status, style = Type.body)
                Text("Keep this screen open. On the phone, tap Cast and choose this TV. No code or paid casting account is needed. Sign in on this TV for protected/live F1 feeds.", style = Type.subhead)
                if (connection != null) SecondaryButton("Disconnect phone") { connection?.close() }
                SecondaryButton("Exit receiver") { finish() }
            }
            if (active && !renderingReported) Text(status, style = Type.subhead, modifier = Modifier.align(Alignment.BottomCenter).background(Ink.surface).padding(12.dp))
            if (active && renderingReported && controlsVisible) Text("OK: play/pause   ←/→: seek   Back: disconnect", style = Type.caption,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
        pending?.let { request -> AlertDialog(onDismissRequest = { deny() }, title = { Text("Play from $senderName?") },
            text = { Text("This phone can control playback until it disconnects. Protected feeds use the F1 TV subscription signed in on this TV.") },
            confirmButton = { TextButton(onClick = { approved = true; pending = null; try { play(request) } catch (e: Exception) { fail(e.message ?: "Unable to play") } }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { deny() }) { Text("Deny") } }) }
    }
    private fun deny() { pending = null; connection?.close() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val current = player
        if (active && pending == null && current != null) {
            showReceiverControls()
            when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (event.repeatCount == 0) { if (current.playWhenReady) current.pause() else current.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { current.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { current.pause(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { controllerSeek((controllerPosition() - 10_000).coerceAtLeast(0)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { controllerSeek(controllerPosition() + 10_000); return true }
        }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onStop() {
        advertisement?.close(); advertisement = null
        runCatching { server?.close() }; server = null
        listeningJob?.cancel(); listeningJob = null
        val previous = connection; connection = null; previous?.close()
        pending = null; approved = false; versionReceived = false
        stopPlayback()
        super.onStop()
    }
    override fun onDestroy() {
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
