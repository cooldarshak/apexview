package app.apexview.cast

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import app.apexview.tiled.*
import java.util.Base64
import kotlin.math.abs

/** Relays the selected original CMAF audio unchanged. All CDN credentials stay on the phone. */
internal class CmafCastAudio(
    private val reader: CmafHttpReader, private val language: String, val trackIndex: Int,
    private val budget: RelayMediaBudget,
) : AutoCloseable {
    init { require(trackIndex in 0..5) }
    private val namespace = "audio-$trackIndex"
    val representationId = "pitwall-audio-$trackIndex"
    val ready get() = segments.isNotEmpty()
    val latestSourceUs get() = lastSourceUs

    /** Alternatives must use the same verified crypto defaults and codec shape
     * as the initially authorized audio. No additional DRM session or key fetch. */
    fun compatibleWith(primary: CmafCastAudio): Boolean {
        val a = report?.tracks?.singleOrNull() ?: return false
        val b = primary.report?.tracks?.singleOrNull() ?: return false
        return ready && primary.ready && a.sampleEntry == b.sampleEntry &&
            a.originalFormat == b.originalFormat && a.protectionScheme == b.protectionScheme &&
            a.trackEncryption == b.trackEncryption &&
            format?.codecs == primary.format?.codecs &&
            format?.sampleMimeType == primary.format?.sampleMimeType &&
            format?.channelCount == primary.format?.channelCount &&
            format?.sampleRate == primary.format?.sampleRate &&
            ((manifestPssh == null && primary.manifestPssh == null) ||
                (manifestPssh != null && primary.manifestPssh != null &&
                    requireNotNull(manifestPssh).contentEquals(requireNotNull(primary.manifestPssh)))) &&
            kotlin.math.abs(requireNotNull(firstSourceUs) - requireNotNull(primary.firstSourceUs)) <= 250_000L
    }
    private data class Segment(val name: String, val bytes: SharedRelayResource, val dts: Long, val duration: Long)
    private var init: SharedRelayResource? = null
    private var protectionXml = ""
    private var initKey = ""
    private var report: CmafBoxInspector.Report? = null
    private var format: Format? = null
    private val segments = mutableListOf<Segment>()
    private var firstSourceUs: Long? = null
    private var firstDts = 0L
    private var timescale = 0L
    private var serial = 0L
    private var lastSourceUs: Long? = null
    private var manifestPssh: ByteArray? = null
    var encrypted: Boolean = false
        private set

    fun capture(url: String, sourceUs: Long, live: Boolean, programme: HlsProgrammeContext?) {
        if (lastSourceUs == sourceUs) return
        if (programme != null) {
            hls(programme.master, sourceUs, live, programme)
        } else {
            val response = reader.read(Uri.parse(url), 2 * 1024 * 1024)
            try {
                if (app.apexview.ManifestProtocol.fromBody(response.bytes) == app.apexview.ManifestProtocol.HLS)
                    hls(HlsPlaylistParser().parse(response.uri, response.bytes.inputStream()), sourceUs, live, null)
                else dash(response.uri, response.bytes, sourceUs)
            } finally { response.bytes.fill(0) }
        }
        lastSourceUs = sourceUs
    }
    private fun dash(uri: Uri, bytes: ByteArray, sourceUs: Long) {
        val manifest = DashManifestParser().parse(uri, bytes.inputStream())
        val periodIndex = (0 until manifest.periodCount).lastOrNull { manifest.getPeriod(it).startMs * 1000 <= sourceUs } ?: 0
        val period = manifest.getPeriod(periodIndex)
        val representation = period.adaptationSets.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { it.representations }
            .maxWithOrNull(compareBy<androidx.media3.exoplayer.dash.manifest.Representation> { it.format.language == language }.thenBy { it.format.bitrate })
            ?: throw PermanentCaptureException("No separate CMAF audio representation is available for casting")
        val index = requireNotNull(representation.index) { "Cast audio requires an in-manifest segment index" }
        val base = representation.baseUrls.first().url
        val initUri = requireNotNull(representation.initializationUri)
        acceptFormat(representation.format)
        acceptPssh(representation.format.drmInitData)
        installInit(initUri.resolveUri(base), initUri.start, initUri.length)
        val duration = manifest.getPeriodDurationUs(periodIndex)
        val number = index.getSegmentNum(sourceUs - period.startMs * 1000, duration)
        val start = period.startMs * 1000 + index.getTimeUs(number)
        require(abs(start - sourceUs) <= 250_000L) { "Audio and composed video segment starts are not aligned" }
        val segment = index.getSegmentUrl(number)
        append(reader.read(segment.resolveUri(base), 8 * 1024 * 1024, segment.start, segment.length).bytes, start)
    }
    private fun hls(playlist: HlsPlaylist, sourceUs: Long, live: Boolean, programme: HlsProgrammeContext?) {
        val media = when (playlist) {
            is HlsMediaPlaylist -> playlist
            is HlsMultivariantPlaylist -> {
                val rendition = playlist.audios.filter { it.url != null &&
                    (programme == null || it.groupId == programme.audioGroup) }
                    .maxWithOrNull(compareBy<HlsMultivariantPlaylist.Rendition> { it.format.language == language }
                        .thenBy { (it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 })
                    ?: throw PermanentCaptureException("Casting requires a separate native audio rendition")
                acceptFormat(rendition.format)
                val response = reader.read(requireNotNull(rendition.url), 2 * 1024 * 1024)
                try { HlsPlaylistParser(playlist, null).parse(response.uri, response.bytes.inputStream()) as? HlsMediaPlaylist
                    ?: throw PermanentCaptureException("Audio rendition did not return a media playlist") }
                finally { response.bytes.fill(0) }
            }
            else -> throw PermanentCaptureException("Unsupported audio playlist")
        }
        val origin = if (live) {
            require(media.hasProgramDateTime) { "Live audio requires program date time" }; media.startTimeUs
        } else if (programme != null && media.hasProgramDateTime && programme.playlistUtcStartUs != null)
            Math.addExact(programme.playlistSourceStartUs, Math.subtractExact(media.startTimeUs, programme.playlistUtcStartUs))
        else 0L
        val segment = media.segments.minByOrNull { abs(origin + it.relativeStartTimeUs - sourceUs) }
            ?: throw PermanentCaptureException("Audio playlist is empty")
        segment.drmInitData?.let { acceptPssh(it) }
        val start = origin + segment.relativeStartTimeUs
        require(abs(start - sourceUs) <= 250_000L) { "Audio and composed video timelines are not aligned" }
        require(segment.fullSegmentEncryptionKeyUri == null) { "HLS AES-128 key retrieval is not supported" }
        val map = requireNotNull(segment.initializationSegment) { "Audio is not CMAF" }
        installInit(UriUtil.resolveToUri(media.baseUri, map.url), map.byteRangeOffset, map.byteRangeLength)
        append(reader.read(UriUtil.resolveToUri(media.baseUri, segment.url), 8 * 1024 * 1024,
            segment.byteRangeOffset, segment.byteRangeLength).bytes, start)
    }
    private fun acceptFormat(next: Format) {
        format?.let { previous ->
            require(previous.codecs == next.codecs && previous.sampleMimeType == next.sampleMimeType &&
                previous.channelCount == next.channelCount && previous.sampleRate == next.sampleRate) {
                "Audio representation changed; reconnect casting"
            }
        }
        format = next
    }
    private fun acceptPssh(drm: androidx.media3.common.DrmInitData?) {
        val next = drm?.let { (0 until it.schemeDataCount).map { i -> it.get(i) }
            .firstOrNull { it.uuid == C.WIDEVINE_UUID }?.data }
        if (initKey.isNotEmpty()) {
            require(if (manifestPssh == null) next == null else next != null && manifestPssh!!.contentEquals(next)) {
                "Audio protection metadata changed; reconnect casting"
            }
        } else {
            manifestPssh?.fill(0); manifestPssh = next?.copyOf()
        }
    }
    private fun installInit(uri: Uri, start: Long, length: Long) {
        val key = "$uri|$start|$length"
        if (key == initKey) return
        require(initKey.isEmpty()) { "Audio initialization changed; reconnect casting" }
        val bytes = reader.read(uri, 2 * 1024 * 1024, start, length).bytes
        var transferred = false
        try {
            val inspected = CmafBoxInspector.inspect(bytes)
            require(inspected.errors.isEmpty() && inspected.tracks.size == 1 && inspected.tracks.single().handler == "soun") { "Cast audio initialization must contain one audio track" }
            val track = inspected.tracks.single()
            require(!track.encrypted || track.protectionScheme in setOf("cenc", "cbcs")) { "Cast audio protection is unsupported" }
            val verifiedTimescale = requireNotNull(track.timescale).also { require(it > 0) }
            val protection = if (track.encrypted)
                ProtectedHevcComposedDashPackageBuilder.protectionXml(bytes, manifestPssh, requireNotNull(track.protectionScheme))
            else ""
            init = budget.own(bytes)
            protectionXml = protection
            report = inspected; initKey = key; encrypted = track.encrypted
            timescale = verifiedTimescale
            transferred = true
        } finally { if (!transferred) bytes.fill(0) }
    }
    private fun append(bytes: ByteArray, sourceUs: Long) {
        var transferred = false
        try {
            val metadata = CmafBoxInspector.inspect(bytes, requireNotNull(report))
            require(metadata.errors.isEmpty() && metadata.fragments.size == 1) { "Cast audio fragment is invalid" }
            val f = metadata.fragments.single()
            require(f.trackId == report!!.tracks.single().trackId && f.timingComplete && f.sampleCount > 0) { "Cast audio fragment does not match its initialization" }
            val dts = requireNotNull(f.baseDecodeTimeTicks); val duration = requireNotNull(f.totalDurationTicks)
            require(duration > 0)
            if (encrypted) require(f.sampleEncryptionBoxPresent && f.sampleEncryptionStructureComplete) { "Cast audio lost its encryption metadata" }
            else require(!f.sampleEncryptionBoxPresent && !f.sampleAuxiliarySizesPresent &&
                !f.sampleAuxiliaryOffsetsPresent && !f.sampleGroupEncryptionPresent) { "Clear Cast audio contains encryption metadata" }
            require(dts >= 0 && dts <= Long.MAX_VALUE - duration)
            segments.lastOrNull()?.let { require(Math.addExact(it.dts, it.duration) == dts) { "Cast audio has a timeline gap" } }
            if (firstSourceUs == null) { firstSourceUs = sourceUs; firstDts = dts }
            val retained = budget.own(bytes)
            segments += Segment("$namespace-${++serial}.m4s", retained, dts, duration)
            transferred = true
            while (segments.size > ArchiveBufferPolicy.RETAINED_SEGMENTS) segments.removeAt(0).bytes.close()
        } finally { if (!transferred) bytes.fill(0) }
    }
    fun appendTo(publication: CastDashPublication, videoSourceStartUs: Long) {
        require(segments.isNotEmpty()) { "No audio is ready" }
        val offsetUs = Math.subtractExact(requireNotNull(firstSourceUs), videoSourceStartUs)
        val pto = Math.subtractExact(firstDts, scale(offsetUs))
        require(pto >= 0)
        val timeline = segments.joinToString("") { "<S t=\"${it.dts}\" d=\"${it.duration}\"/>" }
        val urls = segments.joinToString("") { "<SegmentURL media=\"${it.name}\"/>" }
        val codec = format?.codecs?.takeIf { it.matches(Regex("[A-Za-z0-9.,]+")) } ?: "mp4a.40.2"
        val audio = """<AdaptationSet id="${10 + trackIndex}" contentType="audio" mimeType="audio/mp4" segmentAlignment="true">$protectionXml
          <Representation id="$representationId" bandwidth="${format?.bitrate?.coerceAtLeast(1) ?: 128000}" codecs="$codec">
            <SegmentList timescale="$timescale" presentationTimeOffset="$pto"><Initialization sourceURL="$namespace-init.mp4"/>
              <SegmentTimeline>$timeline</SegmentTimeline>$urls</SegmentList></Representation></AdaptationSet>"""
        publication.addResource("$namespace-init.mp4", requireNotNull(init))
        segments.forEach { publication.addResource(it.name, it.bytes) }
        publication.addAdaptationSet(audio)
    }
    private fun scale(us: Long): Long = java.math.BigInteger.valueOf(us).multiply(java.math.BigInteger.valueOf(timescale))
        .divide(java.math.BigInteger.valueOf(1_000_000)).longValueExact()
    override fun close() {
        init?.close()
        init = null
        segments.forEach { it.bytes.close() }
        segments.clear()
        manifestPssh?.fill(0)
        manifestPssh = null
        protectionXml = ""
    }
}
