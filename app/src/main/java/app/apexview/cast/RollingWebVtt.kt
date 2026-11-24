package app.apexview.cast

/** Parse only real WebVTT cues and translate their HLS media timestamps to the video clock. */
internal object RollingWebVtt {
    private const val MPEGTS_PERIOD = 1L shl 33
    private const val MAX_CUES = 256
    private val timestamp = Regex("""(?:(\d{2,8}):)?([0-5]\d):([0-5]\d)\.(\d{3})""")
    private val interval = Regex("""(\S+)[ \t]+-->[ \t]+(\S+)(?:[ \t]+(.*))?""")
    private val blocks = Regex("""\n[ \t]*\n+""")

    data class Cue(val startUs: Long, val endUs: Long, val id: String?, val settings: String, val text: String)

    fun cues(
        input: String,
        segmentStartUs: Long,
        segmentEndUs: Long,
        videoStartUs: Long,
        videoMediaStartUs: Long,
        mapHeader: String? = null,
    ): List<Cue> {
        require(input.length <= 256 * 1024 && segmentEndUs > segmentStartUs)
        val body = normalize(input)
        val ownHeader = body.lineSequence().firstOrNull()?.let(::hasHeader) == true
        val sections = body.split(blocks).filter(String::isNotBlank)
        val header = if (ownHeader) sections.first() else normalize(requireNotNull(mapHeader)).substringBefore("\n\n")
        require(hasHeader(header.substringBefore('\n'))) { "Missing WebVTT header" }
        val maps = header.lineSequence().filter { it.startsWith("X-TIMESTAMP-MAP") }.toList()
        require(maps.size <= 1)
        val localAndPts = maps.firstOrNull()?.let(::parseMap) ?: (0L to 0L)
        val referencePts = Math.floorDiv(Math.multiplyExact(videoMediaStartUs, 9), 100)
        val epoch = Math.floorDiv(Math.addExact(referencePts - localAndPts.second, MPEGTS_PERIOD / 2), MPEGTS_PERIOD)
        val unwrapped = Math.addExact(Math.multiplyExact(epoch, MPEGTS_PERIOD), localAndPts.second)
        val mediaUs = Math.floorDiv(Math.multiplyExact(unwrapped, 100), 9)
        val offset = Math.subtractExact(Math.addExact(videoStartUs, Math.subtractExact(mediaUs, videoMediaStartUs)),
            localAndPts.first)

        return buildList {
            for (section in if (ownHeader) sections.drop(1) else sections) {
                val lines = section.lines()
                val head = lines.first()
                if (head == "NOTE" || head.startsWith("NOTE ") || head.startsWith("NOTE\t") ||
                    head == "STYLE" || head == "REGION") continue
                val hasId = "-->" !in head
                val time = interval.matchEntire(lines.getOrNull(if (hasId) 1 else 0).orEmpty())
                    ?: throw IllegalArgumentException("Invalid WebVTT cue times")
                val (start, end, settings) = time.destructured
                val startUs = Math.addExact(parseTime(start), offset)
                val endUs = Math.addExact(parseTime(end), offset)
                require(endUs > startUs)
                // CMAF and MPEG-TS round at different timescales. Allow 2 ms for that
                // rounding only, then clip to the declared HLS segment boundaries.
                require(endUs > segmentStartUs - 2_000L && startUs < segmentEndUs + 2_000L) {
                    "WebVTT cue does not match the video clock"
                }
                val clippedStart = maxOf(startUs, segmentStartUs)
                val clippedEnd = minOf(endUs, segmentEndUs)
                if (clippedStart >= clippedEnd) continue
                require(size < MAX_CUES) { "Too many captions in one HLS segment" }
                add(Cue(clippedStart, clippedEnd, head.takeIf { hasId }, settings,
                    lines.drop(if (hasId) 2 else 1).joinToString("\n")))
            }
        }
    }

    private fun normalize(text: String): String = text.removePrefix("\uFEFF")
        .replace("\r\n", "\n").replace('\r', '\n')

    private fun hasHeader(line: String): Boolean =
        line == "WEBVTT" || line.startsWith("WEBVTT ") || line.startsWith("WEBVTT\t")

    private fun parseMap(line: String): Pair<Long, Long> {
        require(line.startsWith("X-TIMESTAMP-MAP="))
        val fields = line.substringAfter('=').split(',').map(String::trim)
        require(fields.size == 2)
        val local = fields.single { it.startsWith("LOCAL:") }.substringAfter(':')
        val pts = fields.single { it.startsWith("MPEGTS:") }.substringAfter(':').toLong()
        require(pts in 0 until MPEGTS_PERIOD)
        return parseTime(local) to pts
    }

    private fun parseTime(raw: String): Long {
        val (hours, minutes, seconds, ms) = requireNotNull(timestamp.matchEntire(raw)).destructured
        return ((hours.toLongOrNull() ?: 0L) * 3_600_000 + minutes.toLong() * 60_000 +
            seconds.toLong() * 1_000 + ms.toLong()) * 1_000
    }
}
