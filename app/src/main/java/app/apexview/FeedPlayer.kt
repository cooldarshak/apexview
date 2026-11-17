package app.apexview

import android.content.Context
import android.net.Uri
import android.media.MediaCodecInfo as PlatformMediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import app.apexview.tiled.HevcArchiveVodSession
import app.apexview.tiled.CmafBoxInspector
import app.apexview.tiled.CmafMetadataInspectingDataSource
import app.apexview.tiled.ProtectedHevcLiveComposition
import app.apexview.tiled.ProtectedHevcLiveDashSource

/**
 * F1's current video renditions use HEVC. Querying AVC here under-counts the secure decoder pool
 * on TVs that expose a separate HEVC decoder (for example, Amlogic's secure HEVC decoder).
 */
internal const val F1_VIDEO_MIME_TYPE = "video/hevc"

@Volatile
internal var lastVideoDecoderDiagnostic: String = "HEVC decoder capabilities not queried yet"
    private set

internal fun supportsF1VideoMimeType(supportedTypes: Array<String>): Boolean =
    supportedTypes.any { it.equals(F1_VIDEO_MIME_TYPE, ignoreCase = true) }

/**
 * Returns a conservative budget for the codec that actually decodes F1's HEVC feeds.
 *
 * maxSupportedInstances is only an upper bound. Reserve one instance for platform/other playback
 * and clamp to this app's four-feed limit. Unknown capability data remains fail-closed at one.
 */
internal fun reportedVideoDecoderBudget(reportedMaxInstances: Int?, isTv: Boolean): Int {
    if (!isTv) return 4
    val reported = reportedMaxInstances?.takeIf { it > 0 } ?: return 1
    return (reported - 1).coerceIn(1, 4)
}

internal fun queryVideoDecoderBudget(context: Context, softwareDrm: Boolean, isTv: Boolean): Int {
    if (!isTv) return 4
    return runCatching {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && supportsF1VideoMimeType(it.supportedTypes) }
        val candidates = codecs.mapNotNull { codec ->
            val caps = runCatching { codec.getCapabilitiesForType(F1_VIDEO_MIME_TYPE) }.getOrNull()
                ?: return@mapNotNull null
            val secure = caps.isFeatureSupported(PlatformMediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) ||
                caps.isFeatureRequired(PlatformMediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
            if (!softwareDrm && !secure) return@mapNotNull null
            codec to caps.maxSupportedInstances
        }
        val hardware = candidates.filter { it.first.isHardwareAccelerated }
        val pool = hardware.ifEmpty { candidates }
        val reportedMax = pool.map { it.second }.filter { it > 0 }.maxOrNull()
        val budget = reportedVideoDecoderBudget(reportedMax, isTv = true)
        lastVideoDecoderDiagnostic =
            "HEVC codecs=" + pool.joinToString { it.first.name + ":" + it.second } +
                "; reportedMax=$reportedMax; appBudget=$budget; secureRequired=${!softwareDrm}"
        Log.i(
            "ApexviewVideoBudget",
            "HEVC softwareDrm=$softwareDrm, codecs=" +
                pool.joinToString { it.first.name + ":" + it.second } +
                ", reportedMax=$reportedMax, appBudget=$budget",
        )
        budget
    }.getOrElse {
        lastVideoDecoderDiagnostic = "HEVC capability query failed: ${it.javaClass.simpleName}: ${it.message}"
        Log.w("ApexviewVideoBudget", "Unable to query HEVC decoder capacity; allowing one TV video decoder", it)
        1
    }
}

/**
 * One feed: its own ExoPlayer and its own Widevine session, licensed by F1's license server
 * with the user's tokens. [status] is the diagnostics line shown on the tile.
 */
@OptIn(UnstableApi::class)
class FeedPlayer(
    context: Context,
    stream: PlayInfo,
    ascendon: String,
    entitlement: String,
    userAgent: String,
    forceL3: Boolean,
    /** Off by default: the international feed carries French captions. */
    subtitles: Boolean,
    /** ISO 639 code, such as "en", for the commentary track. */
    commentary: String,
    /** Whether to allocate a video decoder before prepare; audio can still run without it. */
    startWithVideo: Boolean = true,
    /** Where a replay starts (ms), set before loading so nothing loads for the wrong spot. */
    startMs: Long = C.TIME_UNSET,
    /** Auto or a maximum requested video rendition shared by the viewer. */
    videoQuality: VideoQuality = VideoQuality.AUTO,
) {
    /** Authorized per-channel F1 offer used by the DASH segment-only capture path. */
    internal val sourceStream: PlayInfo = stream

    var status by mutableStateOf("Starting")
        private set

    val player: ExoPlayer
    /** Updated to the security level Widevine actually opened, not just the requested setting. */
    var usesSoftwareDrm by mutableStateOf(forceL3)
        private set
    private var drmWarning: String? = null

    /** Distance from the leading tile in ms, set by the sync loop. Null for the leader. */
    var driftMs: Long? = null

    private val createdAt = android.os.SystemClock.elapsedRealtime()
    private val initialVideoEnabled = startWithVideo
    private val initialStartPositionMs = startMs
    private lateinit var originalMediaItem: MediaItem
    private lateinit var drmManager: DefaultDrmSessionManager
    private var protectedLiveDashSource: ProtectedHevcLiveDashSource? = null
    private var archiveVodSession: HevcArchiveVodSession? = null
    private lateinit var archiveAudioSources: DefaultMediaSourceFactory
    internal val isArchiveVodPlayback: Boolean get() = archiveVodSession != null
    internal var composedSurfaceWidth: Int = 0
        private set
    internal var composedSurfaceHeight: Int = 0
        private set
    private var videoSuppressedByProtectedComposition = false

    private var state = "Starting"
    private var size = ""
    private var decoder = ""
    private var lastFeedProfileDiagnostic: String? = null
    var hevcProfileDiagnostic by mutableStateOf<String?>(null)
        private set
    private var licensed = false
    val hasLoadedDrmKeys: Boolean get() = licensed
    private var firstFrameRendered = false
    var activeVideoDecoderCount by mutableStateOf(0)
        private set
    val activeVideoDecoderName: String? get() = decoder.takeIf { activeVideoDecoderCount > 0 && it.isNotBlank() }
    val hasRenderedFirstFrame: Boolean get() = firstFrameRendered
    private var cachedHardwareDescription: Pair<String, String>? = null
    internal val selectedVideoTrackCount: Int get() = player.currentTracks.groups.sumOf { group ->
        if (group.type == C.TRACK_TYPE_VIDEO) (0 until group.mediaTrackGroup.length).count { group.isTrackSelected(it) } else 0
    }
    internal val renderedVideoFrames: Int get() {
        val counters = player.videoDecoderCounters ?: return 0
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount
    }
    val activeVideoDecoderHardware: String get() = runCatching {
        val name = activeVideoDecoderName ?: return@runCatching "hardware UNVERIFIED"
        cachedHardwareDescription?.takeIf { it.first == name }?.let { return@runCatching it.second }
        val info = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { it.name == name || it.name == name.removeSuffix(".secure") }
        val description = when (info?.isHardwareAccelerated) {
            true -> "hardware YES"
            false -> "hardware NO"
            null -> "hardware UNVERIFIED"
        }
        cachedHardwareDescription = name to description
        description
    }.getOrDefault("hardware UNVERIFIED")
    private var speed = 1f
    private var lastDropped = 0
    private var dropsPerSecond = 0
    @Volatile private var error: String? = null
    /** Why this feed isn't playing, in plain words, for the tile to show. Null while it's fine. */
    var problem by mutableStateOf<String?>(null)
        private set
    /** Waiting on the stream: starting, or stalled until more arrives. */
    var buffering by mutableStateOf(true)
        private set

    private val fit = TileFit().apply { qualityHeight = videoQuality.targetHeight }
    private var selectedVideoQuality = videoQuality

    // Written on the playback thread when the DRM session opens.
    @Volatile private var drmLevel = ""

    init {
        if (stream.allowHevcProfileProbe) {
            HevcFeedProfileCapture.registerFeed(stream.profileGroup, stream.profileLabel)
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)

        val licenseCallback = HttpMediaDrmCallback(stream.licenseUrl, http).apply {
            setKeyRequestProperty("ascendontoken", ascendon)
            setKeyRequestProperty("entitlementtoken", entitlement)
        }

        // Software DRM (L3) is what desktop Chrome gets. It skips the phone's limited pool of
        // secure hardware decoders, which is what allows several feeds at once.
        val drmProvider = ExoMediaDrm.Provider { uuid ->
            try {
                FrameworkMediaDrm.newInstance(uuid).also { drm ->
                    if (forceL3) {
                        // L3 is a preference, not a fatal requirement. Some TV firmware refuses
                        // this property; continue at the device's actual security level instead.
                        runCatching { drm.setPropertyString("securityLevel", "L3") }
                            .onFailure { failure ->
                                drmWarning = "L3 unavailable; using device DRM"
                                Log.w("ApexviewDRM", "Device refused L3; retaining its security level", failure)
                            }
                    }
                    drmLevel = runCatching { drm.getPropertyString("securityLevel") }.getOrDefault("?")
                    if (stream.allowHevcProfileProbe) {
                        HevcFeedProfileCapture.updateDrm(stream.profileGroup, stream.profileLabel, drmLevel, keysLoaded = false)
                    }
                    usesSoftwareDrm = drmLevel.equals("L3", ignoreCase = true)
                    if (forceL3 && !usesSoftwareDrm && drmWarning == null) {
                        drmWarning = "Requested L3; device reports $drmLevel"
                    }
                }
            } catch (e: UnsupportedDrmException) {
                error = "Widevine unavailable on this phone"
                DummyExoMediaDrm()
            }
        }

        drmManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, drmProvider)
            .build(licenseCallback)

        // Observe bounded init metadata for registered F1 feeds so an already-playing feed can
        // be compared later. Capture media-fragment metadata only for the two user-selected feeds.
        // The byte stream passed to Media3, Widevine and the decoder is unchanged.
        val mediaDataSourceFactory: DataSource.Factory = if (stream.allowHevcProfileProbe) {
            DataSource.Factory {
                CmafMetadataInspectingDataSource(
                    upstream = http.createDataSource(),
                    shouldCaptureInit = {
                        HevcFeedProfileCapture.shouldCaptureCmafInit(stream.profileGroup, stream.profileLabel)
                    },
                    shouldCaptureMedia = {
                        HevcFeedProfileCapture.shouldCaptureCmaf(stream.profileGroup, stream.profileLabel)
                    },
                    onCmafResponse = { bytes ->
                        val initReport = HevcFeedProfileCapture.cmafInitializationReport(
                            stream.profileGroup, stream.profileLabel
                        )
                        val report = runCatching { CmafBoxInspector.inspect(bytes, initReport) }.getOrNull()
                        if (report == null) {
                            HevcFeedProfileCapture.recordCmafParseFailure(stream.profileGroup, stream.profileLabel)
                        } else {
                            HevcFeedProfileCapture.recordCmafReport(stream.profileGroup, stream.profileLabel, report)
                            ProtectedHevcLiveComposition.recordCmafResponse(
                                stream.profileGroup, stream.profileLabel, bytes, report
                            )
                        }
                    },
                    onCaptureSkipped = {
                        HevcFeedProfileCapture.recordCmafCaptureSkipped(stream.profileGroup, stream.profileLabel)
                    }
                )
            }
        } else {
            http
        }
        val sources = DefaultMediaSourceFactory(mediaDataSourceFactory).setDrmSessionManagerProvider { drmManager }
        archiveAudioSources = DefaultMediaSourceFactory(http).setDrmSessionManagerProvider { drmManager }

        // Starts once a second of picture is in rather than the default 2.5 (2 rather than 5 after
        // a stall): F1's chunks are almost 6 s each, so a feed starting mid-chunk otherwise waits
        // for the next one too.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, 1_000, 2_000)
            .build()
        player = ExoPlayer.Builder(context, ThinningRenderersFactory(context))
            .setMediaSourceFactory(sources)
            .setLoadControl(loadControl)
            .setTrackSelector(androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context, TileFitSelectionFactory(fit)))
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitles)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !startWithVideo)
            .setPreferredAudioLanguage(commentary)
            .setPreferredTextLanguage(commentary)
            .setMaxVideoSize(videoQuality.maxWidth, videoQuality.maxHeight)
            .build()
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (!startAtBeginning || timeline.isEmpty) return
                if (timeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).isPlaceholder) return
                startAtBeginning = false
                player.seekTo(0L)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                state = when (playbackState) {
                    Player.STATE_BUFFERING -> "Buffering"
                    Player.STATE_READY -> "Playing"
                    Player.STATE_ENDED -> "Ended"
                    else -> "Stopped"
                }
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY && !started) {
                    started = true
                    // A rolling average of how long feeds take to start, for the next one.
                    val took = android.os.SystemClock.elapsedRealtime() - createdAt
                    if (took in 200..8_000) typicalStartMs = (typicalStartMs * 2 + took) / 3
                }
                refresh()
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "${e.errorCodeName}: ${describe(e)}"
                val archiveFailure = generateSequence(e as Throwable) { it.cause }
                    .filterIsInstance<app.apexview.tiled.ArchiveVodMediaException>().firstOrNull()
                problem = archiveFailure?.reason ?: explain(e)
                refresh()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) problem = null
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0) size = "${videoSize.width}x${videoSize.height}"
                refresh()
            }
        })
        fun captureVideoProfile(format: Format, trackType: Int, source: String) {
            if (!stream.allowHevcProfileProbe) return
            val snapshot = HevcFeedProfileProbe.inspect(
                trackType,
                format,
                label = stream.profileLabel,
                group = stream.profileGroup
            ).copy(
                deliveryType = stream.streamType
                    .uppercase()
                    .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                    .take(24)
                    .ifBlank { "UNKNOWN" },
                drmSecurityLevel = drmLevel.takeIf { it.isNotBlank() },
                widevineKeysLoaded = licensed,
                videoDecoderName = decoder.takeIf { it.isNotBlank() },
                firstFrameRendered = firstFrameRendered
            )
            val diagnostic = snapshot.summary
            if (diagnostic != lastFeedProfileDiagnostic) {
                lastFeedProfileDiagnostic = diagnostic
                hevcProfileDiagnostic = diagnostic.substringBefore(" vps=")
                HevcFeedProfileCapture.record(snapshot)
                Log.i(
                    "ApexviewF1FeedProfile",
                    "source=$source label=${stream.profileLabel} mime=${format.sampleMimeType} " +
                        "csdCount=${format.initializationData.size}; $diagnostic"
                )
            }
        }
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                captureVideoProfile(format, C.TRACK_TYPE_VIDEO, "video-input")
            }

            override fun onDownstreamFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                mediaLoadData: MediaLoadData,
            ) {
                val format = mediaLoadData.trackFormat ?: return
                val isVideo = mediaLoadData.trackType == C.TRACK_TYPE_VIDEO ||
                    format.sampleMimeType?.startsWith("video/") == true
                if (!isVideo) return
                captureVideoProfile(format, mediaLoadData.trackType, "downstream")
            }
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                decoder = decoderName
                activeVideoDecoderCount++
                if (stream.allowHevcProfileProbe) {
                    HevcFeedProfileCapture.updateVideoDecoder(
                        stream.profileGroup, stream.profileLabel, decoderName
                    )
                }
                refresh()
            }

            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                activeVideoDecoderCount = (activeVideoDecoderCount - 1).coerceAtLeast(0)
                if (activeVideoDecoderCount == 0) decoder = ""
                refresh()
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                firstFrameRendered = true
                if (stream.allowHevcProfileProbe) {
                    HevcFeedProfileCapture.updateFirstFrameRendered(
                        stream.profileGroup, stream.profileLabel
                    )
                }
                refresh()
            }

            override fun onSurfaceSizeChanged(eventTime: AnalyticsListener.EventTime, width: Int, height: Int) {
                composedSurfaceWidth = width
                composedSurfaceHeight = height
                if (archiveVodSession != null) Log.i("ApexviewSingleDecoder", "ARCHIVE_SURFACE width=$width height=$height")
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                // The length of the stream's video chunks, for where the next feed should start.
                // (F1's HLS reports its video chunks as the default track type, not video.)
                if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA &&
                    mediaLoadData.trackType != C.TRACK_TYPE_AUDIO && mediaLoadData.trackType != C.TRACK_TYPE_TEXT) {
                    val chunk = mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs
                    if (chunk in 1_000..12_000) {
                        chunkMs = chunk
                        if (stream.allowHevcProfileProbe) {
                            val deliveryType = stream.streamType
                                .uppercase()
                                .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                                .take(24)
                                .ifBlank { "UNKNOWN" }
                            HevcFeedProfileCapture.updateMediaChunkDuration(
                                stream.profileGroup,
                                stream.profileLabel,
                                deliveryType,
                                chunk
                            )
                        }
                    }
                }
            }

            override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime) {
                licensed = true
                if (stream.allowHevcProfileProbe) {
                    HevcFeedProfileCapture.updateDrm(stream.profileGroup, stream.profileLabel, drmLevel, keysLoaded = true)
                }
                refresh()
            }
        })

        val isHls = when (ManifestProtocol.fromUrl(stream.url)) {
            ManifestProtocol.HLS -> true
            ManifestProtocol.DASH -> false
            ManifestProtocol.UNKNOWN -> stream.streamType.contains("HLS", ignoreCase = true)
        }
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .setMimeType(if (isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD)
            .build()
        originalMediaItem = item
        if (startMs == C.TIME_UNSET) player.setMediaItem(item) else player.setMediaItem(item, startMs)
        player.playWhenReady = true
        player.prepare()
    }

    /**
     * Go to the earliest moment the stream keeps as soon as it's known. A start position given
     * with a live stream is dropped once the player learns it's live, and it opens at the live
     * edge instead; this seeks once the real timeline is in.
     */
    fun startFromBeginning() {
        startAtBeginning = true
    }

    private var startAtBeginning = false
    private var videoOn = startWithVideo
    private var started = false
    private var viewW = 0
    private var viewH = 0

    /**
     * Whether this feed's picture is wanted, and how big it's shown (px). A feed nobody can see
     * keeps only its sound and its place in the stream: no video downloaded or decoded. One that
     * shows gets the smallest rendition that fills its tile, rather than the screen's, which on
     * a big screen of small tiles saves most of the bandwidth.
     */
    fun setVideo(on: Boolean, width: Int, height: Int) {
        // In steps, so a tile being dragged bigger doesn't re-ask for tracks every frame.
        val w = (width.coerceAtLeast(1) + 63) / 64 * 64
        val h = (height.coerceAtLeast(1) + 35) / 36 * 36
        viewW = w
        viewH = h
        fit.width = w
        fit.height = h
        val enabled = on && !videoSuppressedByProtectedComposition
        if (enabled == videoOn) return
        videoOn = enabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    /** Whether this feed is downloading a picture at all. */
    val showingVideo get() = videoOn

    /** This feed's part of the connection among those showing a picture. */
    fun setBandwidthShare(share: Float) {
        fit.share = share
    }

    /**
     * Replaces this tile's existing media source with the composed CBCS HEVC grid.
     *
     * Reuses this FeedPlayer's ExoPlayer, renderer, output Surface, DRM manager and F1-token-bearing
     * license callback. Other selected FeedPlayers remain audio-only.
     */
    internal fun startProtectedComposedPlayback(
        window: ProtectedHevcLiveComposition.OutputWindow,
        widevinePssh: ByteArray?,
        clearOutput: Boolean = false,
        startPositionMs: Long? = null,
        playWhenReady: Boolean = true,
    ) {
        val nextSource = ProtectedHevcLiveDashSource(clearOutput)
        firstFrameRendered = false
        error = null
        problem = null
        try {
            nextSource.publish(window, widevinePssh)
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(PROTECTED_COMPOSED_MANIFEST_URI))
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .build()
            val mediaSource = DashMediaSource.Factory(nextSource.dataSourceFactory())
                .setDrmSessionManagerProvider { drmManager }
                .createMediaSource(mediaItem)

            val previous = protectedLiveDashSource
            protectedLiveDashSource = nextSource
            videoSuppressedByProtectedComposition = false
            videoOn = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            if (startPositionMs == null) player.setMediaSource(mediaSource)
            else player.setMediaSource(mediaSource, startPositionMs)
            previous?.release()
            player.playWhenReady = playWhenReady
            player.prepare()
        } catch (error: Throwable) {
            nextSource.release()
            throw error
        } finally {
            widevinePssh?.fill(0)
        }
    }

    /** One static composed video track plus native audio, on this same ExoPlayer and surface. */
    internal fun startArchiveVodPlayback(session: HevcArchiveVodSession, positionMs: Long, playWhenReady: Boolean) {
        val audioUri = requireNotNull(session.audioUri) { "Archive native audio was not validated" }
        val video = DashMediaSource.Factory(session.dataSourceFactory()).setDrmSessionManagerProvider { drmManager }
            .setLoadErrorHandlingPolicy(app.apexview.tiled.ArchiveVodLoadErrorPolicy())
            .createMediaSource(MediaItem.Builder().setUri(HevcArchiveVodSession.MANIFEST_URI).setMimeType(MimeTypes.APPLICATION_MPD).build())
        val audio = FilteringMediaSource(archiveAudioSources.createMediaSource(MediaItem.Builder().setUri(audioUri)
            .setMimeType(MimeTypes.APPLICATION_M3U8).build()), C.TRACK_TYPE_AUDIO)
        firstFrameRendered = false; error = null; problem = null
        archiveVodSession?.close()
        archiveVodSession = session
        videoSuppressedByProtectedComposition = false
        videoOn = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        player.setMediaSource(MergingMediaSource(true, true, video, audio), positionMs.coerceAtLeast(0))
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    /** Drop the original loaders/audio as well as video once all source decoders are released. */
    internal fun suspendForArchiveComposition() {
        player.stop()
        player.clearMediaItems()
    }

    /** Validate total picture size/rate, not the number of source feeds, before handoff. */
    internal fun requireArchiveHardwareSupport(width: Int, height: Int, frameRate: Double): String {
        val codec = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.isHardwareAccelerated && supportsF1VideoMimeType(info.supportedTypes) &&
                runCatching {
                    val caps = info.getCapabilitiesForType(F1_VIDEO_MIME_TYPE)
                    !caps.isFeatureRequired(PlatformMediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) &&
                        caps.videoCapabilities.areSizeAndRateSupported(width, height, frameRate)
                }.getOrDefault(false)
        }
        return requireNotNull(codec) { "No hardware HEVC decoder reports support for composed ${width}x$height at ${frameRate}fps" }.name
    }

    /** The rolling composed window and the source archive do not share player positions. */
    internal fun syncComposedArchivePosition(relativeArchiveMs: Long, playing: Boolean) {
        if (protectedLiveDashSource == null || player.currentTimeline.isEmpty) return
        val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        val target = relativeArchiveMs - window.positionInFirstPeriodMs
        if (player.playbackState == Player.STATE_READY && target >= 0 &&
            (player.duration <= 0 || target < player.duration)) {
            val drift = player.currentPosition - target
            if (kotlin.math.abs(drift) > 1_000L) player.seekTo(target)
            else if (playing && kotlin.math.abs(drift) > 100L) setSpeed(if (drift > 0) 0.97f else 1.03f)
            else setSpeed(1f)
        }
        player.playWhenReady = playing
    }

    /** Publishes a newer live CMAF window without rebuilding the player or DRM session. */
    internal fun publishProtectedComposedOutput(
        window: ProtectedHevcLiveComposition.OutputWindow,
        widevinePssh: ByteArray?
    ) {
        val source = protectedLiveDashSource
        if (source == null) {
            window.zero()
            widevinePssh?.fill(0)
            return
        }
        try {
            source.publish(window, widevinePssh)
        } finally {
            widevinePssh?.fill(0)
        }
    }

    /**
     * Waits until this source player's video decoder has actually been released. Audio continues.
     * The composed host is not prepared until every other selected source reports zero active
     * video decoders, avoiding even a short overlap on TVs with one secure HEVC slot.
     */
    suspend fun awaitVideoDecoderRelease(timeoutMs: Long): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            while (true) {
                val counters = player.videoDecoderCounters
                counters?.ensureUpdated()
                if (activeVideoDecoderCount == 0 && (counters == null || counters.decoderReleaseCount >= counters.decoderInitCount)) {
                    return@withTimeoutOrNull true
                }
                kotlinx.coroutines.delay(25L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

    /** Prevents a source tile from allocating a video decoder while the host plays the grid. */
    fun setProtectedCompositionVideoRole(enabled: Boolean, width: Int, height: Int) {
        videoSuppressedByProtectedComposition = !enabled
        setVideo(enabled, width, height)
    }

    /** Restores this player's original feed and releases its bounded composed-media window. */
    fun stopProtectedComposedPlayback(positionMs: Long? = null, playWhenReady: Boolean = true, enableVideo: Boolean = initialVideoEnabled) {
        protectedLiveDashSource?.release()
        protectedLiveDashSource = null
        archiveVodSession?.close()
        archiveVodSession = null
        firstFrameRendered = false; error = null; problem = null
        videoSuppressedByProtectedComposition = false
        val restorePositionMs = positionMs?.coerceAtLeast(0L)
            ?: initialStartPositionMs.takeIf { it != C.TIME_UNSET }
        if (restorePositionMs == null) {
            player.setMediaItem(originalMediaItem)
        } else {
            player.setMediaItem(originalMediaItem, restorePositionMs)
        }
        videoOn = enableVideo
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(selectedVideoQuality.maxWidth, selectedVideoQuality.maxHeight)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enableVideo)
            .build()
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    /** Clears only the source-tile video lock, keeping the TV's normal video budget in charge. */
    fun clearProtectedCompositionVideoRole() {
        // Let AppState's TV/thermal visibility budget decide whether this feed gets video again.
        // Enabling every source player here would briefly recreate the multi-decoder problem.
        videoSuppressedByProtectedComposition = false
    }

    /**
     * Changes the rendition preference without recreating the player or its Widevine session.
     * A manual choice caps resolution and pins the adaptive selection to the highest available
     * height at or below that cap; bitrate adaptation at that height remains enabled.
     */
    fun setVideoQuality(value: VideoQuality) {
        if (selectedVideoQuality == value) return
        selectedVideoQuality = value
        fit.qualityHeight = value.targetHeight
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(value.maxWidth, value.maxHeight)
            .build()
        refresh()
    }

    /** Keeps the picture going at the size it already has, whatever size its window is now. */
    fun keepVideo() {
        if (!videoOn) setVideo(true, viewW.takeIf { it > 1 } ?: 1920, viewH.takeIf { it > 1 } ?: 1080)
    }

    fun setAudible(audible: Boolean) {
        player.volume = if (audible) 1f else 0f
    }

    /** The speed chosen for watching (1 is normal); the sync's nudges go on top of it. */
    private var baseSpeed = 1f

    fun setBaseSpeed(value: Float) {
        if (value == baseSpeed) return
        baseSpeed = value
        player.setPlaybackSpeed(baseSpeed * speed)
    }

    /**
     * Small speed changes let a follower catch up without a visible jump. A share of the chosen
     * speed, so feeds sped up together still line up the same way.
     */
    fun setSpeed(value: Float) {
        if (value == speed) return
        speed = value
        player.setPlaybackSpeed(baseSpeed * value)
    }

    /**
     * The wall-clock time (epoch ms) of the frame on screen, when the stream carries one
     * (DASH availabilityStartTime or HLS program date time). Null otherwise.
     */
    fun wallClockMs(): Long? = windowStartMs()?.let { it + player.currentPosition }

    /** The wall-clock time (epoch ms) of position zero, when the stream carries a clock. */
    fun windowStartMs(): Long? {
        archiveVodSession?.let { return it.archiveStartTimeMs }
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        if (window.windowStartTimeMs == C.TIME_UNSET) return null
        return window.windowStartTimeMs
    }

    /** Rebuilds the diagnostics line. Called on player events and once a second by the tile. */
    fun refresh() {
        val dropped = player.videoDecoderCounters?.droppedBufferCount ?: 0
        if (dropped >= lastDropped) dropsPerSecond = dropped - lastDropped
        lastDropped = dropped

        status = error ?: listOfNotNull(
            state,
            size.ifEmpty { null },
            when {
                state != "Playing" -> null
                dropsPerSecond == 0 -> "smooth"
                else -> "$dropsPerSecond drops/s"
            },
            decoder.ifEmpty { null }?.let { "codec $it" },
            drmLevel.ifEmpty { null }?.let { "DRM $it" },
            drmWarning,
            driftMs?.let { "sync %+.1fs".format(it / 1000f) },
            if (wallClockMs() == null && state == "Playing") "no stream clock" else null,
        ).joinToString(" · ")
    }

    fun release() {
        archiveVodSession?.close()
        archiveVodSession = null
        protectedLiveDashSource?.release()
        protectedLiveDashSource = null
        player.release()
    }

    /**
     * What a playback error means for the viewer. F1 refusing the licence or the stream is almost
     * always the account's plan not covering it; anything else gets F1's own error name.
     */
    private fun explain(e: PlaybackException): String {
        val code = generateSequence(e as Throwable) { it.cause }
            .firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.responseCode
        val refused = code == 401 || code == 403 ||
            e.errorCode in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED
        return if (refused) "F1 TV won't play this on your account. Its plan may not include this session."
        else "This feed stopped: ${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}."
    }

    private fun describe(e: PlaybackException): String {
        val chain = generateSequence(e as Throwable) { it.cause }.toList()
        chain.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.let {
            return "HTTP ${it.responseCode} from ${it.dataSpec.uri.host}"
        }
        val root = chain.last()
        return "${root.javaClass.simpleName}: ${root.message}"
    }

    companion object {
        private const val PROTECTED_COMPOSED_MANIFEST_URI =
            "https://apexview.invalid/protected-grid/manifest.mpd"

        /** How long a new feed has been taking to start playing (ms), learned as feeds start. */
        @Volatile var typicalStartMs = 2_000L
        /** How long the streams' video chunks run (ms), learned from loading; 0 until known. */
        @Volatile var chunkMs = 0L
    }
}
