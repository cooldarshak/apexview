package app.apexview.cast

import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import app.apexview.CaptionPolicy
import app.apexview.tiled.CmafBoxInspector
import app.apexview.tiled.CmafHttpReader
import app.apexview.tiled.HlsProgrammeContext

/** Rolling, source-declared clear WebVTT. Only the derived text track crosses the relay. */
internal class HlsCastText(
    private val reader: CmafHttpReader,
    private val budget: RelayMediaBudget,
    private val programmeIndex: Int,
    private val maxTracks: Int,
) : AutoCloseable {
    private data class Piece(val name: String, val bytes: SharedRelayResource, val startUs: Long, val durationUs: Long)
    private class Track(val index: Int, val language: String, val prefix: String,
                        val init: SharedRelayResource, val report: CmafBoxInspector.Report) : AutoCloseable {
        var lastSequence = -1L
        var lastDiscontinuity = -1
        var serial = 0
        val pieces = ArrayDeque<Piece>()
        override fun close() {
            init.close()
            pieces.forEach { it.bytes.close() }
            pieces.clear()
        }
    }

    private val tracks = linkedMapOf<Int, Track>()
    private var discovered = false

    fun capture(context: HlsProgrammeContext, originUs: Long) {
        val group = context.subtitleGroup ?: return
        for ((index, rendition) in context.master.subtitles.filter {
            it.groupId == group && it.url != null
        }.take(maxTracks).withIndex()) {
            val source = rendition
            if (discovered && index !in tracks) continue
            try {
                val playlist = reader.read(requireNotNull(source.url), 512 * 1024)
                val media = try {
                    HlsPlaylistParser(context.master, null).parse(playlist.uri, playlist.bytes.inputStream()) as HlsMediaPlaylist
                } finally { playlist.bytes.fill(0) }
                require(media.tags.none { it.startsWith("#EXT-X-KEY:") && it.trim() != "#EXT-X-KEY:METHOD=NONE" } &&
                    media.protectionSchemes == null) { "Protected WebVTT is not relayed" }
                val sourceStartUs = if (media.hasProgramDateTime && context.playlistUtcStartUs != null) {
                    Math.addExact(context.playlistSourceStartUs, Math.subtractExact(media.startTimeUs, context.playlistUtcStartUs))
                } else {
                    require(!context.live && media.hasEndTag) { "Rolling text has no verified programme clock" }
                    context.playlistSourceStartUs
                }
                val endOfVideoSegment = Math.addExact(context.sourceSegmentStartUs, context.segmentDurationUs)
                val segments = media.segments.withIndex().filter { (_, segment) ->
                    val start = Math.addExact(sourceStartUs, segment.relativeStartTimeUs)
                    val end = Math.addExact(start, segment.durationUs)
                    Math.addExact(media.discontinuitySequence, segment.relativeDiscontinuitySequence) == context.discontinuity &&
                        start < endOfVideoSegment && end > maxOf(originUs, context.sourceSegmentStartUs - 20_000_000L)
                }.takeLast(3)
                if (segments.isEmpty()) continue
                val track = tracks.getOrPut(index) {
                    val init = WebVttCmaf.initialization()
                    try {
                        val report = CmafBoxInspector.inspect(init)
                        check(report.errors.isEmpty() && report.tracks.single().sampleEntry == "wvtt")
                        val language = CaptionPolicy.normalizeLanguage(source.format.language)
                            .takeUnless { it == CaptionPolicy.AUTO } ?: "und"
                        Track(index, language, "hls-text-$programmeIndex-$index", budget.own(init), report)
                    } catch (failure: Exception) {
                        init.fill(0)
                        throw failure
                    }
                }
                for ((segmentIndex, segment) in segments) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val seq = Math.addExact(media.mediaSequence, segmentIndex.toLong())
                    val disc = Math.addExact(media.discontinuitySequence, segment.relativeDiscontinuitySequence)
                    if (disc < track.lastDiscontinuity || disc == track.lastDiscontinuity && seq <= track.lastSequence) continue
                    val start = Math.addExact(sourceStartUs, segment.relativeStartTimeUs)
                    val end = Math.addExact(start, segment.durationUs)
                    if (!segment.hasGapTag) {
                        val clippedStart = maxOf(start, originUs)
                        // A discontinuity can invalidate any unserved piece mapped across
                        // the boundary. Already leased bytes retain their original contents.
                        val lastEnd = track.pieces.lastOrNull()?.let { it.startUs + it.durationUs + originUs }
                        require(lastEnd == null || clippedStart >= lastEnd) { "Caption segments overlap on the composed timeline" }
                        append(track, media, segment, context, originUs, start, clippedStart, end)
                    }
                    track.lastSequence = seq
                    track.lastDiscontinuity = disc
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException || error is java.io.InterruptedIOException) throw error
                if (!discovered && tracks[index]?.pieces?.isEmpty() == true) tracks.remove(index)?.close()
                android.util.Log.w("PitwallCast", "ROLLING_HLS_CAPTION_UNAVAILABLE type=${error.javaClass.simpleName}")
            }
        }
        discovered = true
    }

    private fun append(track: Track, media: HlsMediaPlaylist, segment: HlsMediaPlaylist.Segment,
                       context: HlsProgrammeContext, originUs: Long, sourceStart: Long,
                       clippedStart: Long, end: Long) {
        require(segment.drmInitData == null && segment.fullSegmentEncryptionKeyUri == null)
        val header = segment.initializationSegment?.let { map ->
            require(map.drmInitData == null && map.fullSegmentEncryptionKeyUri == null)
            readText(media, map, 16 * 1024)
        }
        val input = readText(media, segment, 256 * 1024)
        val cues = RollingWebVtt.cues(input, sourceStart, end, context.sourceSegmentStartUs, context.videoMediaStartUs, header)
        val base = Math.subtractExact(clippedStart, originUs)
        val serial = Math.incrementExact(track.serial)
        val fragment = WebVttCmaf.fragment(serial, base, clippedStart, end, cues)
        var accepted = false
        try {
            val report = CmafBoxInspector.inspect(fragment, track.report)
            val metadata = report.fragments.single()
            require(report.errors.isEmpty() && metadata.trackId == 1L && metadata.timingComplete &&
                metadata.baseDecodeTimeTicks == base && metadata.totalDurationTicks == end - clippedStart)
            track.pieces.addLast(Piece("${track.prefix}-$serial.m4s", budget.own(fragment), base, end - clippedStart))
            accepted = true
            track.serial = serial
            while (track.pieces.size > 3) track.pieces.removeFirst().bytes.close()
        } finally { if (!accepted) fragment.fill(0) }
    }

    private fun readText(media: HlsMediaPlaylist, segment: HlsMediaPlaylist.Segment, limit: Int): String {
        val data = reader.read(UriUtil.resolveToUri(media.baseUri, segment.url), limit,
            segment.byteRangeOffset, segment.byteRangeLength).bytes
        return try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString() }
        finally { data.fill(0) }
    }

    fun appendTo(publication: CastDashPublication) {
        for (track in tracks.values) {
            if (track.pieces.isEmpty()) continue
            val init = "${track.prefix}-init.mp4"
            publication.addResource(init, track.init)
            val timeline = track.pieces.joinToString("") { """<S t="${it.startUs}" d="${it.durationUs}"/>""" }
            val urls = track.pieces.joinToString("") {
                publication.addResource(it.name, it.bytes)
                """<SegmentURL media="${it.name}"/>"""
            }
            publication.addAdaptationSet("""<AdaptationSet id="${100 + programmeIndex * 4 + track.index}" contentType="text" mimeType="application/mp4" lang="${track.language}">
                <Representation id="pitwall-text-$programmeIndex-${track.index}" bandwidth="64000" codecs="wvtt">
                <SegmentList timescale="${WebVttCmaf.TIMESCALE}" presentationTimeOffset="0"><Initialization sourceURL="$init"/>
                <SegmentTimeline>$timeline</SegmentTimeline>$urls</SegmentList></Representation></AdaptationSet>""")
        }
    }

    override fun close() { tracks.values.forEach { it.close() }; tracks.clear() }
}
