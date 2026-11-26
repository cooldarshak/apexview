package app.apexview

import android.content.Context
import android.net.Uri
import android.media.MediaCodecInfo as PlatformMediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Trace
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
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
import app.apexview.scene.SceneCodecTimestampCorrelation
import app.apexview.tiled.HevcArchiveVodSession
import app.apexview.tiled.ArchiveAudioSelectionPlan
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

internal fun isTclG09(): Boolean =
    android.os.Build.VERSION.SDK_INT == 31 &&
        android.os.Build.MANUFACTURER.equals("TCL", ignoreCase = true) &&
        android.os.Build.DEVICE.equals("G09", ignoreCase = true)

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
    /** Enable only caption tracks that the authorized F1 source actually offers. */
    subtitles: Boolean,
    /** ISO 639 code, such as "en", for the commentary track. */
    commentary: String,
    captionLanguage: String = CaptionPolicy.AUTO,
    /** Whether to allocate a video decoder before prepare; audio can still run without it. */
    startWithVideo: Boolean = true,
    /** Where a replay starts (ms), set before loading so nothing loads for the wrong spot. */
    startMs: Long = C.TIME_UNSET,
    /** Auto or a maximum requested video rendition shared by the viewer. */
    videoQuality: VideoQuality = VideoQuality.AUTO,
    preferFastStart: Boolean = false,
) {
    /** Authorized per-channel F1 offer used by the DASH segment-only capture path. */
    internal val sourceStream: PlayInfo = stream

    var status by mutableStateOf("Starting")
        private set

    val player: ExoPlayer
    /** Codec PTS correlation for the real clear/protected scene; no SF assertion. */
    internal val sceneCodecCorrelation = SceneCodecTimestampCorrelation()
    /** Updated to the security level Widevine actually opened, not just the requested setting. */
    var usesSoftwareDrm by mutableStateOf(forceL3)
        private set
    private var drmWarning: String? = null

    /** Distance from the leading tile in ms, set by the sync loop. Null for the leader. */
    var driftMs: Long? = null

    private val createdAt = android.os.SystemClock.elapsedRealtime()
    // Per-player controller. Never promotes a MULTIPLE-decoder source feed to
    // accelerated full-rate output or creates another decoder/DRM session.
    private val outputCadence = AdaptivePlaybackCadenceObserver(context, sceneCodecCorrelation)
    private val archivePower = context.applicationContext.getSystemService(android.os.PowerManager::class.java)
    // The active scene owns the display-rate decision. A previous renderer
    // disposed after a TextureView/SurfaceView handoff cannot revoke a newer
    // scene's selection.
    private var outputDisplayOwner: Any? = null

    internal fun setComposedOutputDisplay(owner: Any, displayId: Int?) {
        if (displayId == null) {
            if (outputDisplayOwner !== owner) return
            outputDisplayOwner = null
            outputCadence.activeDisplayId = -1
        } else {
            outputDisplayOwner = owner
            outputCadence.activeDisplayId = displayId
        }
    }
    private val initialVideoEnabled = startWithVideo
    private val initialStartPositionMs = startMs
    private lateinit var originalMediaItem: MediaItem
    private lateinit var drmManager: DefaultDrmSessionManager
    private var protectedLiveDashSource: ProtectedHevcLiveDashSource? = null
    private var archiveVodSession: HevcArchiveVodSession? = null
    private var archiveSelectedAudioUri: Uri? = null
    private var archiveAudioChildren: List<Uri> = emptyList()
    private var archiveCaptionChildren: Map<String, Int> = emptyMap()
    private var archiveProgrammeLabel: String? = null
    private var archiveCaptionSelected by mutableStateOf(false)
    internal val captionOutputReady: Boolean get() = archiveVodSession == null || archiveCaptionSelected
    private lateinit var archiveAudioSources: DefaultMediaSourceFactory
    internal val isArchiveVodPlayback: Boolean get() = archiveVodSession != null
    internal val archiveSceneLabels: List<String> get() = archiveVodSession?.sourceLabels.orEmpty()
    internal val archiveSceneWidth: Int get() = archiveVodSession?.width ?: 0
    internal val archiveSceneHeight: Int get() = archiveVodSession?.height ?: 0
    internal val archiveSceneSession: HevcArchiveVodSession? get() = archiveVodSession
    internal fun archiveSessionForCast(): HevcArchiveVodSession? = archiveVodSession

    internal var composedSurfaceWidth: Int = 0
        private set
    internal var composedSurfaceHeight: Int = 0
        private set
    private var remotePlaybackSuspended = false
    // Constructor language must survive subsequent in-player CC toggles.
    private val captionCommentaryLanguage = commentary
    private var captionsSelected = subtitles
    private var selectedCaptionLanguage = CaptionPolicy.normalizeLanguage(captionLanguage)
    private var remoteTextWasDisabled = !subtitles
    var availableCaptionTracks by mutableStateOf<List<CaptionTrackOption>>(emptyList())
        private set

    /** Apply player CC changes immediately, without tearing down any codec or DRM session. */
    fun setCaptions(enabled: Boolean, language: String) {
        val normalized = CaptionPolicy.normalizeLanguage(language)
        val disabled = !enabled || remotePlaybackSuspended
        if (captionsSelected == enabled && selectedCaptionLanguage == normalized &&
            (archiveVodSession != null || (C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) == disabled)) return
        captionsSelected = enabled
        selectedCaptionLanguage = normalized
        remoteTextWasDisabled = !enabled
        if (archiveVodSession != null) {
            applyArchiveCaptionTrack(player.currentTracks)
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled)
            .setPreferredTextLanguage(CaptionPolicy.textLanguage(normalized, captionCommentaryLanguage))
            .setSelectUndeterminedTextLanguage(enabled)
            .build()
        playbackEvent("captions", "enabled=$enabled language=$normalized offered=${availableCaptionTracks.size}")
    }

    /** A remote renderer owns playback. Disable tracks as well as pausing to release codec slots. */
    internal fun suspendForRemotePlayback(suspended: Boolean) {
        if (remotePlaybackSuspended == suspended) {
            if (suspended) player.playWhenReady = false
            return
        }
        remotePlaybackSuspended = suspended
        if (suspended) {
            remoteTextWasDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
            player.playWhenReady = false
            videoOn = false
            audioTrackEnabled = false
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            player.volume = 0f
        } else {
            videoOn = false // The visibility budget must explicitly re-enable the actual video track.
            if (archiveVodSession != null) applyArchiveCaptionTrack(player.currentTracks)
            else player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, remoteTextWasDisabled).build()
        }
        // AppState restores visibility/audio under the existing single/multiple decoder budget.
    }
    private var videoSuppressedByProtectedComposition = false

    private var state = "Starting"
    private var size = ""
    internal var nativeSourceRendition by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    private val releaseGate = PlaybackReleaseGate()
    private val bufferEpisodes = PlaybackBufferEpisodes()
    private var decoder = ""
    private var lastFeedProfileDiagnostic: String? = null
    var hevcProfileDiagnostic by mutableStateOf<String?>(null)
        private set
    private var licensed = false
    val hasLoadedDrmKeys: Boolean get() = licensed
    private var firstFrameRendered by mutableStateOf(false)
    private val diagnosticId = System.identityHashCode(this)
    private val playbackAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private var parkedForArchive = false
    private var activeAudioDecoders = 0
    private val memoryLoads = Media3LoadLedger()
    private var memoryOwnerRetired = false
    private fun updateMemoryOwner() {
        if (memoryOwnerRetired) return
        val role = when {
            parkedForArchive && player.mediaItemCount == 0 -> "ARCHIVE_PARKED"
            remotePlaybackSuspended -> "REMOTE"
            archiveVodSession != null || protectedLiveDashSource != null -> "COMPOSED"
            else -> "SOURCE"
        }
        Media3MemoryOwners.update(playbackAllocator, role, activeVideoDecoderCount, activeAudioDecoders,
            player.mediaItemCount, if (player.currentTracks.groups.any { it.isSelected })
                player.totalBufferedDuration.coerceAtLeast(0) else -1L, -1, memoryLoads.snapshot())
    }
    private var loggedClock = false
    private var firstFrameLogged = false
    private var seekSequence = 0
    private var pendingSeekAtMs: Long? = null
    private var lastSampleUptimeMs = 0L
    private var lastSamplePositionMs = 0L

    private fun playbackEvent(event: String, detail: String) {
        if (DiagnosticLogs.recording) {
            Log.i("PitwallPlayback", "feed=$diagnosticId uptimeMs=${android.os.SystemClock.elapsedRealtime()} event=$event $detail")
        }
    }

    internal fun logPlaybackSample() {
        updateMemoryOwner()
        val counters = player.videoDecoderCounters
        counters?.ensureUpdated()
        val uptime = android.os.SystemClock.elapsedRealtime()
        val position = player.currentPosition
        val sampleIntervalMs = if (lastSampleUptimeMs > 0L) uptime - lastSampleUptimeMs else 0L
        val positionDeltaMs = if (lastSampleUptimeMs > 0L) position - lastSamplePositionMs else 0L
        val clockDifferenceMs = if (sampleIntervalMs > 0L && player.isPlaying) {
            positionDeltaMs - (sampleIntervalMs * player.playbackParameters.speed).toLong()
        } else 0L
        lastSampleUptimeMs = uptime
        lastSamplePositionMs = position
        playbackEvent("sample", "positionMs=${player.currentPosition} bufferedMs=${player.bufferedPosition} " +
            "state=${player.playbackState} playing=${player.isPlaying} decoder=${activeVideoDecoderName ?: "none"} " +
            "rendered=${counters?.renderedOutputBufferCount ?: 0} dropped=${counters?.droppedBufferCount ?: 0} " +
            "skipped=${counters?.skippedOutputBufferCount ?: 0} maxConsecutiveDrops=${counters?.maxConsecutiveDroppedBufferCount ?: 0} " +
            "sampleIntervalMs=$sampleIntervalMs mediaPositionDeltaMs=$positionDeltaMs " +
            "mediaClockProgressionDifferenceMs=$clockDifferenceMs playbackSpeed=${player.playbackParameters.speed} " +
            "audioFormat=${player.audioFormat?.sampleMimeType ?: "none"} " +
            "media3InUseBytes=${playbackAllocator.totalBytesAllocated} parkedForArchive=$parkedForArchive mediaItems=${player.mediaItemCount} " +
            "videoDecoders=$activeVideoDecoderCount audioDecoders=$activeAudioDecoders audioHardwareClock=NOT_MEASURED")
    }
    var activeVideoDecoderCount by mutableStateOf(0)
        private set
    val activeVideoDecoderName: String? get() = decoder.takeIf { activeVideoDecoderCount > 0 && it.isNotBlank() }
    val hasRenderedFirstFrame: Boolean get() = firstFrameRendered
    private var cachedHardwareDescription: Pair<String, String>? = null
    internal val selectedVideoTrackCount: Int get() = player.currentTracks.groups.sumOf { group ->
        if (group.type == C.TRACK_TYPE_VIDEO) (0 until group.mediaTrackGroup.length).count { group.isTrackSelected(it) } else 0
    }
    internal fun compositionPlaybackEvidence(): String {
        val counters = player.videoDecoderCounters
        counters?.ensureUpdated()
        val format = player.videoFormat
        return "decoder=${activeVideoDecoderName ?: "none"} hardware=$activeVideoDecoderHardware " +
            "drm=${drmLevel.ifBlank { "unknown" }} keysLoaded=$hasLoadedDrmKeys " +
            "firstFrame=$hasRenderedFirstFrame selectedVideoTracks=$selectedVideoTrackCount " +
            "codedFormat=${format?.width ?: 0}x${format?.height ?: 0} fps=${format?.frameRate ?: -1f} " +
            "surface=${composedSurfaceWidth}x$composedSurfaceHeight positionMs=${player.currentPosition} " +
            "bufferedMs=${player.totalBufferedDuration} rendered=${counters?.renderedOutputBufferCount ?: 0} " +
            "dropped=${counters?.droppedBufferCount ?: 0} playing=${player.isPlaying}"
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

    // The G09's Amlogic OMX path still showed frame corruption when Auto climbed through
    // several resolutions, even after making each resolution change replace the codec.
    private val stableTvResolution = isTclG09()

    // The 720p F1 variant on G09 is a cropped 1920x1088 frame, so it offers no decoder
    // size saving. For Auto, favor the full-frame rendition and keep the user's explicit caps.
    private fun qualityWidth(quality: VideoQuality) =
        if (stableTvResolution && quality == VideoQuality.AUTO) VideoQuality.P1080.maxWidth else quality.maxWidth
    private fun qualityHeight(quality: VideoQuality) =
        if (stableTvResolution && quality == VideoQuality.AUTO) VideoQuality.P1080.maxHeight else quality.maxHeight

    private val fit = TileFit().apply {
        qualityHeight = videoQuality.targetHeight
        fastStart = preferFastStart || startMs != C.TIME_UNSET
    }
    private var selectedVideoQuality = videoQuality
    internal var offeredSourceVideoSizes by mutableStateOf<List<Pair<Int, Int>>>(emptyList())
        private set
    internal var capturedSourceVideoSizes by mutableStateOf<List<Pair<Int, Int>>?>(null)
        private set

    internal fun recordCapturedSourceOffers(sizes: List<Pair<Int, Int>>) {
        if (capturedSourceVideoSizes != sizes) capturedSourceVideoSizes = sizes
    }
    var sourceQualityNotice by mutableStateOf<String?>(null)
        private set

    private fun refreshMissingQualityNotice() {
        val next = if (archiveVodSession != null || protectedLiveDashSource != null) null
            else QualityDisclosurePolicy.unavailableManualCap(
                selectedVideoQuality, offeredSourceVideoSizes)
        if (sourceQualityNotice != next) sourceQualityNotice = next
    }

    private var externalQualityFloor = false
    private val sourceTrackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context, TileFitSelectionFactory(fit))
    private var audioTrackEnabled = false

    // Written on the playback thread when the DRM session opens.
    @Volatile private var drmLevel = ""

    init {
        if (stream.allowHevcProfileProbe) {
            HevcFeedProfileCapture.registerFeed(stream.profileGroup, stream.profileLabel)
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(false)

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
                    drmLevel = runCatching { drm.getPropertyString("securityLevel") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "?"
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
            .setAllocator(playbackAllocator)
            .setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, 1_000, 2_000)
            .build()
        player = ExoPlayer.Builder(context, ThinningRenderersFactory(context, outputCadence))
            .setMediaSourceFactory(sources)
            .setLoadControl(loadControl)
            .setTrackSelector(sourceTrackSelector)
            .setBandwidthMeter(androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(context))
            .build()
        Media3MemoryOwners.register(playbackAllocator, "feed-$diagnosticId")
        val codecReleaseTiming = CodecReleaseTiming()
        var lastCodecReleaseLogNs = 0L
        player.setVideoFrameMetadataListener(object : VideoFrameMetadataListener {
            override fun onVideoFrameAboutToBeRendered(
                presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?
            ) {
                val now = System.nanoTime()
                sceneCodecCorrelation.onCodecFrame(presentationTimeUs, releaseTimeNs, now)
                if (!DiagnosticLogs.recording || archiveVodSession == null) return
                codecReleaseTiming.record(presentationTimeUs, releaseTimeNs, now)
                if (Trace.isEnabled()) {
                    Trace.setCounter("PitwallCodecPtsMs", presentationTimeUs / 1000L)
                    Trace.setCounter("PitwallCodecPlannedReleaseMs", releaseTimeNs / 1_000_000L)
                }
                if (now - lastCodecReleaseLogNs >= 5_000_000_000L) {
                    val sample = codecReleaseTiming.snapshotAndReset()
                    Log.i("ApexviewSingleDecoder", "SCENE_CODEC_RELEASE frames=${sample.frames} " +
                        "ptsBins13_19_23_30_45_100=${sample.ptsIntervals.compact()} " +
                        "plannedReleaseBins13_19_23_30_45_100=${sample.plannedReleaseIntervals.compact()} " +
                        "plannedLeadP95UpperMs=${sample.plannedReleaseLead.percentileUpperMs(95)} " +
                        "callbacksAfterReleaseTime=${sample.callbacksAfterPlannedRelease} " +
                        "physicalPresentation=NOT_MEASURED")
                    lastCodecReleaseLogNs = now
                }
            }
        })
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            // Only the currently audible tile needs an audio decoder. Setting volume to zero
            // still makes Media3 open an AAC decoder for every muted tile, which wastes codec
            // threads and buffers on phones and Android TV during multiview playback.
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitles)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !startWithVideo)
            .setPreferredAudioLanguage(commentary)
            .setPreferredTextLanguage(CaptionPolicy.textLanguage(captionLanguage, commentary))
            .setSelectUndeterminedTextLanguage(subtitles)
            .setMaxVideoSize(qualityWidth(videoQuality), qualityHeight(videoQuality))
            .setForceHighestSupportedBitrate(stableTvResolution)
            .apply { if (stableTvResolution) setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264) }
            .build()
        if (stableTvResolution) Log.i("PitwallVideo",
            "TCL_STABLE_RENDITION preferred=${videoQuality.name} cap=${qualityWidth(videoQuality)}x${qualityHeight(videoQuality)}")
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                archiveSelectedAudioUri?.let { applyArchiveAudioTrack(it, tracks) }
                // Never replace the original feed ladder with composed atlas dimensions,
                // or erase it just because its source player is parked.
                val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (archiveVodSession == null && protectedLiveDashSource == null &&
                    !parkedForArchive && videoGroups.isNotEmpty()) {
                    val sizes = videoGroups.flatMap { group ->
                    (0 until group.length).mapNotNull { index ->
                        if (!group.isTrackSupported(index)) null else {
                            val format = group.getTrackFormat(index)
                            (format.width to format.height).takeIf { it.first > 0 && it.second > 0 }
                        }
                    }
                    }.distinct()
                    if (offeredSourceVideoSizes != sizes) offeredSourceVideoSizes = sizes
                }
                refreshMissingQualityNotice()
                if (archiveVodSession != null) {
                    applyArchiveCaptionTrack(tracks)
                    return
                }
                val offered = buildList {
                    tracks.groups.forEach { group ->
                        if (group.type == C.TRACK_TYPE_TEXT) for (index in 0 until group.length) {
                            if (group.isTrackSupported(index)) {
                                val format = group.getTrackFormat(index)
                                add(format.language to format.label)
                            }
                        }
                    }
                }
                availableCaptionTracks = CaptionPolicy.tracks(offered)
                playbackEvent("caption-tracks", "count=${availableCaptionTracks.size} languages=${availableCaptionTracks.joinToString { it.language }}")
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (DiagnosticLogs.recording && !loggedClock) windowStartMs()?.let { start ->
                    loggedClock = true
                    playbackEvent("clock", "windowStartUtcMs=$start reason=$reason positionMs=${player.currentPosition}")
                }
                if (!startAtBeginning || timeline.isEmpty) return
                if (timeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).isPlaceholder) return
                startAtBeginning = false
                player.seekTo(0L)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val now = android.os.SystemClock.elapsedRealtime()
                bufferEpisodes.onState(now, playbackState == Player.STATE_BUFFERING,
                    playbackState == Player.STATE_READY, player.playWhenReady)?.let { episode ->
                    playbackEvent("buffer-episode", "phase=${episode.phase} durationMs=${episode.durationMs} " +
                        "bufferedDurationMs=${player.totalBufferedDuration} cause=UNATTRIBUTED")
                }
                state = when (playbackState) {
                    Player.STATE_BUFFERING -> "Buffering"
                    Player.STATE_READY -> "Playing"
                    Player.STATE_ENDED -> "Ended"
                    else -> "Stopped"
                }
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_IDLE && parkedForArchive) {
                    memoryLoads.clearPending()
                    playbackAllocator.trim()
                }
                updateMemoryOwner()
                playbackEvent("state", "state=$playbackState positionMs=${player.currentPosition} bufferedMs=${player.bufferedPosition}")
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
                playbackEvent("error", "positionMs=${player.currentPosition} $error")
                val archiveFailure = generateSequence(e as Throwable) { it.cause }
                    .filterIsInstance<app.apexview.tiled.ArchiveVodMediaException>().firstOrNull()
                problem = archiveFailure?.reason ?: explain(e)
                refresh()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) sceneCodecCorrelation.reset()
                if (reason == Player.DISCONTINUITY_REASON_SEEK) bufferEpisodes.onSeek(android.os.SystemClock.elapsedRealtime())
                if (DiagnosticLogs.recording && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    pendingSeekAtMs = android.os.SystemClock.elapsedRealtime()
                    seekSequence++
                }
                playbackEvent("discontinuity", "fromMs=${oldPosition.positionMs} toMs=${newPosition.positionMs} " +
                    "reason=$reason seek=$seekSequence")
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
                nativeSourceRendition = if (archiveVodSession == null && protectedLiveDashSource == null &&
                    format.width > 0 && format.height > 0) format.width to format.height else null
                playbackEvent("video-input-rendition", "requestedCap=${selectedVideoQuality.label} " +
                    "inputPixels=${format.width}x${format.height} composed=${archiveVodSession != null || protectedLiveDashSource != null}")
                playbackEvent("codec-owner-format", "decoder=$decoder count=$activeVideoDecoderCount " +
                    "coded=${format.width}x${format.height} fps=${format.frameRate} " +
                    "declaredMaxInputBytes=${format.maxInputSize} actualCodecBytes=NOT_MEASURED")
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
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                                                   initializedTimestampMs: Long, initializationDurationMs: Long) {
                activeAudioDecoders++
                updateMemoryOwner()
            }
            override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                activeAudioDecoders = (activeAudioDecoders - 1).coerceAtLeast(0)
                updateMemoryOwner()
            }
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                sceneCodecCorrelation.reset()
                decoder = decoderName
                if (archiveVodSession != null) firstFrameRendered = false
                activeVideoDecoderCount++
                playbackEvent("codec-owner-init", "decoder=$decoderName count=$activeVideoDecoderCount actualCodecBytes=NOT_MEASURED")
                updateMemoryOwner()
                if (stream.allowHevcProfileProbe) {
                    HevcFeedProfileCapture.updateVideoDecoder(
                        stream.profileGroup, stream.profileLabel, decoderName
                    )
                }
                refresh()
            }

            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                sceneCodecCorrelation.reset()
                activeVideoDecoderCount = (activeVideoDecoderCount - 1).coerceAtLeast(0)
                playbackEvent("codec-owner-release", "decoder=$decoderName count=$activeVideoDecoderCount actualCodecBytes=NOT_MEASURED")
                if (activeVideoDecoderCount == 0) { decoder = ""; nativeSourceRendition = null }
                updateMemoryOwner()
                refresh()
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                firstFrameRendered = true
                fit.fastStart = false
                val seekAt = pendingSeekAtMs
                if (seekAt != null && renderTimeMs >= seekAt) {
                    playbackEvent("seek-rendered", "seek=$seekSequence elapsedMs=${renderTimeMs - seekAt} " +
                        "positionMs=${eventTime.currentPlaybackPositionMs} decoder=$decoder")
                    pendingSeekAtMs = null
                }
                if (!firstFrameLogged) {
                    playbackEvent("startup-rendered", "elapsedMs=${(renderTimeMs - createdAt).coerceAtLeast(0)} " +
                        "positionMs=${eventTime.currentPlaybackPositionMs} decoder=$decoder")
                    firstFrameLogged = true
                }
                playbackEvent("first-frame", "renderUptimeMs=$renderTimeMs positionMs=${eventTime.currentPlaybackPositionMs} decoder=$decoder")
                if (stream.allowHevcProfileProbe) {
                    HevcFeedProfileCapture.updateFirstFrameRendered(
                        stream.profileGroup, stream.profileLabel
                    )
                }
                refresh()
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                playbackEvent("drops", "frames=$droppedFrames intervalMs=$elapsedMs positionMs=${eventTime.currentPlaybackPositionMs}")
            }

            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime, totalProcessingOffsetUs: Long, frameCount: Int,
            ) {
                if (frameCount > 0) playbackEvent("codec-frame-processing", "frames=$frameCount " +
                    "meanOffsetUs=${totalProcessingOffsetUs / frameCount} totalOffsetUs=$totalProcessingOffsetUs " +
                    "positionMs=${eventTime.currentPlaybackPositionMs} physicalPresentation=NOT_MEASURED")
            }

            override fun onSurfaceSizeChanged(eventTime: AnalyticsListener.EventTime, width: Int, height: Int) {
                composedSurfaceWidth = width
                composedSurfaceHeight = height
                if (archiveVodSession != null) Log.i("ApexviewSingleDecoder", "ARCHIVE_SURFACE width=$width height=$height")
            }

            private val observedLoads = LinkedHashMap<Long, Int>()

            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                if (!memoryOwnerRetired && !(parkedForArchive && player.mediaItemCount == 0) &&
                    mediaLoadData.dataType == C.DATA_TYPE_MEDIA)
                    memoryLoads.start(loadEventInfo.loadTaskId, mediaLoadData.trackType)
                if (!DiagnosticLogs.recording) { observedLoads.clear(); return }
                // Include steady playback, but keep the diagnostic map bounded.
                if (observedLoads.size >= 128) {
                    val oldest = observedLoads.keys.first()
                    observedLoads.remove(oldest)
                    playbackEvent("load-observation-evicted", "load=$oldest reason=bounded-diagnostics")
                }
                observedLoads[loadEventInfo.loadTaskId] = seekSequence
                logLoad("load-start", loadEventInfo, mediaLoadData, seekSequence)
            }

            private fun logLoad(
                event: String,
                info: LoadEventInfo,
                media: MediaLoadData,
                seek: Int,
                detail: String = "",
            ) {
                playbackEvent(event, "load=${info.loadTaskId} seek=$seek dataType=${media.dataType} " +
                    "trackType=${media.trackType} startMs=${media.mediaStartTimeMs} endMs=${media.mediaEndTimeMs} " +
                    "loadUptimeMs=${info.elapsedRealtimeMs} durationMs=${info.loadDurationMs} bytes=${info.bytesLoaded} " +
                    "bufferedDurationMs=${player.totalBufferedDuration} playbackState=${player.playbackState}$detail")
            }

            override fun onLoadCanceled(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                if (memoryOwnerRetired || parkedForArchive && player.mediaItemCount == 0) return
                memoryLoads.finish(loadEventInfo.loadTaskId, loadEventInfo.bytesLoaded)
                val seek = observedLoads.remove(loadEventInfo.loadTaskId) ?: return
                logLoad("load-cancel", loadEventInfo, mediaLoadData, seek)
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean,
            ) {
                if (memoryOwnerRetired || parkedForArchive && player.mediaItemCount == 0) return
                if (wasCanceled) memoryLoads.finish(loadEventInfo.loadTaskId, loadEventInfo.bytesLoaded)
                val seek = observedLoads[loadEventInfo.loadTaskId] ?: return
                // Media3's wrapper alone hid the distinction between a timeout and an HTTP
                // rejection in device logs. Emit only exception types / status, never URLs.
                val causes = generateSequence(error as Throwable) { it.cause }.take(8).toList()
                val root = causes.last().javaClass.simpleName
                val status = causes.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                logLoad("load-error", loadEventInfo, mediaLoadData, seek,
                    " error=${error.javaClass.simpleName} root=$root" +
                        (status?.let { " httpStatus=$it" } ?: "") + " canceled=$wasCanceled")
                if (wasCanceled) observedLoads.remove(loadEventInfo.loadTaskId)
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                if (memoryOwnerRetired || parkedForArchive && player.mediaItemCount == 0) return
                memoryLoads.finish(loadEventInfo.loadTaskId, loadEventInfo.bytesLoaded)
                observedLoads.remove(loadEventInfo.loadTaskId)?.let {
                    logLoad("load-end", loadEventInfo, mediaLoadData, it)
                }
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

    internal fun seekFromControls(positionMs: Long) {
        if (!player.isCurrentMediaItemLive && archiveVodSession == null) fit.fastStart = true
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        player.seekTo(positionMs)
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
        val enabled = on && !videoSuppressedByProtectedComposition && !remotePlaybackSuspended
        if (enabled == videoOn) return
        videoOn = enabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    /** Whether this feed is downloading a picture at all. */
    val showingVideo get() = videoOn
    // READY alone is not proof of an active media clock. A source with no
    // selected output can repeatedly seek an empty sample queue.
    internal val participatesInClockSync: Boolean get() =
        !parkedForArchive && !remotePlaybackSuspended && player.mediaItemCount > 0 &&
            (videoOn || audioTrackEnabled || player.currentTracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT &&
                    (0 until group.mediaTrackGroup.length).any { group.isTrackSelected(it) }
            })

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
    internal suspend fun startProtectedComposedPlayback(
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
        var publisherStarted = false
        val previous = protectedLiveDashSource
        var handoffInstalled = false
        try {
            // DASH package verification and CBCS resource publication can scan
            // tens of MB. Never execute them on Media3's/UI application looper.
            // ProtectedHevcLiveDashSource serializes publish/release, while all
            // ExoPlayer calls remain on this coroutine's original main thread.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                publisherStarted = true
                nextSource.publish(window, widevinePssh)
            }
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(PROTECTED_COMPOSED_MANIFEST_URI))
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .build()
            val mediaSource = DashMediaSource.Factory(nextSource.dataSourceFactory())
                .setDrmSessionManagerProvider { drmManager }
                .createMediaSource(mediaItem)

            protectedLiveDashSource = nextSource
            outputCadence.composedSingle = true
            videoSuppressedByProtectedComposition = false
            videoOn = !remotePlaybackSuspended
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, remotePlaybackSuspended)
                .build()
            if (startPositionMs == null) player.setMediaSource(mediaSource)
            else player.setMediaSource(mediaSource, startPositionMs)
            player.playWhenReady = playWhenReady && !remotePlaybackSuspended
            player.prepare()
            handoffInstalled = true
            parkedForArchive = false
            updateMemoryOwner()
            // Retire only after the new Media3 source was successfully installed.
            previous?.release()
        } catch (error: Throwable) {
            // If cancellation happens before the IO block starts, publish() never
            // receives ownership. Clean that detached encrypted window here.
            if (!publisherStarted) window.zero()
            if (!handoffInstalled) {
                if (protectedLiveDashSource === nextSource) protectedLiveDashSource = previous
                nextSource.release()
                outputCadence.composedSingle =
                    protectedLiveDashSource != null || archiveVodSession != null
            }
            throw error
        } finally {
            widevinePssh?.fill(0)
        }
    }

    /** One static composed video track plus native audio, on this same ExoPlayer and surface. */
    internal fun startArchiveVodPlayback(
        session: HevcArchiveVodSession, positionMs: Long, playWhenReady: Boolean,
        programme: String = session.clockLabel,
    ) {
        val audioUri = requireNotNull(session.audioUris[programme] ?: session.audioUri.takeIf { programme == session.clockLabel }) {
            "Archive native audio was not validated"
        }
        val video = DashMediaSource.Factory(session.dataSourceFactory()).setDrmSessionManagerProvider { drmManager }
            .setLoadErrorHandlingPolicy(app.apexview.tiled.ArchiveVodLoadErrorPolicy())
            .createMediaSource(MediaItem.Builder().setUri(HevcArchiveVodSession.MANIFEST_URI).setMimeType(MimeTypes.APPLICATION_MPD).build())
        // Keep native audio AND F1-provided text renditions while replacing only video
        // with the composed HEVC atlas. This does not add any video decoder.
        val audioChildren = ArchiveAudioSelectionPlan.children(audioUri, session.stableAudioUris)
        val audioSources = audioChildren.map { uri ->
            FilteringMediaSource(archiveAudioSources.createMediaSource(MediaItem.Builder().setUri(uri)
                .setMimeType(MimeTypes.APPLICATION_M3U8).build()), setOf(C.TRACK_TYPE_AUDIO))
        }
        val captionSources = session.captionSources.filterKeys { label ->
            label == programme || session.audioUris[label] in audioChildren
        }
        val captionIndexes = captionSources.keys.withIndex().associate { (index, label) ->
            label to (audioSources.size + index + 1)
        }
        val captionChildren = captionSources.values.map {
            // Every text child belongs to its own verified programme. None exposes video or audio.
            FilteringMediaSource(archiveAudioSources.createMediaSource(MediaItem.Builder()
                .setUri(it.masterUri).setMimeType(MimeTypes.APPLICATION_M3U8)
                .setStreamKeys(it.streamKeys).build()), setOf(C.TRACK_TYPE_TEXT))
        }
        firstFrameRendered = false; error = null; problem = null
        val previousArchive = archiveVodSession
        val previousAudio = archiveSelectedAudioUri
        val previousAudioChildren = archiveAudioChildren
        val previousProgramme = archiveProgrammeLabel
        val previousCaptionChildren = archiveCaptionChildren
        val previousSelection = player.trackSelectionParameters
        archiveVodSession = session
        archiveAudioChildren = emptyList() // Old-source track events cannot select a new child.
        archiveCaptionChildren = emptyMap()
        archiveProgrammeLabel = programme
        archiveCaptionSelected = false
        availableCaptionTracks = emptyList()
        archiveSelectedAudioUri = audioUri
        session.updateThermalStatus(VideoThermalBudget.effectiveStatus(archivePower?.currentThermalStatus ?: -1))
        outputCadence.composedSingle = true
        videoSuppressedByProtectedComposition = false
        videoOn = !remotePlaybackSuspended
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, remotePlaybackSuspended).build()
        try {
            // Keep the audio child indexes stable for native TrackSelectionOverride.
            val children = audioSources + captionChildren
            // A sparse or shorter text rendition must leave the full video timeline seekable.
            player.setMediaSource(MergingMediaSource(true, video, *children.toTypedArray()), positionMs.coerceAtLeast(0))
            parkedForArchive = false
            updateMemoryOwner()
            archiveAudioChildren = audioChildren
            archiveCaptionChildren = captionIndexes
            player.playWhenReady = playWhenReady && !remotePlaybackSuspended
            player.prepare()
            Log.i("PitwallPlayback", "ARCHIVE_AUDIO_SOURCE_READY stableChildren=${audioChildren.size}")
        } catch (failure: Throwable) {
            if (archiveVodSession === session) {
                archiveVodSession = previousArchive
                archiveSelectedAudioUri = previousAudio
                archiveAudioChildren = previousAudioChildren
                archiveCaptionChildren = previousCaptionChildren
                archiveProgrammeLabel = previousProgramme
                applyArchiveCaptionTrack(player.currentTracks)
                player.trackSelectionParameters = previousSelection
            }
            outputCadence.composedSingle =
                archiveVodSession != null || protectedLiveDashSource != null
            throw failure
        }
        // Media3's loader/source replacement must precede retiring old CMAF bytes.
        if (previousArchive !== session) previousArchive?.close()
    }

    /** Select a merged child using Media3's documented index-prefixed TrackGroup IDs. */
    private fun applyArchiveAudioTrack(uri: Uri, tracks: androidx.media3.common.Tracks): Boolean {
        if (archiveVodSession == null || archiveAudioChildren.size < 2) return false
        val child = archiveAudioChildren.indexOf(uri) + 1
        if (child <= 0) return false
        val candidates = tracks.groups.filter { group ->
            group.type == C.TRACK_TYPE_AUDIO &&
                ArchiveAudioSelectionPlan.childIndex(group.mediaTrackGroup.id) == child
        }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }
        val selected = candidates.firstOrNull { (group, index) -> group.isTrackSelected(index) }
            ?: candidates.firstOrNull { (group, index) ->
                (group.getTrackFormat(index).selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
            } ?: candidates.firstOrNull() ?: return false
        val override = TrackSelectionOverride(selected.first.mediaTrackGroup, selected.second)
        val previous = player.trackSelectionParameters
        if (previous.overrides[override.mediaTrackGroup] == override) return true
        player.trackSelectionParameters = previous.buildUpon().setOverrideForType(override).build()
        Log.i("PitwallPlayback", "ARCHIVE_AUDIO_TRACK_SELECTION child=$child videoSourceUnchanged=true")
        return true
    }

    private fun applyArchiveCaptionTrack(tracks: androidx.media3.common.Tracks) {
        val child = archiveCaptionChildren[archiveProgrammeLabel]
        val candidates = tracks.groups.filter {
            child != null && it.type == C.TRACK_TYPE_TEXT &&
                ArchiveAudioSelectionPlan.childIndex(it.mediaTrackGroup.id) == child
        }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }
        val formats = candidates.map { (group, index) -> group.getTrackFormat(index) }
        availableCaptionTracks = CaptionPolicy.tracks(formats.map { it.language to it.label })
        val preferred = if (captionsSelected && !remotePlaybackSuspended) CaptionPolicy.preferredIndex(
            selectedCaptionLanguage, captionCommentaryLanguage, formats.map { it.language },
            formats.map { it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 },
        ) else null
        val selected = preferred?.let(candidates::get)
        archiveCaptionSelected = selected?.let { (group, index) -> group.isTrackSelected(index) } == true
        val previous = player.trackSelectionParameters
        val next = previous.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selected == null).apply {
                selected?.let { (group, index) -> setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)) }
            }.build()
        if (previous != next) player.trackSelectionParameters = next
    }

    internal fun selectArchiveAudio(label: String): Boolean {
        val session = archiveVodSession ?: return false
        val audio = session.audioUris[label] ?: return false
        // Cast handback and repeated selection must not rebuild the same video
        // source, reset its decoder, lose the current CC choice or restart audio.
        if (archiveProgrammeLabel == label && player.playerError == null) return true
        if (audio in archiveAudioChildren && player.playerError == null) {
            val prior = archiveSelectedAudioUri
            archiveSelectedAudioUri = audio
            if (audio == prior || applyArchiveAudioTrack(audio, player.currentTracks)) {
                archiveProgrammeLabel = label
                archiveCaptionSelected = false
                applyArchiveCaptionTrack(player.currentTracks)
                return true
            }
            archiveSelectedAudioUri = prior
        }
        // A muxed, unverified or unsupported audio rendition must remain
        // selectable through the original native-audio handoff. Never pretend
        // its timeline or track group is verified.
        Log.i("PitwallPlayback", "ARCHIVE_AUDIO_SOURCE_HANDOFF reason=UNVERIFIED_OR_UNAVAILABLE_GROUP")
        startArchiveVodPlayback(session, player.currentPosition, player.playWhenReady, label)
        return true
    }

    internal fun releaseArchiveOwnership() {
        archiveVodSession?.close(); archiveVodSession = null
        archiveSelectedAudioUri = null
        archiveAudioChildren = emptyList()
        archiveCaptionChildren = emptyMap()
        archiveProgrammeLabel = null
        archiveCaptionSelected = false
        outputCadence.composedSingle = protectedLiveDashSource != null
    }

    internal fun prioritizeArchiveSeek(positionMs: Long) { archiveVodSession?.prioritizeSeek(positionMs) }
    internal fun updateArchiveThermalStatus(status: Int) { archiveVodSession?.updateThermalStatus(status) }

    /** Drop the original loaders/audio as well as video once all source decoders are released. */
    internal fun suspendForArchiveComposition() {
        parkedForArchive = true
        player.stop()
        player.clearMediaItems()
        memoryLoads.clearPending()
        playbackAllocator.trim()
        updateMemoryOwner()
        playbackEvent("archive-parked", "items=${player.mediaItemCount} allocatorBytes=${playbackAllocator.totalBytesAllocated}")
    }

    /** Validate total picture size/rate, not the number of source feeds, before handoff. */
    /**
     * Beyond the six-source validated path, prove the real picture fits a
     * hardware HEVC decoder before replacing source video. Secure Widevine
     * needs secure-capable hardware; authorized L3/clear needs normal output.
     */
    internal fun requireExtendedProtectedAtlasSupport(
        width: Int, height: Int, frameRate: Double, clearOutput: Boolean
    ): String {
        require(width in 1..4096 && height in 1..2176 &&
            frameRate.isFinite() && frameRate in 1.0..120.0) {
            "Extended HEVC picture dimensions or rate are outside bounded limits"
        }
        val needsSecure = !clearOutput && !usesSoftwareDrm
        val codec = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.isHardwareAccelerated &&
                supportsF1VideoMimeType(info.supportedTypes) &&
                runCatching {
                    val caps = info.getCapabilitiesForType(F1_VIDEO_MIME_TYPE)
                    val drmCompatible = if (needsSecure)
                        caps.isFeatureSupported(PlatformMediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                    else !caps.isFeatureRequired(PlatformMediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                    drmCompatible && caps.videoCapabilities?.areSizeAndRateSupported(width, height, frameRate) == true
                }.getOrDefault(false)
        }
        return requireNotNull(codec) {
            "No suitable hardware HEVC decoder advertises a protected eight-feed " +
                "picture of $width x $height at $frameRate fps"
        }.name
    }

    internal fun requireArchiveHardwareSupport(width: Int, height: Int, frameRate: Double): String {
        val codec = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.isHardwareAccelerated && supportsF1VideoMimeType(info.supportedTypes) &&
                runCatching {
                    val caps = info.getCapabilitiesForType(F1_VIDEO_MIME_TYPE)
                    !caps.isFeatureRequired(PlatformMediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) &&
                        caps.videoCapabilities?.areSizeAndRateSupported(width, height, frameRate) == true
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
    internal suspend fun publishProtectedComposedOutput(
        window: ProtectedHevcLiveComposition.OutputWindow,
        widevinePssh: ByteArray?
    ) {
        val source = protectedLiveDashSource
        if (source == null) {
            window.zero()
            widevinePssh?.fill(0)
            return
        }
        var publisherStarted = false
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                publisherStarted = true
                source.publish(window, widevinePssh)
            }
        } finally {
            if (!publisherStarted) window.zero()
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
        outputCadence.composedSingle = false
        val priorProtected = protectedLiveDashSource
        val priorArchive = archiveVodSession
        protectedLiveDashSource = null
        archiveVodSession = null
        archiveSelectedAudioUri = null
        archiveAudioChildren = emptyList()
        archiveCaptionChildren = emptyMap()
        archiveProgrammeLabel = null
        archiveCaptionSelected = false
        firstFrameRendered = false; error = null; problem = null
        videoSuppressedByProtectedComposition = false
        parkedForArchive = false
        val restorePositionMs = positionMs?.coerceAtLeast(0L)
            ?: initialStartPositionMs.takeIf { it != C.TIME_UNSET }
        try {
            if (restorePositionMs == null) {
                player.setMediaItem(originalMediaItem)
            } else {
                player.setMediaItem(originalMediaItem, restorePositionMs)
            }
            videoOn = enableVideo && !remotePlaybackSuspended
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setMaxVideoSize(qualityWidth(selectedVideoQuality), qualityHeight(selectedVideoQuality))
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enableVideo || remotePlaybackSuspended)
                .build()
            player.playWhenReady = playWhenReady && !remotePlaybackSuspended
            player.prepare()
        } finally {
            // Releases the old generations even if Media3 throws while restoring.
            try { priorProtected?.release() } finally { priorArchive?.close() }
        }
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
        refreshMissingQualityNotice()
        fit.qualityHeight = if (externalQualityFloor) maxOf(720, value.targetHeight) else value.targetHeight
        // The manual preference describes a source feed, not the composed atlas.
        // Remember it for source restoration without constraining the active atlas.
        if (archiveVodSession != null || protectedLiveDashSource != null) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(if (externalQualityFloor) maxOf(1280, qualityWidth(value)) else qualityWidth(value), if (externalQualityFloor) maxOf(720, qualityHeight(value)) else qualityHeight(value))
            .build()
        refresh()
    }

    /** External output requires a real HD rendition; never relabel an upscaled SD feed. */
    internal fun setExternalQualityFloor(enabled: Boolean) {
        if (enabled == externalQualityFloor) return
        externalQualityFloor = enabled
        val q = selectedVideoQuality
        fit.qualityHeight = if (enabled) maxOf(720, q.targetHeight) else q.targetHeight
        sourceTrackSelector.parameters = sourceTrackSelector.buildUponParameters()
            .setMinVideoSize(1, if (enabled) 720 else 0)
            .setMaxVideoSize(if (enabled) maxOf(1280, qualityWidth(q)) else qualityWidth(q),
                if (enabled) maxOf(720, qualityHeight(q)) else qualityHeight(q))
            .setExceedVideoConstraintsIfNecessary(!enabled)
            .build()
    }

    /** Keeps the picture going at the size it already has, whatever size its window is now. */
    fun keepVideo() {
        if (!videoOn) setVideo(true, viewW.takeIf { it > 1 } ?: 1920, viewH.takeIf { it > 1 } ?: 1080)
    }

    fun setAudible(audible: Boolean) {
        val audible = audible && !remotePlaybackSuspended
        if (audioTrackEnabled != audible) {
            audioTrackEnabled = audible
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audible)
                .build()
        }
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
        // The owner is terminal; repeated stop/remove/dispose must not release
        // Media3 (and its DRM session manager) a second time.
        if (!releaseGate.beginRelease()) return
        memoryOwnerRetired = true
        memoryLoads.clearPending()
        outputCadence.composedSingle = false
        outputDisplayOwner = null
        outputCadence.activeDisplayId = -1
        sceneCodecCorrelation.shutdown()
        val archive = archiveVodSession
        archiveVodSession = null
        archiveSelectedAudioUri = null
        archiveAudioChildren = emptyList()
        archiveCaptionChildren = emptyMap()
        archiveProgrammeLabel = null
        archiveCaptionSelected = false
        val protectedSource = protectedLiveDashSource
        protectedLiveDashSource = null
        // Media3 owns the codec producer and DRM session. Close it before
        // zeroing/releasing the DataSource's backing CMAF buffers. Closing the
        // source first raced in-flight Media3 reads and codec handoff during
        // rapid seeks / transitions. The finally chain still closes every
        // owned source even if a platform player release fails.
        try {
            player.release()
        } finally {
            try {
                Media3MemoryOwners.unregister(playbackAllocator)
                playbackAllocator.trim()
            } finally {
                try { archive?.close() } finally { protectedSource?.release() }
            }
        }
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
        // Network/DRM exceptions can include F1 token-bearing URLs and headers.
        // This text reaches Compose status, app logs and user-exported diagnostics.
        return DiagnosticRedaction.redact(
            "${root.javaClass.simpleName}: ${root.message}"
        ).take(220)
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
