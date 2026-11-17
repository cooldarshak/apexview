package app.apexview.cast

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import app.apexview.tiled.*
import java.util.Base64
import kotlin.math.abs

/** Relays the selected original CMAF audio unchanged. All CDN credentials stay on the phone. */
internal class CmafCastAudio(private val reader: CmafHttpReader, private val language: String) : AutoCloseable {
    data class Segment(val name: String, val bytes: ByteArray, val dts: Long, val duration: Long)
    private var init = ByteArray(0)
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

    fun capture(url: String, sourceUs: Long, live: Boolean) {
        if (lastSourceUs == sourceUs) return
        val response = reader.read(Uri.parse(url), 2 * 1024 * 1024)
        try {
            if (response.bytes.toString(Charsets.UTF_8).trimStart().startsWith("#EXTM3U")) hls(response.uri, response.bytes, sourceUs, live)
            else dash(response.uri, response.bytes, sourceUs)
        } finally { response.bytes.fill(0) }
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
        format = representation.format
        val drm = format?.drmInitData
        manifestPssh?.fill(0)
        manifestPssh = drm?.let { (0 until it.schemeDataCount).map { i -> it.get(i) }.firstOrNull { it.uuid == C.WIDEVINE_UUID }?.data?.copyOf() }
        installInit(initUri.resolveUri(base), initUri.start, initUri.length)
        val duration = manifest.getPeriodDurationUs(periodIndex)
        val number = index.getSegmentNum(sourceUs - period.startMs * 1000, duration)
        val start = period.startMs * 1000 + index.getTimeUs(number)
        require(abs(start - sourceUs) <= 250_000L) { "Audio and composed video segment starts are not aligned" }
        val segment = index.getSegmentUrl(number)
        append(reader.read(segment.resolveUri(base), 8 * 1024 * 1024, segment.start, segment.length).bytes, start)
    }
    private fun hls(uri: Uri, bytes: ByteArray, sourceUs: Long, live: Boolean) {
        val playlist = HlsPlaylistParser().parse(uri, bytes.inputStream())
        var base = uri
        val media = when (playlist) {
            is HlsMediaPlaylist -> playlist
            is HlsMultivariantPlaylist -> {
                val rendition = playlist.audios.filter { it.url != null }
                    .maxWithOrNull(compareBy<HlsMultivariantPlaylist.Rendition> { it.format.language == language }
                        .thenBy { (it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 })
                    ?: throw PermanentCaptureException("Casting requires a separate native audio rendition")
                format = rendition.format
                base = rendition.url!!
                val response = reader.read(base, 2 * 1024 * 1024)
                try { HlsPlaylistParser(playlist, null).parse(response.uri, response.bytes.inputStream()) as? HlsMediaPlaylist
                    ?: throw PermanentCaptureException("Audio rendition did not return a media playlist") }
                finally { response.bytes.fill(0) }
            }
            else -> throw PermanentCaptureException("Unsupported audio playlist")
        }
        val origin = if (live) {
            require(media.hasProgramDateTime) { "Live audio requires program date time" }; media.startTimeUs
        } else 0L
        val segment = media.segments.minByOrNull { abs(origin + it.relativeStartTimeUs - sourceUs) }
            ?: throw PermanentCaptureException("Audio playlist is empty")
        segment.drmInitData?.let { drm ->
            manifestPssh?.fill(0)
            manifestPssh = (0 until drm.schemeDataCount).map { drm.get(it) }.firstOrNull { it.uuid == C.WIDEVINE_UUID }?.data?.copyOf()
        }
        val start = origin + segment.relativeStartTimeUs
        require(abs(start - sourceUs) <= 250_000L) { "Audio and composed video timelines are not aligned" }
        require(segment.fullSegmentEncryptionKeyUri == null) { "HLS AES-128 key retrieval is not supported" }
        val map = requireNotNull(segment.initializationSegment) { "Audio is not CMAF" }
        base = Uri.parse(media.baseUri)
        installInit(UriUtil.resolveToUri(base.toString(), map.url), map.byteRangeOffset, map.byteRangeLength)
        append(reader.read(UriUtil.resolveToUri(base.toString(), segment.url), 8 * 1024 * 1024,
            segment.byteRangeOffset, segment.byteRangeLength).bytes, start)
    }
    private fun installInit(uri: Uri, start: Long, length: Long) {
        val key = "$uri|$start|$length"
        if (key == initKey) return
        require(initKey.isEmpty()) { "Audio initialization changed; reconnect casting" }
        val bytes = reader.read(uri, 2 * 1024 * 1024, start, length).bytes
        try {
            val inspected = CmafBoxInspector.inspect(bytes)
            require(inspected.errors.isEmpty() && inspected.tracks.size == 1 && inspected.tracks.single().handler == "soun") { "Cast audio initialization must contain one audio track" }
            val track = inspected.tracks.single()
            require(!track.encrypted || track.protectionScheme in setOf("cenc", "cbcs")) { "Cast audio protection is unsupported" }
            init = bytes.copyOf(); report = inspected; initKey = key; encrypted = track.encrypted
            timescale = requireNotNull(track.timescale).also { require(it > 0) }
        } finally { bytes.fill(0) }
    }
    private fun append(bytes: ByteArray, sourceUs: Long) {
        try {
            val metadata = CmafBoxInspector.inspect(bytes, requireNotNull(report))
            require(metadata.errors.isEmpty() && metadata.fragments.size == 1) { "Cast audio fragment is invalid" }
            val f = metadata.fragments.single()
            require(f.trackId == report!!.tracks.single().trackId && f.timingComplete && f.sampleCount > 0) { "Cast audio fragment does not match its initialization" }
            val dts = requireNotNull(f.baseDecodeTimeTicks); val duration = requireNotNull(f.totalDurationTicks)
            require(duration > 0)
            if (encrypted) require(f.sampleEncryptionBoxPresent && f.sampleEncryptionStructureComplete) { "Cast audio lost its encryption metadata" }
            if (firstSourceUs == null) { firstSourceUs = sourceUs; firstDts = dts }
            segments.lastOrNull()?.let { require(it.dts + it.duration == dts) { "Cast audio has a timeline gap" } }
            segments += Segment("audio-${++serial}.m4s", bytes.copyOf(), dts, duration)
            while (segments.size > ArchiveBufferPolicy.RETAINED_SEGMENTS) segments.removeAt(0).bytes.fill(0)
        } finally { bytes.fill(0) }
    }
    fun appendTo(video: ProtectedHevcComposedDashPackageBuilder.Package, videoSourceStartUs: Long): ProtectedHevcComposedDashPackageBuilder.Package {
        require(segments.isNotEmpty()) { "No audio is ready" }
        val offsetUs = requireNotNull(firstSourceUs) - videoSourceStartUs
        val pto = firstDts - scale(offsetUs)
        require(pto >= 0)
        val resources = video.resources.toMutableMap()
        resources["audio-init.mp4"] = init.copyOf()
        segments.forEach { resources[it.name] = it.bytes.copyOf() }
        val protection = if (encrypted) ProtectedHevcComposedDashPackageBuilder.protectionXml(init, manifestPssh, requireNotNull(report!!.tracks.single().protectionScheme)) else ""
        val timeline = segments.joinToString("") { "<S t=\"${it.dts}\" d=\"${it.duration}\"/>" }
        val urls = segments.joinToString("") { "<SegmentURL media=\"${it.name}\"/>" }
        val codec = format?.codecs?.takeIf { it.matches(Regex("[A-Za-z0-9.,]+")) } ?: "mp4a.40.2"
        val audio = """<AdaptationSet id="2" contentType="audio" mimeType="audio/mp4" segmentAlignment="true">$protection
          <Representation id="pitwall-audio" bandwidth="${format?.bitrate?.coerceAtLeast(1) ?: 128000}" codecs="$codec">
            <SegmentList timescale="$timescale" presentationTimeOffset="$pto"><Initialization sourceURL="audio-init.mp4"/>
              <SegmentTimeline>$timeline</SegmentTimeline>$urls</SegmentList></Representation></AdaptationSet>"""
        val manifest = video.manifest.replace("</Period>", "$audio</Period>")
        resources["manifest.mpd"] = manifest.toByteArray()
        return video.copy(manifest = manifest, resources = resources)
    }
    private fun scale(us: Long): Long = java.math.BigInteger.valueOf(us).multiply(java.math.BigInteger.valueOf(timescale))
        .divide(java.math.BigInteger.valueOf(1_000_000)).longValueExact()
    override fun close() { init.fill(0); init = ByteArray(0); segments.forEach { it.bytes.fill(0) }; segments.clear(); manifestPssh?.fill(0) }
}
