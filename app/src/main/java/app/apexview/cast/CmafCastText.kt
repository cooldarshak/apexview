package app.apexview.cast

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import app.apexview.ManifestProtocol
import app.apexview.tiled.HlsProgrammeContext
import app.apexview.tiled.CmafBoxInspector
import app.apexview.tiled.CmafHttpReader

/** Source-declared clear fragmented text, unchanged bytes and source timestamps.
 * Never fetch keys or synthesize captions for an undeclared representation. */
internal class CmafCastText(
    private val reader: CmafHttpReader,
    private val budget: RelayMediaBudget,
    private val programmeIndex: Int,
    private val maxTracks: Int,
) : AutoCloseable {
    private val hls = HlsCastText(reader, budget, programmeIndex, maxTracks)
    private var hlsMode = false
    private val prefix = "text-$programmeIndex"
    private data class Piece(val name: String, val bytes: SharedRelayResource, val dts: Long, val duration: Long)
    private class Track(val key: String, val index: Int, val language: String, val codec: String,
                        val init: SharedRelayResource, val report: CmafBoxInspector.Report, val scale: Long,
                        val initKey: String, val pto: Long) {
        val pieces = mutableListOf<Piece>()
        var lastStart: Long? = null
        var serial = 0L
        fun close() { init.close(); pieces.forEach { it.bytes.close() }; pieces.clear() }
    }
    private val tracks = linkedMapOf<String, Track>()
    private var discovered = false
    private fun ticks(us: Long, scale: Long) = java.math.BigInteger.valueOf(us)
        .multiply(java.math.BigInteger.valueOf(scale)).divide(java.math.BigInteger.valueOf(1_000_000)).longValueExact()
    private fun codec(rep: Representation): String? = rep.format.codecs?.let {
        when {
            it == "wvtt" -> it
            it.startsWith("stpp") && it.matches(Regex("[A-Za-z0-9.]+")) -> it
            else -> null
        }
    }
    fun capture(url: String, sourceUs: Long, videoStartUs: Long, programme: HlsProgrammeContext?) {
        if (programme != null) {
            hlsMode = true
            hls.capture(programme, videoStartUs)
            return
        }
        if (hlsMode) return
        val response = reader.read(Uri.parse(url), 2 * 1024 * 1024)
        try {
            if (ManifestProtocol.fromBody(response.bytes) != ManifestProtocol.DASH) return
            val manifest = DashManifestParser().parse(response.uri, response.bytes.inputStream())
            val pi = (0 until manifest.periodCount).lastOrNull { manifest.getPeriod(it).startMs * 1000 <= sourceUs } ?: return
            val period = manifest.getPeriod(pi)
            val periodUs = period.startMs * 1000
            val candidates = period.adaptationSets.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { set ->
                set.representations.map { "${set.id}:${it.format.id}" to it }
            }.filter { (_, rep) -> rep.format.drmInitData == null && codec(rep) != null && rep.initializationUri != null && rep.index != null }
            for ((key, rep) in candidates) {
                if (key !in tracks && (discovered || tracks.size >= maxTracks)) continue
                try {
                    val index = requireNotNull(rep.index)
                    val durationUs = manifest.getPeriodDurationUs(pi)
                    val number = index.getSegmentNum(sourceUs - periodUs, durationUs)
                    val start = periodUs + index.getTimeUs(number)
                    val duration = index.getDurationUs(number, durationUs)
                    require(duration > 0 && sourceUs >= start && sourceUs - start < duration)
                    val base = rep.baseUrls.first().url
                    val initialization = requireNotNull(rep.initializationUri)
                    val initUri = initialization.resolveUri(base)
                    val initKey = "$initUri|${initialization.start}|${initialization.length}"
                    var track = tracks[key]
                    if (track == null) {
                        val bytes = reader.read(initUri, 512 * 1024, initialization.start, initialization.length).bytes
                        var owned = true
                        try {
                            val report = CmafBoxInspector.inspect(bytes)
                            val info = report.tracks.single()
                            require(report.errors.isEmpty() && info.handler in setOf("text","subt","sbtl") &&
                                info.sampleEntry in setOf("wvtt","stpp") && !info.encrypted && info.trackEncryption == null &&
                                !info.sampleEncryptionBoxPresent && !info.sampleAuxiliarySizesPresent && !info.sampleAuxiliaryOffsetsPresent &&
                                !info.seigSampleToGroupPresent && info.seigSampleGroupDescription == null && info.sampleDescriptionCount == 1)
                            val scale = requireNotNull(info.timescale).also { require(it > 0) }
                            val pto = ticks(rep.presentationTimeOffsetUs + videoStartUs - periodUs, scale)
                            require(pto >= 0)
                            val lang = rep.format.language?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,35}")) } ?: "und"
                            track = Track(key, (0 until maxTracks).first { slot -> tracks.values.none { it.index == slot } }, lang, requireNotNull(codec(rep)), budget.own(bytes), report, scale, initKey, pto)
                            tracks[key] = track; owned = false
                        } finally { if (owned) bytes.fill(0) }
                    }
                    val t = requireNotNull(track)
                    require(t.initKey == initKey && t.codec == codec(rep) &&
                        t.pto == ticks(rep.presentationTimeOffsetUs + videoStartUs - periodUs, t.scale))
                    if (t.lastStart == start) continue
                    require(t.lastStart == null || start > t.lastStart!!)
                    val segment = index.getSegmentUrl(number)
                    val bytes = reader.read(segment.resolveUri(base), 1024 * 1024, segment.start, segment.length).bytes
                    var transferred = false
                    try {
                        val report = CmafBoxInspector.inspect(bytes, t.report)
                        val f = report.fragments.single()
                        val info = t.report.tracks.single()
                        require(report.errors.isEmpty() && f.trackId == info.trackId && f.timingComplete &&
                            f.sampleDescriptionIndex == 1L && !f.sampleEncryptionBoxPresent && !f.sampleAuxiliarySizesPresent &&
                            !f.sampleAuxiliaryOffsetsPresent && !f.sampleGroupEncryptionPresent)
                        val dts = requireNotNull(f.baseDecodeTimeTicks)
                        val length = requireNotNull(f.totalDurationTicks)
                        require(length > 0 && kotlin.math.abs(dts - ticks(rep.presentationTimeOffsetUs + start - periodUs, t.scale)) <= 1 &&
                            kotlin.math.abs(length - ticks(duration, t.scale)) <= 1)
                        t.pieces.lastOrNull()?.let { require(dts >= it.dts + it.duration) }
                        t.pieces += Piece("$prefix-${t.index}-${++t.serial}.m4s", budget.own(bytes), dts, length)
                        transferred = true; t.lastStart = start
                        while (t.pieces.size > 3) t.pieces.removeAt(0).bytes.close()
                    } finally { if (!transferred) bytes.fill(0) }
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    if (!discovered && tracks[key]?.pieces?.isEmpty() == true) tracks.remove(key)?.close()
                    android.util.Log.w("PitwallCast", "NATIVE_TEXT_RENDITION_REJECTED type=${failure.javaClass.simpleName}")
                }
            }
            discovered = true
        } finally { response.bytes.fill(0) }
    }
    fun appendTo(publication: CastDashPublication) {
        if (hlsMode) { hls.appendTo(publication); return }
        for (t in tracks.values) {
            if (t.pieces.isEmpty()) continue
            val initName = "$prefix-${t.index}-init.mp4"
            publication.addResource(initName, t.init)
            val timeline = t.pieces.joinToString("") { """<S t="${it.dts}" d="${it.duration}"/>""" }
            val urls = t.pieces.joinToString("") {
                publication.addResource(it.name, it.bytes)
                """<SegmentURL media="${it.name}"/>"""
            }
            publication.addAdaptationSet("""<AdaptationSet id="${100 + programmeIndex * 4 + t.index}" contentType="text" mimeType="application/mp4" lang="${t.language}">
                <Representation id="pitwall-text-$programmeIndex-${t.index}" bandwidth="64000" codecs="${t.codec}">
                <SegmentList timescale="${t.scale}" presentationTimeOffset="${t.pto}"><Initialization sourceURL="$initName"/>
                <SegmentTimeline>$timeline</SegmentTimeline>$urls</SegmentList></Representation></AdaptationSet>""")
        }
    }
    override fun close() {
        hls.close()
        tracks.values.forEach { it.close() }
        tracks.clear()
    }
}
