package app.apexview.tiled

import android.net.Uri
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
import java.io.ByteArrayOutputStream
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
    private val maximumVideoHeight: Int = 2160,
    private val segmentTimeToleranceUs: Long = 250_000L,
) {
    data class Feed(
        val label: String,
        val manifestUrl: String,
    )

    data class Snapshot(
        val selectedWidth: Int?,
        val selectedHeight: Int?,
        val selectedCodecs: String?,
        val alignedSegmentTimeUs: Long?,
        val capturedFeeds: Int,
        val compositionStatus: String,
        val detail: String? = null,
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

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setAllowCrossProtocolRedirects(true)

    private val initializationByLabel = linkedMapOf<String, InitState>()
    private var lastCapturedSegmentStartUs: Long? = null

    init {
        require(userAgent.isNotBlank()) { "F1 media User-Agent is required" }
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
        require(feeds.size in setOf(2, 3, 4, 6)) {
            "Live HEVC composition needs 2, 3, 4 or 6 selected feeds"
        }
        require(feeds.map { it.label }.distinct().size == feeds.size &&
            feeds.all { it.label.isNotBlank() && it.manifestUrl.isNotBlank() }) {
            "Selected F1 feed labels and DASH URLs must be present and unique"
        }

        try {
            val loaded = coroutineScope {
                feeds.map { feed -> async { loadFeed(feed) } }.awaitAll()
            }
            val chosenSize = chooseCommonHevcSize(loaded)
                ?: throw IOException("F1 feeds do not expose a common HEVC rendition at or below ${maximumVideoHeight}p")
            val selected = loaded.map { item ->
                item.copy(
                    representation = item.representationFor(chosenSize.first, chosenSize.second)
                )
            }
            val selectedWithIndexes = selected.map { item ->
                val index = item.representation.index
                    ?: throw IOException("${item.feed.label}: DASH representation has no in-manifest segment index")
                val init = item.representation.initializationUri
                    ?: throw IOException("${item.feed.label}: DASH representation has no initialization segment")
                val base = item.representation.baseUrls.firstOrNull()?.url
                    ?: throw IOException("${item.feed.label}: DASH representation has no BaseURL")
                item.copy(index = index, initializationUri = init, baseUrl = base)
            }

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
                                    throw IOException(
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
            val aligned = findNewestAligned(candidates = candidateWindows)
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
                    detail = "Waiting for the next F1 segment",
                )
            }

            val media = coroutineScope {
                selectedWithIndexes.zip(aligned).map { (item, segment) ->
                    async {
                        val bytes = readBounded(
                            item,
                            item.index.getSegmentUrl(segment.number),
                            MAX_MEDIA_FRAGMENT_BYTES,
                            "media fragment"
                        )
                        val init = initializationByLabel[item.feed.label]
                            ?: throw IOException("${item.feed.label}: source initialization was not captured")
                        try {
                            val report = CmafBoxInspector.inspect(bytes, init.report)
                            if (report.errors.isNotEmpty() || report.initializationSegmentPresent ||
                                report.fragments.none { it.trackId == init.report.tracks.singleOrNull { t -> t.handler == "vide" }?.trackId }) {
                                throw IOException(
                                    "${item.feed.label}: invalid F1 video fragment: " +
                                        report.errors.joinToString("; ").take(180)
                                )
                            }
                            item.feed.label to bytes.also {
                                // Report is retained separately from encrypted sample bytes.
                                ProtectedHevcLiveComposition.recordCmafResponse(
                                    group, item.feed.label, it, report
                                )
                            }
                        } catch (error: Throwable) {
                            bytes.fill(0)
                            throw error
                        }
                    }
                }.awaitAll()
            }
            media.forEach { (_, bytes) -> bytes.fill(0) }
            lastCapturedSegmentStartUs = aligned.first().absoluteStartUs

            Snapshot(
                selectedWidth = chosenSize.first,
                selectedHeight = chosenSize.second,
                selectedCodecs = selectedWithIndexes.first().representation.format.codecs,
                alignedSegmentTimeUs = aligned.first().absoluteStartUs,
                capturedFeeds = media.size,
                compositionStatus = ProtectedHevcLiveComposition.status(group),
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
                detail = (error.message ?: error.javaClass.simpleName)
                    .replace(Regex("https?://\\S+"), "[feed URL]")
                    .take(240),
            )
        }
    }

    fun reset() {
        initializationByLabel.clear()
        lastCapturedSegmentStartUs = null
    }

    private suspend fun loadFeed(feed: Feed): LoadedFeed = withContext(Dispatchers.IO) {
        val lowerUrl = feed.manifestUrl.substringBefore('?').lowercase()
        if (lowerUrl.endsWith(".m3u8") || "HLS" in lowerUrl) {
            throw IOException("${feed.label}: this capture path requires F1 DASH offers, not HLS")
        }
        val dataSource = httpFactory.createDataSource()
        val manifest = try {
            DashUtil.loadManifest(dataSource, Uri.parse(feed.manifestUrl))
        } finally {
            runCatching { dataSource.close() }
        }
        if (manifest.getPeriodCount() == 0) {
            throw IOException("${feed.label}: F1 DASH manifest contains no Period")
        }
        val periodIndex = manifest.getPeriodCount() - 1
        val period = manifest.getPeriod(periodIndex)
        val candidates = period.adaptationSets
            .filter { it.type == C.TRACK_TYPE_VIDEO || it.representations.any(::isHevc) }
            .flatMap { it.representations }
            .filter(::isHevc)
            .filter { it.format.width > 0 && it.format.height in 1..maximumVideoHeight }
        if (candidates.isEmpty()) {
            throw IOException("${feed.label}: F1 DASH manifest has no supported HEVC video representation")
        }
        val preferred = candidates.maxWithOrNull(
            compareBy<Representation> { it.format.height }
                .thenBy { it.format.width }
                .thenBy { it.format.bitrate }
        ) ?: throw IOException("${feed.label}: no usable HEVC representation")
        val index = preferred.index
            ?: throw IOException("${feed.label}: HEVC representation uses an unsupported external index")
        val init = preferred.initializationUri
            ?: throw IOException("${feed.label}: HEVC representation has no initialization segment")
        val base = preferred.baseUrls.firstOrNull()?.url
            ?: throw IOException("${feed.label}: HEVC representation has no BaseURL")
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

    private fun chooseCommonHevcSize(feeds: List<LoadedFeed>): Pair<Int, Int>? {
        val common = feeds.map { loaded ->
            loaded.period.adaptationSets
                .filter { it.type == C.TRACK_TYPE_VIDEO || it.representations.any(::isHevc) }
                .flatMap { it.representations }
                .filter(::isHevc)
                .map { it.format.width to it.format.height }
                .filter { (width, height) -> width > 0 && height in 1..maximumVideoHeight }
                .toSet()
        }.reduce { left, right -> left intersect right }
        return common.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
    }

    private fun LoadedFeed.representationFor(width: Int, height: Int): Representation {
        return period.adaptationSets
            .filter { it.type == C.TRACK_TYPE_VIDEO || it.representations.any(::isHevc) }
            .flatMap { it.representations }
            .filter(::isHevc)
            .filter { it.format.width == width && it.format.height == height }
            .maxByOrNull { it.format.bitrate }
            ?: throw IOException("${feed.label}: common HEVC rendition disappeared during selection")
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
        if (count == DashSegmentIndex.INDEX_UNBOUNDED || count <= 0L) {
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
        candidates: List<List<SegmentCandidate>>
    ): List<SegmentCandidate>? {
        if (candidates.size < 2 || candidates.any { it.isEmpty() }) return null
        val master = candidates.first().asReversed()
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

    private fun readDataSpec(dataSpec: DataSpec, maximumBytes: Int, context: String): ByteArray {
        val dataSource: DataSource = httpFactory.createDataSource()
        try {
            val length = dataSource.open(dataSpec)
            if (length > maximumBytes.toLong()) {
                throw IOException("$context exceeds the ${maximumBytes / (1024 * 1024)} MB capture limit")
            }
            val output = ByteArrayOutputStream(if (length in 1..maximumBytes.toLong()) length.toInt() else 64 * 1024)
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val count = dataSource.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                if (count <= 0) continue
                total = Math.addExact(total, count)
                if (total > maximumBytes) {
                    throw IOException("$context exceeds the configured capture limit")
                }
                output.write(buffer, 0, count)
            }
            if (total < 8) throw IOException("$context is too small to contain a CMAF box")
            return output.toByteArray()
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private companion object {
        const val RECENT_SEGMENT_WINDOW = 6L
        const val MAX_INITIALIZATION_BYTES = 2 * 1024 * 1024
        const val MAX_MEDIA_FRAGMENT_BYTES = 8 * 1024 * 1024
    }
}
