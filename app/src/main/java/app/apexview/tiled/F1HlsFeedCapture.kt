package app.apexview.tiled

import android.net.Uri
import app.apexview.ManifestProtocol
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import java.util.concurrent.Executors
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import kotlin.coroutines.coroutineContext

/** Keep archive probing bounded on lower-memory TVs while retaining useful I/O concurrency. */
private suspend fun <T, R> mapArchiveFeedsBounded(
    items: List<T>,
    concurrency: Int = 4,
    block: suspend (T) -> R,
): List<R> = coroutineScope {
    val permits = Semaphore(concurrency.coerceAtLeast(1))
    items.map { item -> async { permits.withPermit { block(item) } } }.awaitAll()
}

/** Reads the HLS/CMAF actually supplied by player_tm, without another PLAY or media player. */
internal class F1HlsFeedCapture(factory: DataSource.Factory, private val maximumHeight: Int = 2160,
                                private val maximumOutputWidth: Int = 4096, private val minimumCaptureHeight: Int = 0, fullPictures: Boolean = minimumCaptureHeight > 0) {
    private data class Offer(val feed: F1DashFeedCapture.Feed, val uri: Uri, val format: Format?, val master: HlsMultivariantPlaylist?, val audioGroupId: String? = null)
    private class Init(val uri: Uri, val offset: Long, val length: Long, val bytes: ByteArray, val report: CmafBoxInspector.Report)
    private val reader = CmafHttpReader(factory)
    private val offers = java.util.concurrent.ConcurrentHashMap<String, List<Offer>>()
    private val vodPlaylists = java.util.concurrent.ConcurrentHashMap<String, HlsMediaPlaylist>()
    private val initializations = java.util.concurrent.ConcurrentHashMap<String, Init>()
    private val actual = java.util.concurrent.ConcurrentHashMap<String, ManifestProtocol>()
    // Emit source-quality changes immediately, but not the identical rendition
    // snapshot on every 200-300ms rolling capture poll.
    private val renditionDiagnostics = BoundedChangeLog()
    private val clearComposition = ClearCmafArchiveComposition(fullPictures = fullPictures)
    private val compositionDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            // Active rolling composition is playback-critical: prefer it over
            // speculative warmup, but below UI and the display-priority GL thread.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + 2)
            task.run()
        }, "PitwallCmafComposition").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private var previousStartUs: Long? = null
    private val discontinuityGate = HlsDiscontinuityBoundaryGate()
    private var mediaToArchiveOffsetUs: Long? = null
    private var pssh: ByteArray? = null
    var clearOutput: Boolean? = null
        private set

    /** Prepare one fixed replay segment and a full VOD timeline. No player/PLAY session is opened. */
    suspend fun prepareVod(
        feeds: List<F1DashFeedCapture.Feed>, positionMs: Long, clockLabel: String,
        preferredHeight: Int = if (feeds.size > 2) 540 else 720,
        commentary: String? = null,
        minimumHeight: Int = 0,
        preferredHeights: Map<String, Int> = emptyMap(),
    ): HevcArchiveVodSession {
        var acquired: HevcArchiveVodSession? = null
        try { return withContext(Dispatchers.IO) {
            require(feeds.size in 1..app.apexview.AppState.MAX_SINGLE_DECODER_FEEDS && feeds.map { it.label }.distinct().size == feeds.size)
            val perFeed = mapArchiveFeedsBounded(feeds) { feed -> offers.getOrPut(feed.label) { loadOffers(feed) } }
            val (selected, playlists, inits) = loadFittingVod(perFeed, preferredHeight, minimumHeight,
                positionMs, clockLabel, preferredHeights)
            val probeIndexes = probeSegmentIndexes(selected, playlists, positionMs, clockLabel)
            playlists.forEachIndexed { index, playlist ->
                val active = playlist.segments[probeIndexes[index]]
                require(!active.hasGapTag && active.initializationSegment != null) {
                    "Selected archive segment is a gap or has no CMAF initialization"
                }
                // Reject invalid media at the requested position, not a different HLS period
                // that happens to exist hours later in the complete VOD playlist.
                val gapCount = playlist.segments.count { it.hasGapTag }
                val discontinuityCount = playlist.segments.count { it.relativeDiscontinuitySequence != active.relativeDiscontinuitySequence }
                android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_PLAYLIST_BOUNDARIES " +
                    "feed=${selected[index].feed.label} probe=${probeIndexes[index]} segments=${playlist.segments.size} " +
                    "remoteGaps=$gapCount differentDiscontinuitySegments=$discontinuityCount verification=PER_SEGMENT")
            }
            val indexedOffers = selected.mapIndexed { index, offer -> index to offer }
            require(playlists.none { it.tags.any { tag -> tag.startsWith("#EXT-X-KEY:") && !tag.contains("METHOD=NONE") } }) {
                "HLS declares protection but the selected CMAF track is clear"
            }
            val clockPlaylist = playlists[selected.indexOfFirst { it.feed.label == clockLabel }.coerceAtLeast(0)]
            val audioOffers = mapArchiveFeedsBounded(indexedOffers) { (index, offer) ->
                val separate = offer.master?.audios.orEmpty().filter { it.url != null &&
                    (offer.audioGroupId == null || it.groupId == offer.audioGroupId) }
                    .sortedWith(compareByDescending<HlsMultivariantPlaylist.Rendition> { it.format.language == commentary }
                        .thenByDescending { (it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 }).firstOrNull()?.url
                // Preserve the original mandatory primary-audio validation. For
                // alternate separate renditions, failed or uncertain inspection
                // only excludes the source from stable merging, not audio selection.
                var stable = false
                if (separate != null) {
                    val mandatory = commentary != null && offer.feed.label == clockLabel
                    val audioPlaylist = try {
                        parse(separate, offer.master) as? HlsMediaPlaylist
                            ?: throw PermanentCaptureException("Archive audio rendition is not a media playlist")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        coroutineContext.ensureActive()
                        if (mandatory) throw failure
                        android.util.Log.w("ApexviewPlaybackOffer",
                            "ARCHIVE_AUDIO_STABLE_PROBE_FAILED type=${failure.javaClass.simpleName}")
                        null
                    }
                    if (audioPlaylist != null) {
                        val clock = playlists[index]
                        if (mandatory) require(audioPlaylist.hasEndTag && audioPlaylist.segments.isNotEmpty() &&
                            kotlin.math.abs(audioPlaylist.durationUs - clock.durationUs) <= clock.targetDurationUs &&
                            (!audioPlaylist.hasProgramDateTime || !clock.hasProgramDateTime ||
                                kotlin.math.abs(audioPlaylist.startTimeUs - clock.startTimeUs) <= 250_000L)) {
                            "Archive native audio and composed video timelines are incompatible"
                        }
                        stable = audioPlaylist.hasEndTag && audioPlaylist.segments.isNotEmpty() &&
                            audioPlaylist.segments.none { segment ->
                                segment.hasGapTag || segment.drmInitData != null ||
                                    segment.fullSegmentEncryptionKeyUri != null ||
                                    segment.initializationSegment?.fullSegmentEncryptionKeyUri != null ||
                                    segment.initializationSegment?.drmInitData != null
                            } && audioPlaylist.tags.none { it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE") } &&
                            ArchiveAudioSelectionPlan.sameTimeline(
                                audioPlaylist.startTimeUs.takeIf { audioPlaylist.hasProgramDateTime },
                                clockPlaylist.startTimeUs.takeIf { clockPlaylist.hasProgramDateTime },
                                audioPlaylist.durationUs, clockPlaylist.durationUs)
                    }
                }
                // Silent video tiles need no audio rendition. Some HLS variants instead carry
                // audio in the same playlist; pass those to Media3's audio-only source.
                val muxed = offer.master?.muxedAudioFormat != null ||
                    inits[index].report.tracks.any { it.handler == "soun" } ||
                    offer.format?.codecs.orEmpty().split(',').any { it.trim().startsWith("mp4a") }
                val audio = separate ?: if (muxed) offer.uri else null
                android.util.Log.i("ApexviewPlaybackOffer", "${offer.feed.label}: audio=${if (separate != null) "SEPARATE" else if (muxed) "MUXED_ADVERTISED" else "NOT_ADVERTISED"}")
                audio?.let { Triple(offer.feed.label, it, stable) }
            }.filterNotNull()
            val audioUris = audioOffers.associate { it.first to it.second }
            val stableAudioUris = audioOffers.filter { it.third }.map { it.second }.toSet()
            android.util.Log.i("ApexviewPlaybackOffer", "ARCHIVE_AUDIO_STABLE_ELIGIBLE count=${stableAudioUris.size}")
            val audio = audioUris[clockLabel] ?: if (commentary == null) null else {
                // Let the ordinary authorized Media3 HLS path resolve the selected feed's
                // audio when the selected variant doesn't advertise a separate rendition.
                // This may have no audio; it must never reject otherwise compatible video.
                android.util.Log.i("ApexviewPlaybackOffer", "$clockLabel: audio=ORIGINAL_OFFER_UNVERIFIED")
                Uri.parse(selected.first { it.feed.label == clockLabel }.feed.manifestUrl)
            }
            val selectableAudioUris = if (audio == null) audioUris else audioUris + (clockLabel to audio)
            var session: HevcArchiveVodSession? = null
            val owned = selected.mapIndexed { i, offer -> HevcArchiveVodSession.Input(offer.feed.label, playlists[i], offer.format,
                inits[i].bytes.copyOf(), inits[i].report,
                requireNotNull(playlists[i].segments[probeIndexes[i]].initializationSegment)) }
            try {
                session = HevcArchiveVodSession(reader, owned, clockLabel, maximumOutputWidth = maximumOutputWidth,
                    audioUri = audio, audioUris = selectableAudioUris, stableAudioUris = stableAudioUris)
                session.prepare(positionMs)
                coroutineContext.ensureActive()
                acquired = session
                session
            } catch (error: Throwable) {
                session?.close() ?: owned.forEach { it.initialization.fill(0) }
                throw error
            }
        } } catch (error: Throwable) {
            // withContext may cancel while dispatching the resource back to the caller.
            acquired?.close()
            throw error
        }
    }

    private suspend fun loadFittingVod(
        perFeed: List<List<Offer>>, preferredHeight: Int, minimumHeight: Int,
        positionMs: Long, clockLabel: String,
        preferredHeights: Map<String, Int> = emptyMap(),
    ): Triple<List<Offer>, List<HlsMediaPlaylist>, List<Init>> {
        var offers = perFeed
        val independent = preferredHeights.values.distinct().size > 1
        while (true) {
            val selected = if (independent) chooseIndividual(offers, preferredHeights, preferredHeight, minimumHeight)
                else chooseCommon(offers, preferredHeight, perFeed.size, minimumHeight)
            val playlists = mapArchiveFeedsBounded(selected, block = ::loadMedia)
            require(playlists.all { it.hasEndTag && it.segments.isNotEmpty() }) {
                "Archive VOD requires complete HLS replay playlists"
            }
            playlists.forEach { playlist ->
                require(playlist.segments.none { it.fullSegmentEncryptionKeyUri != null || it.initializationSegment?.fullSegmentEncryptionKeyUri != null }) {
                    "HLS AES-128 full-segment encryption is unsupported; no key is requested"
                }
            }
            val probes = probeSegmentIndexes(selected, playlists, positionMs, clockLabel)
            val inits = mapArchiveFeedsBounded(selected.indices.toList()) { i ->
                loadInit(selected[i], playlists[i], playlists[i].segments[probes[i]])
            }
            val tracks = inits.map { it.report.tracks.singleOrNull { track -> track.handler == "vide" }
                ?: throw PermanentCaptureException("HLS archive must contain one video track") }
            if (tracks.any { it.encrypted }) throw ProtectedArchiveBackendRequired()
            val sizes = tracks.map { track ->
                track.hevcConfiguration?.sps?.singleOrNull()
                    ?: throw PermanentCaptureException("No compatible HEVC CMAF representation")
            }
            require(sizes.all { it.displayHeight >= minimumHeight }) {
                "Requested at least ${minimumHeight}p per feed; the actual HEVC pictures are smaller."
            }
            val fits = if (independent) {
                HevcMixedArchiveAtlasPlan.choose(tracks.map { track ->
                    val hvc = requireNotNull(track.hevcConfiguration)
                    HevcMixedArchiveAtlasPlan.Source(hvc.vps.single(), hvc.sps.single(), hvc.pps.single())
                }, maximumOutputWidth, HevcArchiveAtlasPlan.MAX_CODED_HEIGHT) != null
            } else sizes.all { shape ->
                HevcArchiveAtlasPlan.choose(perFeed.size, shape.width, shape.height, maximumOutputWidth, HevcArchiveAtlasPlan.MAX_CODED_HEIGHT) != null
            }
            if (fits) return Triple(selected, playlists, inits)
            if (independent) throw PermanentCaptureException(
                "Selected per-feed HEVC renditions cannot form one decoder-safe atlas. " +
                    "Source SPS/PPS tile boundaries or codec parameters are incompatible: " +
                    sizes.joinToString { "${it.width}x${it.height}" })
            android.util.Log.w("PitwallCast", "ARCHIVE_CODED_SIZE_REJECT feeds=${perFeed.size} " +
                "coded=${sizes.joinToString { "${it.width}x${it.height}" }}")
            val declared = selected.firstNotNullOfOrNull { it.format }
                ?: throw PermanentCaptureException("The supplied HLS playlists have fixed renditions and their coded pictures do not fit the combined stream")
            offers = offers.map { list -> list.filter { offer ->
                val format = offer.format
                format == null || format.width != declared.width || format.height != declared.height
            } }
            if (offers.any { it.isEmpty() }) throw PermanentCaptureException("No offered HEVC rendition fits the combined archive picture")
        }
    }

    /** Pick init segments in the same time window that the finite archive session will probe. */
    private fun probeSegmentIndexes(
        selected: List<Offer>, playlists: List<HlsMediaPlaylist>, positionMs: Long, clockLabel: String
    ): List<Int> {
        val clockIndex = selected.indexOfFirst { it.feed.label == clockLabel }.coerceAtLeast(0)
        val usePdt = playlists.all { it.hasProgramDateTime }
        val targetUs = Math.multiplyExact(positionMs.coerceAtLeast(0L), 1000L)
        return playlists.mapIndexed { index, playlist ->
            val offset = if (usePdt) playlist.startTimeUs - playlists[clockIndex].startTimeUs else 0L
            playlist.segments.indexOfLast { it.relativeStartTimeUs + offset <= targetUs }.coerceAtLeast(0)
        }
    }

    suspend fun captureAtPosition(group: String, feeds: List<F1DashFeedCapture.Feed>, positionMs: Long, live: Boolean = false, followLiveEdge: Boolean = true): F1DashFeedCapture.Snapshot = withContext(Dispatchers.IO) {
        var format: Format? = null
        var stage = "source-manifests"
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val gcAtStart = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
        val allocatedAtStart = android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
        try {
            require(feeds.size in 2..8 && feeds.map { it.label }.distinct().size == feeds.size)
            // Keep network lanes bounded and independent of the single GL video thread.
            val perFeed = mapArchiveFeedsBounded(feeds) { feed ->
                offers.getOrPut(feed.label) { loadOffers(feed) }
            }
            stage = "per-feed-hevc-rendition"
            // Protected HLS is not DASH. Its old chooseCommon() discarded the user's
            // independent quality choices before the mixed HEVC composer ever ran.
            // Read real preferences from the F1ArchiveFeedCapture.Feed objects, with
            // AUTO capped by the authorized maximum. Keep the original common route
            // intact when all feeds request the same source quality.
            val heights = feeds.associate { feed ->
                feed.label to (feed.preferredHeight?.takeIf { it > 0 } ?: maximumHeight)
            }
            val individual = heights.values.distinct().size > 1
            val selected = if (individual) chooseIndividual(perFeed, heights, maximumHeight, minimumCaptureHeight)
                else chooseCommon(perFeed, preferredHeight = maximumHeight.takeIf { it <= 1080 },
                    minimumHeight = minimumCaptureHeight)
            format = selected.first().format
            val renditionReport = "REAL_HLS_RENDITIONS policy=${if (individual) "PER_FEED" else "COMMON"} " +
                selected.joinToString(", ") { offer ->
                    "${offer.feed.label}=${offer.format?.width ?: 0}x${offer.format?.height ?: 0}"
                } + " protectedValidation=MANDATORY decoderCount=ONE"
            if (renditionDiagnostics.shouldLog(renditionReport, startedMs)) {
                android.util.Log.i("ApexviewSingleDecoder", renditionReport)
            }
            stage = "video-playlists"
            val playlists = coroutineScope { selected.map { offer -> async { loadMedia(offer) } }.awaitAll() }
            if (!live && playlists.any { !it.hasEndTag }) throw PermanentCaptureException("HLS single-decoder archive capture requires complete replay playlists")
            val useProgramTime = playlists.all { it.hasProgramDateTime }
            val masterStartUs = playlists.first().startTimeUs
            val windows = playlists.map { playlist ->
                playlist.segments.mapIndexed { index, segment ->
                    ArchiveDashSegmentSelector.Candidate(index.toLong(), segment.relativeStartTimeUs +
                        if (useProgramTime) playlist.startTimeUs - (if (live) 0L else masterStartUs) else if (live) throw PermanentCaptureException("Live HLS needs program date time for cross-feed synchronization") else 0L)
                }
            }
            val targetUs = if (live && followLiveEdge) windows.minOf { it.lastOrNull()?.absoluteStartUs ?: 0L } else Math.multiplyExact(positionMs, 1000)
            val aligned = ArchiveDashSegmentSelector.selectAligned(windows, targetUs, previousStartUs, 250_000,
                maximumLeadUs = ArchiveBufferPolicy.maximumLeadUs(windows.first(), targetUs))
                ?: return@withContext snapshot(group, format, null, 0,
                    if (previousStartUs == null) CaptureFailure("HLS fragment alignment failure at the archive position", false) else null,
                    "Waiting for the next aligned HLS archive segment")
            val segments = playlists.zip(aligned).map { (playlist, candidate) -> playlist.segments[candidate.number.toInt()] }
            // EXT-X-DISCONTINUITY-SEQUENCE is local to each media rendition.
            // Comparing raw numbers across different feeds falsely rejected aligned
            // F1 HLS streams. Instead require a synchronized *change* of period
            // across consecutive captures; PTS/POC/CMAF checks still validate media.
            val discontinuities = playlists.zip(segments).map { (playlist, segment) ->
                playlist.discontinuitySequence + segment.relativeDiscontinuitySequence
            }
            val labels = selected.map { it.feed.label }
            if (!discontinuityGate.accepts(labels, discontinuities, aligned.first().absoluteStartUs)) {
                throw PermanentCaptureException("HLS feeds changed discontinuity periods at different segment boundaries")
            }
            if (segments.any { it.hasGapTag }) throw PermanentCaptureException("Selected HLS archive segment is marked as a gap")
            if (segments.any { it.fullSegmentEncryptionKeyUri != null || it.initializationSegment?.fullSegmentEncryptionKeyUri != null }) {
                throw PermanentCaptureException("HLS AES-128 full-segment encryption is unsupported; no key is requested")
            }
            stage = "cmaf-init"
            // Init metadata is immutable by rendition; the ConcurrentHashMap cache
            // prevents repeated CMAF parsing when the sliding window advances.
            val inits = mapArchiveFeedsBounded(selected.indices.toList()) { i ->
                loadInit(selected[i], playlists[i], segments[i])
            }
            // In hev1, a valid encrypted HEVC track can carry VPS/SPS/PPS in its
            // media samples rather than in hvcC. Fetch the real selected fragment
            // before judging that representation. Bound memory and clear all bytes.
            // Indexed owned storage ensures cancellation still zeroes successfully
            // fetched fragments when a different feed fails. Avoid serial I/O.
            val media = MutableList(selected.size) { ByteArray(0) }
            val fetchStartedMs = android.os.SystemClock.elapsedRealtime()
            var fetchMs = 0L
            var composeMs = 0L
            try {
                stage = "cmaf-fragment-download"
                mapArchiveFeedsBounded(selected.indices.toList(), concurrency = 4) { index ->
                    coroutineContext.ensureActive()
                    val segment = segments[index]
                    media[index] = reader.read(
                        UriUtil.resolveToUri(playlists[index].baseUri, segment.url),
                        MAX_MEDIA_BYTES, segment.byteRangeOffset, segment.byteRangeLength
                    ).bytes
                }
                fetchMs = android.os.SystemClock.elapsedRealtime() - fetchStartedMs
                // One bounded, read-only profile for every *real* selected rendition
                // before any individual compatibility gate can stop the capture.
                // Only structural metadata is logged, never media bytes, IVs or KIDs.
                val fragmentProfiles = selected.indices.map { index ->
                    val initVideo = inits[index].report.tracks.singleOrNull { it.handler == "vide" }
                    val profile = CmafBoxInspector.inspect(media[index], inits[index].report)
                    val videoFragment = profile.fragments.singleOrNull {
                        it.trackId == initVideo?.trackId
                    }
                    val hvc = initVideo?.hevcConfiguration
                    android.util.Log.i("ApexviewSingleDecoder",
                        "REAL_HLS_CMAF_STRUCTURE feed=${selected[index].feed.label} " +
                            "entry=${initVideo?.sampleEntry ?: "missing"} " +
                            "original=${initVideo?.originalFormat ?: "missing"} " +
                            "encrypted=${initVideo?.encrypted} " +
                            "hvcCParsed=${hvc?.vps?.size ?: 0}/${hvc?.sps?.size ?: 0}/${hvc?.pps?.size ?: 0} " +
                            "hvcCRaw=${hvc?.vpsCount ?: 0}/${hvc?.spsCount ?: 0}/${hvc?.ppsCount ?: 0} " +
                            "fragmentCount=${profile.fragments.size} " +
                            "groupBoxes=${videoFragment?.sampleGroupDescriptionPresent}/" +
                                "${videoFragment?.sampleToGroupPresent} " +
                            "seigActive=${videoFragment?.sampleGroupEncryptionPresent} " +
                            "seigComplete=${videoFragment?.sampleGroupEncryptionStructureComplete} " +
                            "seigRuns=${videoFragment?.sampleGroupEncryptionMetadata?.runs?.size ?: 0} " +
                            "senc=${videoFragment?.sampleEncryptionBoxPresent} " +
                            "sencComplete=${videoFragment?.sampleEncryptionStructureComplete} " +
                            "auxSaiz=${videoFragment?.sampleAuxiliarySizesPresent} " +
                            "auxSaio=${videoFragment?.sampleAuxiliaryOffsetsPresent} " +
                            "auxComplete=${videoFragment?.sampleAuxiliaryInfoStructureComplete} " +
                            "subsampleComplete=${videoFragment?.sampleEncryptionRangesCoverSamples} " +
                            "samples=${videoFragment?.sampleCount ?: 0} " +
                            "sampleDescriptionIndex=${videoFragment?.sampleDescriptionIndex} " +
                            "inspectionErrors=${profile.errors.size}")
                    profile
                }
                stage = "real-hev1-parameter-validation"
                val resolvedReports = selected.indices.map { index ->
                    val original = inits[index].report
                    val video = original.tracks.singleOrNull { it.handler == "vide" }
                        ?: throw PermanentCaptureException(
                            "${selected[index].feed.label}: initialization has no unambiguous video track"
                        )
                    val hevc = video.hevcConfiguration
                    if (video.encrypted && video.originalFormat == "hev1" && hevc != null &&
                        (hevc.vps.size != 1 || hevc.sps.size != 1 || hevc.pps.size != 1)) {
                        Hev1InbandParameterSetRecovery.resolve(
                            selected[index].feed.label,
                            inits[index].bytes, media[index], original
                        )
                    } else original
                }
                val tracks = resolvedReports.mapIndexed { index, init ->
                    init.tracks.singleOrNull { it.handler == "vide" }
                        ?: throw PermanentCaptureException(
                            "${selected[index].feed.label}: CMAF init must have exactly one video track"
                        )
                }
                tracks.forEachIndexed { index, track ->
                    val config = track.hevcConfiguration
                    android.util.Log.i("ApexviewSingleDecoder",
                        "REAL_CMAF_HEVC_INIT feed=${selected[index].feed.label} " +
                            "entry=${track.sampleEntry ?: "none"} original=${track.originalFormat ?: "none"} " +
                            "encrypted=${track.encrypted} configuration=${config != null} " +
                            "parsed=${config?.vps?.size ?: 0}/${config?.sps?.size ?: 0}/${config?.pps?.size ?: 0} " +
                            "raw=${config?.vpsCount ?: 0}/${config?.spsCount ?: 0}/${config?.ppsCount ?: 0} " +
                            "errors=${config?.parameterSetErrors?.size ?: 0}")
                    val sps = config?.sps?.singleOrNull()
                        ?: throw PermanentCaptureException(
                            "${selected[index].feed.label}: HEVC parameter sets are incomplete " +
                                "(hvcC=${config != null}, parsed VPS/SPS/PPS=" +
                                "${config?.vps?.size ?: 0}/${config?.sps?.size ?: 0}/${config?.pps?.size ?: 0}, " +
                                "raw=${config?.vpsCount ?: 0}/${config?.spsCount ?: 0}/${config?.ppsCount ?: 0})"
                        )
                    require(config.vps.size == 1 && config.pps.size == 1 &&
                        config.parameterSetErrors.isEmpty()) {
                        "${selected[index].feed.label}: HEVC VPS/PPS validation failed"
                    }
                    if (sps.displayHeight < minimumCaptureHeight) {
                        throw PermanentCaptureException(
                            "${selected[index].feed.label}: actual HEVC source is below ${minimumCaptureHeight}p"
                        )
                    }
                }
                val clear = tracks.all { !it.encrypted }
                if (!clear && tracks.any { !it.encrypted || it.protectionScheme != "cbcs" }) {
                    throw PermanentCaptureException(
                        "HLS composition needs all-clear HEVC or compatible CBCS HEVC; mixed protection is unsupported"
                    )
                }
                if (clearOutput != null && clearOutput != clear) {
                    throw PermanentCaptureException("HLS archive protection changed during composition")
                }
                clearOutput = clear
                if (clear && playlists.any { playlist ->
                        playlist.tags.any { it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE") }
                    }) {
                    throw PermanentCaptureException(
                        "HLS declares encryption but its selected CMAF track is clear"
                    )
                }
                segments.forEach { rememberPssh(it.drmInitData) }
                selected.forEach { it.master?.sessionKeyDrmInitData?.forEach(::rememberPssh) }
                if (!clear) selected.indices.forEach { index ->
                    ProtectedHevcLiveComposition.recordCmafResponse(
                        group, selected[index].feed.label, inits[index].bytes, resolvedReports[index]
                    )
                }
                val composeStartedMs = android.os.SystemClock.elapsedRealtime()
                stage = "video-fragment-clock"
                if (mediaToArchiveOffsetUs == null) {
                    val inspected = CmafBoxInspector.inspect(media.first(), inits.first().report)
                    if (inspected.errors.isNotEmpty()) {
                        throw PermanentCaptureException("${selected.first().feed.label}: initial CMAF media fragment failed structural inspection")
                    }
                    // Do not let .first(predicate) silently throw NoSuchElementException
                    // when the genuine media segment does not refer to the video's init
                    // track. Report the actual fragment IDs rather than guessing.
                    val firstFragment = inspected.fragments.firstOrNull { it.trackId == tracks.first().trackId }
                        ?: throw PermanentCaptureException(
                            "${selected.first().feed.label}: video fragment track mismatch " +
                                "initTrack=${tracks.first().trackId} mediaTracks=${inspected.fragments.map { it.trackId }.distinct()} " +
                                "fragments=${inspected.fragments.size}"
                        )
                    val decodeTicks = firstFragment.baseDecodeTimeTicks
                        ?: throw PermanentCaptureException("${selected.first().feed.label}: video fragment has no base decode time")
                    val timescale = tracks.first().timescale
                        ?: throw PermanentCaptureException("${selected.first().feed.label}: video track timescale is missing")
                    mediaToArchiveOffsetUs = aligned.first().absoluteStartUs -
                        CmafWindowTiming.ticksToUs(decodeTicks, timescale)
                }
                stage = if (clear) "clear-hevc-tile-composition" else "cbcs-hevc-tile-composition"
                // Both clear and CBCS composition are CPU-bound. Neither should
                // run on the IO/network dispatcher that schedules later fragments.
                // A single priority-lowered worker per capture preserves CBCS
                // sample ordering, avoids blocking the UI and never opens a
                // second decoder or decrypts encrypted payloads.
                withContext(compositionDispatcher) {
                    if (clear) {
                        clearComposition.compose(selected.mapIndexed { index, offer ->
                            HevcClearCmafGridComposer.Feed(inits[index].bytes, listOf(media[index]), offer.feed.label)
                        })
                    } else {
                        selected.forEachIndexed { index, offer ->
                            coroutineContext.ensureActive()
                            // The already-validated real CMAF report is reused
                            // unless in-band HEV1 resolution changed the init's
                            // codec configuration; that case demands reinspection.
                            val report = if (resolvedReports[index] === inits[index].report)
                                fragmentProfiles[index]
                            else CmafBoxInspector.inspect(media[index], resolvedReports[index])
                            if (report.errors.isNotEmpty()) throw PermanentCaptureException("${offer.feed.label}: invalid HLS CMAF fragment")
                            ProtectedHevcLiveComposition.recordCmafResponse(group, offer.feed.label, media[index], report)
                        }
                        ProtectedHevcLiveComposition.rejectionReason(group)?.let { throw PermanentCaptureException("HLS HEVC/CMAF composition rejected: $it") }
                    }
                }
                composeMs = android.os.SystemClock.elapsedRealtime() - composeStartedMs
                discontinuityGate.commit(labels, discontinuities, aligned.first().absoluteStartUs)
                previousStartUs = aligned.first().absoluteStartUs
                val allocated = allocatedAtStart?.let { before ->
                    android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                        ?.let { (it - before).coerceAtLeast(0L) }
                }
                val collections = gcAtStart?.let { before ->
                    android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                        ?.let { (it - before).coerceAtLeast(0L) }
                }
                android.util.Log.i("ApexviewSingleDecoder", "HLS_CMAF_CAPTURE feeds=${feeds.size} protection=${if (clear) "CLEAR" else "CBCS"} aligned=PASS composed=${if (clear) clearComposition.hasOutput() else ProtectedHevcLiveComposition.hasOutput(group)} fetchMs=$fetchMs composeMs=$composeMs totalMs=${android.os.SystemClock.elapsedRealtime() - startedMs} archiveClockMs=$positionMs segmentStartUs=${aligned.first().absoluteStartUs} " +
                    "processAllocBytesDuringSegment=${allocated ?: -1L} processGcDuringSegment=${collections ?: -1L} " +
                    "sourceBytes=${media.sumOf { it.size.toLong() }} ioConcurrency=4 compositionWorker=BELOW_UI_PRIORITY")
                snapshot(group, format, aligned.first().absoluteStartUs, feeds.size)
            } finally { media.forEach { it.fill(0) } }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            // Real F1 troubleshooting needs the failing production location, not
            // "Capture validation failed (NoSuchElementException)". Log only an
            // application stack frame and bounded safe metadata: no URLs, keys,
            // PSSH, cookies, or encrypted bytes are ever written to the log.
            val origin = error.stackTrace.firstOrNull { it.className.startsWith("app.apexview.") }
            val at = origin?.let { "${it.className.substringAfterLast('.')}/${it.methodName}:${it.lineNumber}" } ?: "unknown"
            android.util.Log.e("ApexviewSingleDecoder",
                "REAL_HLS_CAPTURE_FAILURE stage=$stage origin=$at type=${error.javaClass.simpleName} " +
                    "feeds=${feeds.size} outputProtection=${if (clearOutput == true) "CLEAR" else if (clearOutput == false) "CBCS" else "UNVERIFIED"}")
            val normalized = if (error is IllegalArgumentException || error is IllegalStateException ||
                error is NoSuchElementException) {
                PermanentCaptureException("HLS CMAF stage=$stage origin=$at: " +
                    (error.message?.replace(Regex("https?://\\S+"), "[URL]")?.take(130)
                        ?: error.javaClass.simpleName))
            } else error
            snapshot(group, format, null, 0, CaptureFailure.from(normalized))
        }
    }

    fun outputWindow(group: String): ProtectedHevcLiveComposition.OutputWindow? =
        if (clearOutput == true) clearComposition.outputWindow() else ProtectedHevcLiveComposition.outputWindow(group)

    fun widevinePssh(): ByteArray? = pssh?.copyOf()

    fun reset() {
        initializations.values.forEach { it.bytes.fill(0) }; initializations.clear()
        offers.clear(); vodPlaylists.clear(); actual.clear(); previousStartUs = null; discontinuityGate.reset(); mediaToArchiveOffsetUs = null
        pssh?.fill(0); pssh = null; clearOutput = null; clearComposition.reset()
        compositionDispatcher.close()
    }

    private fun snapshot(group: String, format: Format?, startUs: Long?, count: Int, failure: CaptureFailure? = null, detail: String? = null) =
        F1DashFeedCapture.Snapshot(format?.width, format?.height, format?.codecs, startUs, count,
            if (clearOutput == true) clearComposition.status else ProtectedHevcLiveComposition.status(group),
            failure?.reason ?: detail, failure, actual.toMap(),
            mediaToArchiveOffsetUs?.let { offset ->
                (if (clearOutput == true) clearComposition.outputTiming() else ProtectedHevcLiveComposition.outputTiming(group))?.shifted(offset)
            })

    private fun parse(uri: Uri, master: HlsMultivariantPlaylist? = null): HlsPlaylist {
        val response = reader.read(uri, MAX_MANIFEST_BYTES)
        try {
            if (ManifestProtocol.fromBody(response.bytes) == ManifestProtocol.DASH) throw ArchiveVodBackendRequired("DASH offer uses the existing CMAF/Widevine backend")
            if (ManifestProtocol.fromBody(response.bytes) != ManifestProtocol.HLS) throw PermanentCaptureException("Expected a real HLS playlist response")
            return (if (master == null) HlsPlaylistParser() else HlsPlaylistParser(master, null))
                .parse(response.uri, ByteArrayInputStream(response.bytes))
        } finally { response.bytes.fill(0) }
    }

    private fun loadOffers(feed: F1DashFeedCapture.Feed): List<Offer> {
        val playlist = parse(Uri.parse(feed.manifestUrl))
        actual[feed.label] = ManifestProtocol.HLS
        android.util.Log.i("ApexviewPlaybackOffer", "${feed.label}: actualManifest=HLS acquisition=NATIVE_HLS_CMAF")
        return when (playlist) {
            is HlsMultivariantPlaylist -> playlist.variants.filter {
                it.format.codecs?.split(',')?.any { codec -> codec.trim().startsWith("hvc1") || codec.trim().startsWith("hev1") } == true &&
                    it.format.height in 1..maximumHeight
            }.map { Offer(feed, it.url, it.format, playlist, it.audioGroupId) }.ifEmpty { throw PermanentCaptureException("${feed.label}: HLS offers no supported HEVC variant") }
            is HlsMediaPlaylist -> {
                if (playlist.hasEndTag) vodPlaylists[playlist.baseUri] = playlist
                listOf(Offer(feed, Uri.parse(playlist.baseUri), null, null))
            }
            else -> throw PermanentCaptureException("${feed.label}: unsupported HLS playlist")
        }
    }

    private fun chooseCommon(perFeed: List<List<Offer>>, preferredHeight: Int? = null, feedCount: Int = perFeed.size, minimumHeight: Int = 0): List<Offer> {
        // A direct media playlist has no rendition declarations; hvcC validates it later.
        val declared = perFeed.filter { list -> list.any { it.format != null } }
        if (declared.isEmpty()) return perFeed.map { it.first() }
        val common = declared.map { list -> list.map { it.format!!.width to it.format.height }.toSet() }.reduce { a, b -> a intersect b }
        val size = HevcArchiveRenditionPlan.choose(common, preferredHeight, minimumHeight, feedCount, maximumOutputWidth)
            ?: throw PermanentCaptureException(if (minimumHeight > 0)
                "No common HEVC rendition of at least ${minimumHeight}p fits $feedCount feeds. Try fewer feeds or select a lower quality."
                else "No common HEVC rendition fits $feedCount feeds in the composed picture; choose fewer feeds or a lower source rendition")
        return perFeed.map { list -> list.filter { it.format == null || it.format.width == size.first && it.format.height == size.second }
            .maxByOrNull { it.format?.bitrate ?: 0 } ?: throw PermanentCaptureException("HLS common HEVC rendition unavailable") }
    }

    /** Each user-selected feed gets its own rendition. No decoder/player is allocated here. */
    private fun chooseIndividual(perFeed: List<List<Offer>>, heights: Map<String, Int>,
                                 defaultHeight: Int, minimumHeight: Int): List<Offer> = perFeed.map { variants ->
        val label = variants.first().feed.label
        val requested = heights[label]?.takeIf { it > 0 } ?: defaultHeight
        variants.filter { it.format == null ||
            (it.format.height in minimumHeight..requested && it.format.width > 0) }
            .maxWithOrNull(compareBy<Offer> { it.format?.height ?: requested }
                .thenBy { it.format?.bitrate ?: 0 })
            ?: throw PermanentCaptureException("$label has no available HEVC rendition at or below ${requested}p")
    }

    private fun loadMedia(offer: Offer): HlsMediaPlaylist = vodPlaylists[offer.uri.toString()] ?: run {
        val playlist = parse(offer.uri, offer.master) as? HlsMediaPlaylist ?: throw PermanentCaptureException("HLS variant did not return a media playlist")
        if (playlist.hasEndTag) vodPlaylists[offer.uri.toString()] = playlist
        playlist
    }

    private fun loadInit(offer: Offer, playlist: HlsMediaPlaylist, segment: HlsMediaPlaylist.Segment): Init {
        val map = segment.initializationSegment ?: throw PermanentCaptureException("${offer.feed.label}: HLS has no EXT-X-MAP; MPEG-TS is not HEVC CMAF")
        val uri = UriUtil.resolveToUri(playlist.baseUri, map.url)
        initializations[offer.feed.label]?.let { if (it.uri == uri && it.offset == map.byteRangeOffset && it.length == map.byteRangeLength) return it }
        val bytes = reader.read(uri, MAX_INIT_BYTES, map.byteRangeOffset, map.byteRangeLength).bytes
        try {
            val report = CmafBoxInspector.inspect(bytes)
            if (report.errors.isNotEmpty() || !report.initializationSegmentPresent) throw PermanentCaptureException("${offer.feed.label}: invalid HLS CMAF initialization")
            initializations.put(offer.feed.label, Init(uri, map.byteRangeOffset, map.byteRangeLength, bytes, report))?.bytes?.fill(0)
            return initializations.getValue(offer.feed.label)
        } catch (error: Throwable) { bytes.fill(0); throw error }
    }

    private fun rememberPssh(data: DrmInitData?) {
        if (pssh != null || data == null) return
        for (i in 0 until data.schemeDataCount) {
            val entry = data[i]
            if (entry.uuid == C.WIDEVINE_UUID && entry.data != null) { pssh = entry.data!!.copyOf(); return }
        }
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        const val MAX_INIT_BYTES = 2 * 1024 * 1024
        const val MAX_MEDIA_BYTES = 8 * 1024 * 1024
    }
}
