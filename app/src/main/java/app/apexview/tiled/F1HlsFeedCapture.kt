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
import java.io.ByteArrayInputStream
import kotlin.coroutines.coroutineContext

/** Reads the HLS/CMAF actually supplied by player_tm, without another PLAY or media player. */
internal class F1HlsFeedCapture(factory: DataSource.Factory, private val maximumHeight: Int = 2160,
                                private val maximumOutputWidth: Int = 4096) {
    private data class Offer(val feed: F1DashFeedCapture.Feed, val uri: Uri, val format: Format?, val master: HlsMultivariantPlaylist?, val audioGroupId: String? = null)
    private class Init(val uri: Uri, val offset: Long, val length: Long, val bytes: ByteArray, val report: CmafBoxInspector.Report)
    private val reader = CmafHttpReader(factory)
    private val offers = java.util.concurrent.ConcurrentHashMap<String, List<Offer>>()
    private val vodPlaylists = java.util.concurrent.ConcurrentHashMap<String, HlsMediaPlaylist>()
    private val initializations = java.util.concurrent.ConcurrentHashMap<String, Init>()
    private val actual = java.util.concurrent.ConcurrentHashMap<String, ManifestProtocol>()
    private val clearComposition = ClearCmafArchiveComposition()
    private var previousStartUs: Long? = null
    private var mediaToArchiveOffsetUs: Long? = null
    private var pssh: ByteArray? = null
    var clearOutput: Boolean? = null
        private set

    /** Prepare one fixed replay segment and a full VOD timeline. No player/PLAY session is opened. */
    suspend fun prepareVod(
        feeds: List<F1DashFeedCapture.Feed>, positionMs: Long, clockLabel: String,
        preferredHeight: Int = if (feeds.size > 2) 540 else 720,
        commentary: String? = null,
    ): HevcArchiveVodSession {
        var acquired: HevcArchiveVodSession? = null
        try { return withContext(Dispatchers.IO) {
            require(feeds.size in 1..app.apexview.AppState.MAX_SINGLE_DECODER_FEEDS && feeds.map { it.label }.distinct().size == feeds.size)
            val perFeed = coroutineScope { feeds.map { feed -> async { offers.getOrPut(feed.label) { loadOffers(feed) } } }.awaitAll() }
            val selected = chooseCommon(perFeed, preferredHeight, feeds.size)
            val playlists = coroutineScope { selected.map { offer -> async { loadMedia(offer) } }.awaitAll() }
            require(playlists.all { it.hasEndTag && it.segments.isNotEmpty() }) { "Archive VOD requires complete HLS replay playlists" }
            playlists.forEach { playlist ->
                require(playlist.segments.none { it.fullSegmentEncryptionKeyUri != null || it.initializationSegment?.fullSegmentEncryptionKeyUri != null }) {
                    "HLS AES-128 full-segment encryption is unsupported; no key is requested"
                }
                val firstMap = requireNotNull(playlist.segments.first().initializationSegment) { "HLS archive has no CMAF initialization" }
                require(playlist.segments.all { segment -> segment.initializationSegment?.let {
                    it.url == firstMap.url && it.byteRangeOffset == firstMap.byteRangeOffset && it.byteRangeLength == firstMap.byteRangeLength
                } == true && segment.relativeDiscontinuitySequence == 0 && !segment.hasGapTag }) {
                    "Archive changes CMAF initialization, crosses a discontinuity or contains a gap"
                }
            }
            val inits = coroutineScope { selected.mapIndexed { i, offer -> async { loadInit(offer, playlists[i].segments.first()) } }.awaitAll() }
            val tracks = inits.map { it.report.tracks.singleOrNull { track -> track.handler == "vide" }
                ?: throw PermanentCaptureException("HLS archive must contain one video track") }
            if (tracks.any { it.encrypted }) throw ProtectedArchiveBackendRequired()
            require(tracks.all { it.hevcConfiguration != null }) { "No compatible HEVC CMAF representation" }
            require(playlists.none { it.tags.any { tag -> tag.startsWith("#EXT-X-KEY:") && !tag.contains("METHOD=NONE") } }) {
                "HLS declares protection but the selected CMAF track is clear"
            }
            val audioUris = coroutineScope { selected.mapIndexed { index, offer -> async {
                val separate = offer.master?.audios.orEmpty().filter { it.url != null &&
                    (offer.audioGroupId == null || it.groupId == offer.audioGroupId) }
                    .sortedWith(compareByDescending<HlsMultivariantPlaylist.Rendition> { it.format.language == commentary }
                        .thenByDescending { (it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 }).firstOrNull()?.url
                if (commentary != null && separate != null && offer.feed.label == clockLabel) {
                    val audioPlaylist = parse(separate, offer.master) as? HlsMediaPlaylist
                        ?: throw PermanentCaptureException("Archive audio rendition is not a media playlist")
                    val clock = playlists[index]
                    require(audioPlaylist.hasEndTag && audioPlaylist.segments.isNotEmpty() &&
                        kotlin.math.abs(audioPlaylist.durationUs - clock.durationUs) <= clock.targetDurationUs &&
                        (!audioPlaylist.hasProgramDateTime || !clock.hasProgramDateTime ||
                            kotlin.math.abs(audioPlaylist.startTimeUs - clock.startTimeUs) <= 250_000L)) {
                        "Archive native audio and composed video timelines are incompatible"
                    }
                }
                // Silent video tiles need no audio rendition. Some HLS variants instead carry
                // audio in the same playlist; pass those to Media3's audio-only source.
                val muxed = offer.master?.muxedAudioFormat != null ||
                    inits[index].report.tracks.any { it.handler == "soun" } ||
                    offer.format?.codecs.orEmpty().split(',').any { it.trim().startsWith("mp4a") }
                val audio = separate ?: if (muxed) offer.uri else null
                android.util.Log.i("ApexviewPlaybackOffer", "${offer.feed.label}: audio=${if (separate != null) "SEPARATE" else if (muxed) "MUXED_ADVERTISED" else "NOT_ADVERTISED"}")
                audio?.let { offer.feed.label to it }
            } }.awaitAll() }.filterNotNull().toMap()
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
                inits[i].bytes.copyOf(), inits[i].report) }
            try {
                session = HevcArchiveVodSession(reader, owned, clockLabel, maximumOutputWidth = maximumOutputWidth,
                    audioUri = audio, audioUris = selectableAudioUris)
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

    suspend fun captureAtPosition(group: String, feeds: List<F1DashFeedCapture.Feed>, positionMs: Long): F1DashFeedCapture.Snapshot = withContext(Dispatchers.IO) {
        var format: Format? = null
        val startedMs = android.os.SystemClock.elapsedRealtime()
        try {
            require(feeds.size in 2..4 && feeds.map { it.label }.distinct().size == feeds.size)
            val perFeed = feeds.map { feed -> offers.getOrPut(feed.label) { loadOffers(feed) } }
            val selected = chooseCommon(perFeed)
            format = selected.first().format
            val playlists = coroutineScope { selected.map { offer -> async { loadMedia(offer) } }.awaitAll() }
            if (playlists.any { !it.hasEndTag }) throw PermanentCaptureException("HLS single-decoder archive capture requires complete replay playlists")
            val useProgramTime = playlists.all { it.hasProgramDateTime }
            val masterStartUs = playlists.first().startTimeUs
            val windows = playlists.map { playlist ->
                playlist.segments.mapIndexed { index, segment ->
                    ArchiveDashSegmentSelector.Candidate(index.toLong(), segment.relativeStartTimeUs +
                        if (useProgramTime) playlist.startTimeUs - masterStartUs else 0L)
                }
            }
            val targetUs = Math.multiplyExact(positionMs, 1000)
            val aligned = ArchiveDashSegmentSelector.selectAligned(windows, targetUs, previousStartUs, 250_000,
                maximumLeadUs = ArchiveBufferPolicy.maximumLeadUs(windows.first(), targetUs))
                ?: return@withContext snapshot(group, format, null, 0,
                    if (previousStartUs == null) CaptureFailure("HLS fragment alignment failure at the archive position", false) else null,
                    "Waiting for the next aligned HLS archive segment")
            val segments = playlists.zip(aligned).map { (playlist, candidate) -> playlist.segments[candidate.number.toInt()] }
            val discontinuities = playlists.zip(segments).map { (playlist, segment) -> playlist.discontinuitySequence + segment.relativeDiscontinuitySequence }
            if (discontinuities.distinct().size != 1) throw PermanentCaptureException("HLS feeds cross incompatible discontinuities")
            if (segments.any { it.hasGapTag }) throw PermanentCaptureException("Selected HLS archive segment is marked as a gap")
            if (segments.any { it.fullSegmentEncryptionKeyUri != null || it.initializationSegment?.fullSegmentEncryptionKeyUri != null }) {
                throw PermanentCaptureException("HLS AES-128 full-segment encryption is unsupported; no key is requested")
            }
            val inits = selected.zip(segments).map { (offer, segment) -> loadInit(offer, segment) }
            val tracks = inits.map { it.report.tracks.singleOrNull { track -> track.handler == "vide" }
                ?: throw PermanentCaptureException("HLS CMAF initialization must contain exactly one video track") }
            if (tracks.any { it.hevcConfiguration == null }) throw PermanentCaptureException("HLS CMAF has no supported HEVC video configuration")
            val clear = tracks.all { !it.encrypted }
            if (!clear && tracks.any { !it.encrypted || it.protectionScheme != "cbcs" }) {
                throw PermanentCaptureException("HLS composition needs all-clear HEVC or compatible CBCS HEVC; mixed protection is unsupported")
            }
            if (clearOutput != null && clearOutput != clear) throw PermanentCaptureException("HLS archive protection changed during composition")
            clearOutput = clear
            if (clear && playlists.any { playlist -> playlist.tags.any { it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE") } }) {
                throw PermanentCaptureException("HLS declares encryption but its selected CMAF track is clear")
            }
            segments.forEach { rememberPssh(it.drmInitData) }
            selected.forEach { it.master?.sessionKeyDrmInitData?.forEach(::rememberPssh) }
            if (!clear) selected.zip(inits).forEach { (offer, init) ->
                ProtectedHevcLiveComposition.recordCmafResponse(group, offer.feed.label, init.bytes, init.report)
            }
            val media = mutableListOf<ByteArray>()
            val fetchStartedMs = android.os.SystemClock.elapsedRealtime()
            var fetchMs = 0L
            var composeMs = 0L
            try {
                // Serial, bounded segment reads reduce the peak memory load on Android TVs.
                selected.zip(segments).forEach { (offer, segment) ->
                    coroutineContext.ensureActive()
                    media += reader.read(UriUtil.resolveToUri(playlists[selected.indexOf(offer)].baseUri, segment.url),
                        MAX_MEDIA_BYTES, segment.byteRangeOffset, segment.byteRangeLength).bytes
                }
                fetchMs = android.os.SystemClock.elapsedRealtime() - fetchStartedMs
                val composeStartedMs = android.os.SystemClock.elapsedRealtime()
                if (mediaToArchiveOffsetUs == null) {
                    val firstFragment = CmafBoxInspector.inspect(media.first(), inits.first().report).fragments
                        .first { it.trackId == tracks.first().trackId }
                    mediaToArchiveOffsetUs = aligned.first().absoluteStartUs - CmafWindowTiming.ticksToUs(
                        requireNotNull(firstFragment.baseDecodeTimeTicks), requireNotNull(tracks.first().timescale))
                }
                if (clear) {
                    clearComposition.compose(selected.mapIndexed { index, offer ->
                        HevcClearCmafGridComposer.Feed(inits[index].bytes, listOf(media[index]), offer.feed.label)
                    })
                } else {
                    selected.forEachIndexed { index, offer ->
                        val report = CmafBoxInspector.inspect(media[index], inits[index].report)
                        if (report.errors.isNotEmpty()) throw PermanentCaptureException("${offer.feed.label}: invalid HLS CMAF fragment")
                        ProtectedHevcLiveComposition.recordCmafResponse(group, offer.feed.label, media[index], report)
                    }
                    ProtectedHevcLiveComposition.rejectionReason(group)?.let { throw PermanentCaptureException("HLS HEVC/CMAF composition rejected: $it") }
                }
                composeMs = android.os.SystemClock.elapsedRealtime() - composeStartedMs
            } finally { media.forEach { it.fill(0) } }
            previousStartUs = aligned.first().absoluteStartUs
            android.util.Log.i("ApexviewSingleDecoder", "HLS_CMAF_CAPTURE feeds=${feeds.size} protection=${if (clear) "CLEAR" else "CBCS"} aligned=PASS composed=${if (clear) clearComposition.hasOutput() else ProtectedHevcLiveComposition.hasOutput(group)} fetchMs=$fetchMs composeMs=$composeMs totalMs=${android.os.SystemClock.elapsedRealtime() - startedMs} archiveClockMs=$positionMs segmentStartUs=${aligned.first().absoluteStartUs}")
            snapshot(group, format, aligned.first().absoluteStartUs, feeds.size)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            val failure = CaptureFailure.from(if (error is IllegalArgumentException || error is IllegalStateException)
                PermanentCaptureException("HLS CMAF validation failed: ${error.message?.replace(Regex("https?://\\S+"), "[URL]")?.take(240)}") else error)
            snapshot(group, format, null, 0, failure)
        }
    }

    fun outputWindow(group: String): ProtectedHevcLiveComposition.OutputWindow? =
        if (clearOutput == true) clearComposition.outputWindow() else ProtectedHevcLiveComposition.outputWindow(group)

    fun widevinePssh(): ByteArray? = pssh?.copyOf()

    fun reset() {
        initializations.values.forEach { it.bytes.fill(0) }; initializations.clear()
        offers.clear(); vodPlaylists.clear(); actual.clear(); previousStartUs = null; mediaToArchiveOffsetUs = null
        pssh?.fill(0); pssh = null; clearOutput = null; clearComposition.reset()
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
                if (playlist.hasEndTag) vodPlaylists[feed.label] = playlist
                listOf(Offer(feed, Uri.parse(playlist.baseUri), null, null))
            }
            else -> throw PermanentCaptureException("${feed.label}: unsupported HLS playlist")
        }
    }

    private fun chooseCommon(perFeed: List<List<Offer>>, preferredHeight: Int? = null, feedCount: Int = perFeed.size): List<Offer> {
        // A direct media playlist has no rendition declarations; hvcC validates it later.
        val declared = perFeed.filter { list -> list.any { it.format != null } }
        if (declared.isEmpty()) return perFeed.map { it.first() }
        val common = declared.map { list -> list.map { it.format!!.width to it.format.height }.toSet() }.reduce { a, b -> a intersect b }
        val legal = common.filter { preferredHeight == null ||
            (it.first > 0 && it.second > 0 && HevcArchiveAtlasPlan.choose(feedCount, it.first, it.second,
                maximumOutputWidth, 2160) != null) }
        val sizes = if (preferredHeight == null) legal else legal.filter { it.second <= preferredHeight }.ifEmpty {
            // Use only representations actually offered by F1, including a larger minimum rung.
            legal.minByOrNull { it.second }?.let(::listOf).orEmpty()
        }
        val size = sizes.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
            ?: throw PermanentCaptureException("No common HEVC rendition fits $feedCount feeds in the composed picture; choose fewer feeds or a lower source rendition")
        return perFeed.map { list -> list.filter { it.format == null || it.format.width == size.first && it.format.height == size.second }
            .maxByOrNull { it.format?.bitrate ?: 0 } ?: throw PermanentCaptureException("HLS common HEVC rendition unavailable") }
    }

    private fun loadMedia(offer: Offer): HlsMediaPlaylist = vodPlaylists[offer.feed.label] ?: run {
        val playlist = parse(offer.uri, offer.master) as? HlsMediaPlaylist ?: throw PermanentCaptureException("HLS variant did not return a media playlist")
        if (playlist.hasEndTag) vodPlaylists[offer.feed.label] = playlist
        playlist
    }

    private fun loadInit(offer: Offer, segment: HlsMediaPlaylist.Segment): Init {
        val map = segment.initializationSegment ?: throw PermanentCaptureException("${offer.feed.label}: HLS has no EXT-X-MAP; MPEG-TS is not HEVC CMAF")
        val playlist = vodPlaylists[offer.feed.label] ?: throw PermanentCaptureException("Archive playlist unavailable")
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
