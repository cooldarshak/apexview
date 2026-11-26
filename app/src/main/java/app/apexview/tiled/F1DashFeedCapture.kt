package app.apexview.tiled

import android.net.Uri
import app.apexview.ManifestProtocol
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import java.io.ByteArrayInputStream
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.Representation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import java.util.concurrent.Executors
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.abs

/**
 * Acquires real CMAF inputs from the per-channel DASH URLs returned by F1's single multifeed
 * PLAY session. This deliberately uses Media3 DataSource + DashManifest/DashSegmentIndex only:
 * it does not create an ExoPlayer, MediaCodec, Surface, or additional DRM session.
 *
 * The selected protected fragments are handed to [ProtectedHevcLiveComposition]. That component
 * validates the actual CMAF track, HEVC tile metadata, CBCS protection, picture order and timing
 * before producing any output. No clear samples or DRM keys are requested here.
 *
 * F1 can publish the newest segment before every CDN edge has it. We therefore consider a small
 * recent window and only fetch a segment when every selected feed has a presentation-time match.
 */
@OptIn(UnstableApi::class)
internal class F1DashFeedCapture(
    userAgent: String,
    ascendon: String,
    entitlement: String,
    private val maximumVideoHeight: Int = 2160,
    private val segmentTimeToleranceUs: Long = 250_000L,
    dataSourceFactory: DataSource.Factory? = null,
    private val minimumVideoHeight: Int = 0,
    private val autoPreferredHeight: Int? = null,
    private val autoBandwidthBps: Long? = null,
) : CmafCaptureLifecycle {
    data class Feed(
        val label: String,
        val manifestUrl: String,
        /** Per-feed resolution ceiling. Null retains the existing common-rendition selection. */
        val preferredHeight: Int? = null,
    )

    data class Snapshot(
        val selectedWidth: Int?,
        val selectedHeight: Int?,
        val selectedCodecs: String?,
        val alignedSegmentTimeUs: Long?,
        val capturedFeeds: Int,
        val compositionStatus: String,
        val detail: String? = null,
        val failure: CaptureFailure? = null,
        val actualManifests: Map<String, ManifestProtocol> = emptyMap(),
        val outputTiming: CmafWindowTiming? = null,
    )

    private data class LoadedFeed(
        val feed: Feed,
        val manifest: DashManifest,
        val period: Period,
        val periodIndex: Int,
        val representation: Representation,
        val index: DashSegmentIndex,
        val baseUrl: String,
        val initializationUri: androidx.media3.exoplayer.dash.manifest.RangedUri,
        val periodDurationUs: Long,
    )

    private data class SegmentCandidate(
        val number: Long,
        val absoluteStartUs: Long,
    )

    private data class InitState(
        val key: String,
        val report: CmafBoxInspector.Report,
    )

    private val httpFactory: DataSource.Factory = dataSourceFactory ?: DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setAllowCrossProtocolRedirects(false)
        .setDefaultRequestProperties(
            mapOf(
                "ascendontoken" to ascendon,
                "entitlementtoken" to entitlement,
            )
        )

    private val reader = CmafHttpReader(httpFactory)
    private val compositionDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            // Active rolling composition is playback-critical: prefer it over
            // speculative warmup, but below UI and the display-priority GL thread.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + 2)
            task.run()
        }, "PitwallDashHevcComposition").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val actualProtocols = java.util.concurrent.ConcurrentHashMap<String, ManifestProtocol>()
    private val initializationByLabel = java.util.concurrent.ConcurrentHashMap<String, InitState>()
    @Volatile private var latestWidevinePssh: ByteArray? = null
    private var lastCapturedSegmentStartUs: Long? = null

    init {
        require(userAgent.isNotBlank()) { "F1 media User-Agent is required" }
        require(ascendon.isNotBlank() && entitlement.isNotBlank()) {
            "F1 media authorization tokens are required for DASH segment capture"
        }
        require(maximumVideoHeight > 0) { "Maximum HEVC rendition height must be positive" }
        require(segmentTimeToleranceUs in 1..1_000_000L) {
            "DASH feed alignment tolerance is outside the supported range"
        }
    }

    /**
     * Fetches one newest common CMAF segment from all selected F1 feeds.
     *
     * This method is safe to call repeatedly from a single background coroutine. Manifest refresh,
     * CDN retries, and composition are intentionally serialized per instance so two polls cannot
     * overwrite a feed's initialization or process one live segment twice.
     */
    suspend fun captureNext(group: String, feeds: List<Feed>): Snapshot = withContext(Dispatchers.IO) {
        require(group.isNotBlank()) { "Composition group is required" }
        require(feeds.size in 2..8) {
            "Live protected HEVC composition supports two to six selected feeds"
        }
        require(feeds.map { it.label }.distinct().size == feeds.size &&
            feeds.all { it.label.isNotBlank() && it.manifestUrl.isNotBlank() }) {
            "Selected F1 feed labels and DASH URLs must be present and unique"
        }

        try {
            val loaded = coroutineScope {
                feeds.map { feed -> async { loadFeed(feed) } }.awaitAll()
            }
            val chosen = chooseRequestedRepresentations(loaded)
            val chosenSize = chosen.first().format.let { it.width to it.height }
            val selected = loaded.zip(chosen).map { (item, representation) ->
                item.copy(representation = representation)
            }
            val selectedWithIndexes = selected.map { item ->
                val index = item.representation.index
                    ?: throw PermanentCaptureException("${item.feed.label}: DASH representation has no in-manifest segment index")
                val init = item.representation.initializationUri
                    ?: throw PermanentCaptureException("${item.feed.label}: DASH representation has no initialization segment")
                val base = item.representation.baseUrls.firstOrNull()?.url
                    ?: throw PermanentCaptureException("${item.feed.label}: DASH representation has no BaseURL")
                item.copy(index = index, initializationUri = init, baseUrl = base)
            }
            selectedWithIndexes.forEach { rememberWidevinePssh(it.representation.format) }

            // Install/refresh all source initialization segments before accepting media samples.
            coroutineScope {
                selectedWithIndexes.map { item ->
                    async {
                        val initKey = initializationKey(item)
                        if (initializationByLabel[item.feed.label]?.key != initKey) {
                            val bytes = readBounded(
                                item,
                                item.initializationUri,
                                MAX_INITIALIZATION_BYTES,
                                "initialization"
                            )
                            try {
                                val report = CmafBoxInspector.inspect(bytes)
                                if (report.errors.isNotEmpty() || !report.initializationSegmentPresent) {
                                    throw PermanentCaptureException(
                                        "${item.feed.label}: invalid F1 CMAF initialization: " +
                                            report.errors.joinToString("; ").take(180)
                                    )
                                }
                                ProtectedHevcLiveComposition.recordCmafResponse(
                                    group, item.feed.label, bytes, report
                                )
                                initializationByLabel[item.feed.label] = InitState(initKey, report)
                            } finally {
                                bytes.fill(0)
                            }
                        }
                    }
                }.awaitAll()
            }

            val candidateWindows = selectedWithIndexes.map { item -> recentSegments(item) }
            val aligned = findNewestAligned(candidates = candidateWindows, afterUs = lastCapturedSegmentStartUs)
                ?: throw IOException("Waiting for a common published F1 HEVC segment across selected feeds")
            val previous = lastCapturedSegmentStartUs
            if (previous != null && aligned.first().absoluteStartUs <= previous) {
                return@withContext Snapshot(
                    selectedWidth = chosenSize.first,
                    selectedHeight = chosenSize.second,
                    selectedCodecs = selectedWithIndexes.first().representation.format.codecs,
                    alignedSegmentTimeUs = aligned.first().absoluteStartUs,
                    capturedFeeds = 0,
                    compositionStatus = ProtectedHevcLiveComposition.status(group),
                    actualManifests = actualProtocols.toMap(),
                    detail = "Waiting for the next F1 segment",
                )
            }

            val media = captureAlignedMedia(group, selectedWithIndexes, aligned, archive = false)
            lastCapturedSegmentStartUs = aligned.first().absoluteStartUs

            Snapshot(
                selectedWidth = chosenSize.first,
                selectedHeight = chosenSize.second,
                selectedCodecs = selectedWithIndexes.first().representation.format.codecs,
                alignedSegmentTimeUs = aligned.first().absoluteStartUs,
                capturedFeeds = media.size,
                compositionStatus = ProtectedHevcLiveComposition.status(group),
                actualManifests = actualProtocols.toMap(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Snapshot(
                selectedWidth = null,
                selectedHeight = null,
                selectedCodecs = null,
                alignedSegmentTimeUs = null,
                capturedFeeds = 0,
                compositionStatus = ProtectedHevcLiveComposition.status(group),
                actualManifests = actualProtocols.toMap(),
                detail = CaptureFailure.from(error).reason.take(240),
                failure = CaptureFailure.from(error),
            )
        }
    }

    /**
     * Captures the next aligned encrypted CMAF segment at the current VOD playback position.
     * Unlike [captureNext], this does not choose the newest segment in the manifest, which would
     * jump an archive straight to its final segment. Calls are serialized by the owning test job.
     */
    suspend fun captureAtPosition(
        group: String,
        feeds: List<Feed>,
        playbackPositionMs: Long
    ): Snapshot = withContext(Dispatchers.IO) {
        require(group.isNotBlank()) { "Composition group is required" }
        require(feeds.size in 2..8) {
            "Protected archive HEVC composition supports two to six selected feeds"
        }
        require(playbackPositionMs >= 0L) { "Archive playback position cannot be negative" }
        require(feeds.map { it.label }.distinct().size == feeds.size &&
            feeds.all { it.label.isNotBlank() && it.manifestUrl.isNotBlank() }) {
            "Selected F1 feed labels and DASH URLs must be present and unique"
        }

        try {
            val loaded = coroutineScope {
                feeds.map { feed -> async { loadFeed(feed, playbackPositionMs) } }.awaitAll()
            }
            val chosen = chooseRequestedRepresentations(loaded)
            val chosenSize = chosen.first().format.let { it.width to it.height }
            val selectedWithIndexes = loaded.zip(chosen).map { (item, representation) ->
                val index = representation.index
                    ?: throw PermanentCaptureException("${item.feed.label}: archive DASH representation has no in-manifest segment index")
                val init = representation.initializationUri
                    ?: throw PermanentCaptureException("${item.feed.label}: archive DASH representation has no initialization segment")
                val base = representation.baseUrls.firstOrNull()?.url
                    ?: throw PermanentCaptureException("${item.feed.label}: archive DASH representation has no BaseURL")
                item.copy(
                    representation = representation,
                    index = index,
                    initializationUri = init,
                    baseUrl = base
                )
            }
            selectedWithIndexes.forEach { rememberWidevinePssh(it.representation.format) }

            coroutineScope {
                selectedWithIndexes.map { item ->
                    async {
                        val initKey = initializationKey(item)
                        if (initializationByLabel[item.feed.label]?.key != initKey) {
                            val bytes = readBounded(
                                item,
                                item.initializationUri,
                                MAX_INITIALIZATION_BYTES,
                                "archive initialization"
                            )
                            try {
                                val report = CmafBoxInspector.inspect(bytes)
                                if (report.errors.isNotEmpty() || !report.initializationSegmentPresent) {
                                    throw PermanentCaptureException(
                                        "${item.feed.label}: invalid archive CMAF initialization: " +
                                            report.errors.joinToString("; ").take(180)
                                    )
                                }
                                ProtectedHevcLiveComposition.recordCmafResponse(
                                    group, item.feed.label, bytes, report
                                )
                                initializationByLabel[item.feed.label] = InitState(initKey, report)
                            } finally {
                                bytes.fill(0)
                            }
                        }
                    }
                }.awaitAll()
            }

            val candidateWindows = selectedWithIndexes.map { item ->
                archiveSegmentsNearPosition(item, playbackPositionMs)
            }
            val previous = lastCapturedSegmentStartUs
            val aligned = ArchiveDashSegmentSelector.selectAligned(
                candidateWindows = candidateWindows.map { window ->
                    window.map { ArchiveDashSegmentSelector.Candidate(it.number, it.absoluteStartUs) }
                },
                targetTimeUs = Math.multiplyExact(playbackPositionMs, 1_000L),
                previousStartUs = previous,
                toleranceUs = segmentTimeToleranceUs,
            )?.map { SegmentCandidate(it.number, it.absoluteStartUs) }
                ?: return@withContext Snapshot(
                    selectedWidth = chosenSize.first,
                    selectedHeight = chosenSize.second,
                    selectedCodecs = selectedWithIndexes.first().representation.format.codecs,
                    alignedSegmentTimeUs = null,
                    capturedFeeds = 0,
                    compositionStatus = ProtectedHevcLiveComposition.status(group),
                    actualManifests = actualProtocols.toMap(),
                    detail = if (previous == null) "Fragment alignment failure at the current archive position"
                        else "Waiting for the next contiguous archive segment",
                    failure = if (previous == null) CaptureFailure("Fragment alignment failure at the current archive position", false) else null,
                )

            val media = captureAlignedMedia(group, selectedWithIndexes, aligned, archive = true)
            ProtectedHevcLiveComposition.rejectionReason(group)?.let {
                throw PermanentCaptureException("HEVC/CMAF composition rejected: $it")
            }
            lastCapturedSegmentStartUs = aligned.first().absoluteStartUs

            Snapshot(
                selectedWidth = chosenSize.first,
                selectedHeight = chosenSize.second,
                selectedCodecs = selectedWithIndexes.first().representation.format.codecs,
                alignedSegmentTimeUs = aligned.first().absoluteStartUs,
                capturedFeeds = media.size,
                compositionStatus = ProtectedHevcLiveComposition.status(group),
                actualManifests = actualProtocols.toMap(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Snapshot(
                selectedWidth = null,
                selectedHeight = null,
                selectedCodecs = null,
                alignedSegmentTimeUs = null,
                capturedFeeds = 0,
                compositionStatus = ProtectedHevcLiveComposition.status(group),
                actualManifests = actualProtocols.toMap(),
                detail = CaptureFailure.from(error).reason.take(240),
                failure = CaptureFailure.from(error),
            )
        }
    }

    private fun archiveSegmentsNearPosition(
        feed: LoadedFeed,
        playbackPositionMs: Long
    ): List<SegmentCandidate> {
        val durationUs = feed.periodDurationUs
        if (feed.manifest.dynamic) {
            return LiveDashSegmentWindow.near(feed.index, durationUs, feed.period.startMs * 1000,
                System.currentTimeMillis() * 1000, playbackPositionMs * 1000,
                RECENT_SEGMENT_WINDOW.toLong(), ARCHIVE_LOOKAHEAD_SEGMENTS.toLong())
                .map { SegmentCandidate(it.number, it.absoluteStartUs) }
        }
        if (durationUs <= 0L || durationUs == C.TIME_UNSET) {
            throw PermanentCaptureException("${feed.feed.label}: archive DASH Period has no finite duration")
        }
        val index = feed.index
        val count = index.getSegmentCount(durationUs)
        if (count <= 0L || count == DashSegmentIndex.INDEX_UNBOUNDED.toLong()) {
            throw PermanentCaptureException("${feed.feed.label}: archive DASH index is not a finite VOD index")
        }
        val first = index.firstSegmentNum
        val last = Math.addExact(first, count - 1L)
        val periodStartUs = Math.multiplyExact(feed.period.startMs, 1_000L)
        val absolutePositionUs = Math.multiplyExact(playbackPositionMs, 1_000L)
        val positionInPeriodUs = absolutePositionUs - periodStartUs
        if (positionInPeriodUs < 0L || positionInPeriodUs >= durationUs) {
            throw PermanentCaptureException(
                "${feed.feed.label}: current playback position is outside the final DASH Period"
            )
        }
        val current = index.getSegmentNum(positionInPeriodUs, durationUs).coerceIn(first, last)
        val begin = maxOf(first, current - RECENT_SEGMENT_WINDOW)
        val end = minOf(last, current + ARCHIVE_LOOKAHEAD_SEGMENTS)
        return (begin..end).map { number ->
            SegmentCandidate(
                number = number,
                absoluteStartUs = Math.addExact(periodStartUs, index.getTimeUs(number))
            )
        }
    }

    private suspend fun captureAlignedMedia(
        group: String,
        feeds: List<LoadedFeed>,
        aligned: List<SegmentCandidate>,
        archive: Boolean
    ): List<String> {
        require(feeds.size == aligned.size)
        val owned = arrayOfNulls<ByteArray>(feeds.size)
        val started = android.os.SystemClock.elapsedRealtime()
        val forecast = SpeculativeMemoryAdmission.process.beginForeground(
            SpeculativeMemoryAdmission.sourceForecast(feeds.size, MAX_MEDIA_FRAGMENT_BYTES))
        try {
            // Network requests run concurrently without blocking the global
            // encrypted-composition critical section or video display thread.
            coroutineScope {
                feeds.indices.map { index ->
                    async {
                        val feed = feeds[index]
                        owned[index] = readBounded(
                            feed, feed.index.getSegmentUrl(aligned[index].number),
                            MAX_MEDIA_FRAGMENT_BYTES,
                            if (archive) "archive media fragment" else "media fragment"
                        )
                    }
                }.awaitAll()
            }
            val fetchMs = android.os.SystemClock.elapsedRealtime() - started
            val composedStarted = android.os.SystemClock.elapsedRealtime()
            val labels = withContext(compositionDispatcher) {
                feeds.indices.map { index ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val feed = feeds[index]
                    val bytes = requireNotNull(owned[index])
                    val init = initializationByLabel[feed.feed.label]
                        ?: throw PermanentCaptureException(
                            "${feed.feed.label}: source initialization was not captured")
                    val report = CmafBoxInspector.inspect(bytes, init.report)
                    if (report.errors.isNotEmpty() || report.initializationSegmentPresent ||
                        report.fragments.none { fragment ->
                            fragment.trackId == init.report.tracks
                                .singleOrNull { it.handler == "vide" }?.trackId
                        }) throw PermanentCaptureException(
                            "${feed.feed.label}: invalid F1 HEVC fragment: " +
                            report.errors.joinToString("; ").take(180))
                    ProtectedHevcLiveComposition.recordCmafResponse(
                        group, feed.feed.label, bytes, report)
                    feed.feed.label
                }
            }
            val composeMs = android.os.SystemClock.elapsedRealtime() - composedStarted
            android.util.Log.i("ApexviewSingleDecoder",
                "DASH_CMAF_CAPTURE feeds=${feeds.size} protection=CBCS " +
                "archive=$archive ioParallel=true compositionWorker=BELOW_UI_PRIORITY " +
                "fetchMs=$fetchMs composeMs=$composeMs " +
                "sourceBytes=${owned.sumOf { it?.size?.toLong() ?: 0L }}")
            return labels
        } finally {
            owned.forEach { it?.fill(0) }
            forecast.close()
        }
    }

    /** Defensive copy of the selected DASH representation's Widevine initialization data. */
    fun widevinePssh(): ByteArray? = latestWidevinePssh?.copyOf()

    private fun rememberWidevinePssh(format: Format) {
        val drmInitData = format.drmInitData ?: return
        val schemeData = (0 until drmInitData.schemeDataCount)
            .asSequence()
            .map(drmInitData::get)
            .firstOrNull { it.uuid == C.WIDEVINE_UUID && it.data != null }
            ?: return
        synchronized(this) {
            if (latestWidevinePssh == null) {
                latestWidevinePssh = schemeData.data?.copyOf()
            }
        }
    }

    override fun reset() {
        initializationByLabel.clear()
        actualProtocols.clear()
        lastCapturedSegmentStartUs = null
        latestWidevinePssh?.fill(0)
        latestWidevinePssh = null
        compositionDispatcher.close()
    }

    private suspend fun loadFeed(feed: Feed, playbackPositionMs: Long? = null): LoadedFeed = withContext(Dispatchers.IO) {
        var manifestUri = Uri.parse(feed.manifestUrl)
        val bytes = readDataSpec(DataSpec(manifestUri), MAX_MANIFEST_BYTES, "${feed.label} manifest") {
            manifestUri = it
        }
        val protocol = ManifestProtocol.fromBody(bytes)
        actualProtocols[feed.label] = protocol
        android.util.Log.i("ApexviewPlaybackOffer", "${feed.label}: actualManifest=$protocol")
        val manifest = try {
            when (protocol) {
                ManifestProtocol.HLS -> throw PermanentCaptureException(
                    "${feed.label}: actual manifest is HLS; DASH source unavailable in this PLAY session"
                )
                ManifestProtocol.UNKNOWN -> throw PermanentCaptureException("${feed.label}: response is not a DASH manifest")
                ManifestProtocol.DASH -> DashManifestParser().parse(manifestUri, ByteArrayInputStream(bytes))
            }
        } finally { bytes.fill(0) }
        if (manifest.getPeriodCount() == 0) {
            throw PermanentCaptureException("${feed.label}: F1 DASH manifest contains no Period")
        }
        val periodIndex = if (playbackPositionMs == null) manifest.getPeriodCount() - 1 else {
            (0 until manifest.getPeriodCount()).lastOrNull { manifest.getPeriod(it).startMs <= playbackPositionMs }
                ?: throw PermanentCaptureException("${feed.label}: archive position precedes the DASH Period")
        }
        val period = manifest.getPeriod(periodIndex)
        val candidates = period.adaptationSets
            .filter { it.type == C.TRACK_TYPE_VIDEO || it.representations.any(::isHevc) }
            .flatMap { it.representations }
            .filter(::isHevc)
            .filter { it.format.width > 0 && it.format.height in maxOf(1, minimumVideoHeight)..maximumVideoHeight }
        if (candidates.isEmpty()) {
            throw PermanentCaptureException("${feed.label}: F1 DASH manifest has no supported HEVC video representation")
        }
        val preferred = candidates.maxWithOrNull(
            compareBy<Representation> { it.format.height }
                .thenBy { it.format.width }
                .thenBy { it.format.bitrate }
        ) ?: throw PermanentCaptureException("${feed.label}: no usable HEVC representation")
        val index = preferred.index
            ?: throw PermanentCaptureException("${feed.label}: HEVC representation uses an unsupported external index")
        val init = preferred.initializationUri
            ?: throw PermanentCaptureException("${feed.label}: HEVC representation has no initialization segment")
        val base = preferred.baseUrls.firstOrNull()?.url
            ?: throw PermanentCaptureException("${feed.label}: HEVC representation has no BaseURL")
        LoadedFeed(
            feed = feed,
            manifest = manifest,
            period = period,
            periodIndex = periodIndex,
            representation = preferred,
            index = index,
            baseUrl = base,
            initializationUri = init,
            periodDurationUs = manifest.getPeriodDurationUs(periodIndex),
        )
    }

    /**
     * Keep real user-selected resolutions for protected feeds. Merely selecting a rendition
     * is not permission to stitch ciphertext: the downstream composer independently
     * checks source HEVC tiles, KID, IV and every encrypted subsample range.
     */
    private fun chooseRequestedRepresentations(feeds: List<LoadedFeed>): List<Representation> {
        val caps = feeds.map { it.feed.preferredHeight?.takeIf { cap -> cap > 0 } }
        if (caps.any { it != null && it > maximumVideoHeight })
            throw PermanentCaptureException("Requested manual quality exceeds the supported capture limit of ${maximumVideoHeight}p")
        if (caps.distinct().size <= 1) {
            val common = chooseCommonHevcSize(feeds, caps.firstOrNull())
                ?: throw PermanentCaptureException("Selected feeds expose no common HEVC rendition within the decoder atlas")
            return feeds.map { it.representationFor(common.first, common.second) }
        }
        val selected = feeds.map { loaded ->
            val cap = minOf(maximumVideoHeight,
                loaded.feed.preferredHeight?.takeIf { it > 0 } ?: maximumVideoHeight)
            loaded.period.adaptationSets
                .filter { it.type == C.TRACK_TYPE_VIDEO || it.representations.any(::isHevc) }
                .flatMap { it.representations }
                .filter(::isHevc)
                .filter { it.format.width > 0 &&
                    it.format.height in maxOf(1, minimumVideoHeight)..cap }
                .maxWithOrNull(compareBy<Representation> { it.format.height }
                    .thenBy { it.format.width }.thenBy { it.format.bitrate })
                ?: throw PermanentCaptureException("${loaded.feed.label}: no authorized HEVC rendition at or below ${cap}p")
        }
        android.util.Log.i("ApexviewSingleDecoder", "PROTECTED_SOURCE_RENDITIONS " +
            selected.mapIndexed { index, rep ->
                "${feeds[index].feed.label}=${rep.format.width}x${rep.format.height}"
            }.joinToString(", ") + " cbcsGate=REQUIRED decoderCount=ONE")
        return selected
    }

    private fun chooseCommonHevcSize(feeds: List<LoadedFeed>, manualHeight: Int?): Pair<Int, Int>? {
        val common = feeds.map { loaded ->
            loaded.period.adaptationSets
                .filter { it.type == C.TRACK_TYPE_VIDEO || it.representations.any(::isHevc) }
                .flatMap { it.representations }
                .filter(::isHevc)
                .map { it.format.width to it.format.height }
                .filter { (width, height) -> width > 0 && height in maxOf(1, minimumVideoHeight)..maximumVideoHeight }
                .toSet()
        }.reduce { left, right -> left intersect right }
        if (manualHeight != null) {
            if (feeds.all { loaded -> loaded.period.adaptationSets
                    .flatMap { it.representations }.any { isHevc(it) && it.format.height == manualHeight } } &&
                common.none { it.second == manualHeight })
                throw PermanentCaptureException("Requested ${manualHeight}p feeds have incompatible advertised geometries or capture bounds")
            return HevcArchiveRenditionPlan.chooseManual(common, manualHeight, minimumVideoHeight, feeds.size, 4096)
        }
        // Existing hard capture bounds and compatibility remain authoritative.
        // A soft Auto preference only applies to the common route.
        val preferred = autoPreferredHeight?.coerceIn(1, maximumVideoHeight)
        val costs = if (autoBandwidthBps != null) HevcArchiveRenditionPlan.aggregateBitrates(feeds.map { loaded ->
            loaded.period.adaptationSets
                .filter { it.type == C.TRACK_TYPE_VIDEO || it.representations.any(::isHevc) }
                .flatMap { it.representations }.filter(::isHevc)
                .map { Triple(it.format.width, it.format.height, it.format.bitrate) }
        }) else emptyMap()
        return if (maximumVideoHeight <= 1080 || minimumVideoHeight > 0 || preferred != null || autoBandwidthBps != null)
            HevcArchiveRenditionPlan.choose(common, preferred ?: maximumVideoHeight, minimumVideoHeight, feeds.size, 4096,
                costs, autoBandwidthBps)
        else common.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
    }

    private fun LoadedFeed.representationFor(width: Int, height: Int): Representation {
        return period.adaptationSets
            .filter { it.type == C.TRACK_TYPE_VIDEO || it.representations.any(::isHevc) }
            .flatMap { it.representations }
            .filter(::isHevc)
            .filter { it.format.width == width && it.format.height == height }
            .maxByOrNull { it.format.bitrate }
            ?: throw PermanentCaptureException("${feed.label}: common HEVC rendition disappeared during selection")
    }

    private fun isHevc(representation: Representation): Boolean {
        val format = representation.format
        return format.sampleMimeType == MimeTypes.VIDEO_H265 ||
            format.codecs?.startsWith("hev1", ignoreCase = true) == true ||
            format.codecs?.startsWith("hvc1", ignoreCase = true) == true
    }

    private fun initializationKey(feed: LoadedFeed): String = listOf(
        feed.feed.manifestUrl.substringBefore('?'),
        feed.periodIndex.toString(),
        feed.representation.format.id.orEmpty(),
        feed.representation.format.width.toString(),
        feed.representation.format.height.toString(),
        feed.baseUrl,
        feed.initializationUri.toString(),
    ).joinToString("|")

    private fun recentSegments(feed: LoadedFeed): List<SegmentCandidate> {
        val index = feed.index
        val periodDurationUs = feed.periodDurationUs
        var first = index.firstSegmentNum
        var count = index.getSegmentCount(periodDurationUs)
        if (count == DashSegmentIndex.INDEX_UNBOUNDED.toLong() || count <= 0L) {
            val nowUnixTimeUs = System.currentTimeMillis() * 1_000L
            first = index.getFirstAvailableSegmentNum(periodDurationUs, nowUnixTimeUs)
            count = index.getAvailableSegmentCount(periodDurationUs, nowUnixTimeUs)
        }
        if (count <= 0L) return emptyList()
        val last = Math.addExact(first, count - 1L)
        val begin = maxOf(first, last - RECENT_SEGMENT_WINDOW + 1L)
        return (begin..last).map { number ->
            SegmentCandidate(
                number = number,
                absoluteStartUs = Math.addExact(
                    feed.period.startMs * 1_000L,
                    index.getTimeUs(number)
                )
            )
        }
    }

    private fun findNewestAligned(
        candidates: List<List<SegmentCandidate>>, afterUs: Long? = null,
    ): List<SegmentCandidate>? {
        if (candidates.size < 2 || candidates.any { it.isEmpty() }) return null
        val master = if (afterUs == null) candidates.first().asReversed() else candidates.first().filter { it.absoluteStartUs > afterUs }
        for (candidate in master) {
            val match = candidates.drop(1).map { window ->
                window.minByOrNull { abs(it.absoluteStartUs - candidate.absoluteStartUs) }
            }
            if (match.all { it != null && abs(it.absoluteStartUs - candidate.absoluteStartUs) <= segmentTimeToleranceUs }) {
                return listOf(candidate) + match.filterNotNull()
            }
        }
        return null
    }

    private suspend fun readBounded(
        feed: LoadedFeed,
        range: androidx.media3.exoplayer.dash.manifest.RangedUri,
        maximumBytes: Int,
        kind: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        val dataSpec = DashUtil.buildDataSpec(
            feed.representation,
            feed.baseUrl,
            range,
            0,
            emptyMap(),
        )
        readDataSpec(dataSpec, maximumBytes, "${feed.feed.label} $kind")
    }

    private fun readDataSpec(
        dataSpec: DataSpec, maximumBytes: Int, context: String, onOpened: (Uri) -> Unit = {}
    ): ByteArray {
        val response = reader.read(dataSpec, maximumBytes, verifyEnd = true)
        if (response.bytes.size < 8) {
            response.bytes.fill(0)
            throw PermanentCaptureException("$context is too small to contain a CMAF box")
        }
        onOpened(response.uri)
        return response.bytes
    }

    private companion object {
        const val RECENT_SEGMENT_WINDOW = 6L
        const val ARCHIVE_LOOKAHEAD_SEGMENTS = 3L
        const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        const val MAX_INITIALIZATION_BYTES = 2 * 1024 * 1024
        const val MAX_MEDIA_FRAGMENT_BYTES = 8 * 1024 * 1024
    }
}
