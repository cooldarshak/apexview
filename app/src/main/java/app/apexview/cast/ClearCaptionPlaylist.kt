package app.apexview.cast

/** Only a finite clear WebVTT HLS media playlist may be relayed. Source URLs,
 * unknown tags, keys and arbitrary metadata are never emitted to the TV. */
internal object ClearCaptionPlaylist {
    private val safeTags = listOf("#EXT-X-VERSION:", "#EXT-X-TARGETDURATION:",
        "#EXT-X-MEDIA-SEQUENCE:", "#EXT-X-DISCONTINUITY-SEQUENCE:",
        "#EXT-X-PLAYLIST-TYPE:", "#EXT-X-PROGRAM-DATE-TIME:")
    private val validLanguage = Regex("[a-z]{2,3}(-[a-z]{2,8})?")
    const val MAX_SEGMENTS = 8192

    data class Playlist(val text: String, val sourceUris: List<String>)
    fun rewrite(input: String, track: Int): Playlist {
        require(track in 0..15)
        val lines = input.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == "#EXTM3U" && lines.lastOrNull() == "#EXT-X-ENDLIST")
        val output = StringBuilder("#EXTM3U\n")
        val uris = mutableListOf<String>()
        var awaitingUri = false
        var targetDuration = false
        for ((index, line) in lines.drop(1).withIndex()) {
            when {
                line == "#EXT-X-KEY:METHOD=NONE" -> Unit
                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY") ||
                    line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-BYTERANGE:") ||
                    line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-MEDIA:") ||
                    line.startsWith("#EXT-X-DEFINE:") || line.startsWith("#EXT-X-PART:") ->
                    throw IllegalArgumentException("Caption relay requires independent clear WebVTT segments")
                line.startsWith("#EXTINF:") -> {
                    require(!awaitingUri)
                    val rawDuration = line.substringAfter(':').substringBefore(',')
                    require(rawDuration.matches(Regex("[0-9]{1,3}([.][0-9]{1,9})?")))
                    val duration = rawDuration.toBigDecimal()
                    require(duration.signum() > 0 && duration <= java.math.BigDecimal.valueOf(600))
                    output.append("#EXTINF:").append(duration.stripTrailingZeros().toPlainString()).append(",\n")
                    awaitingUri = true
                }
                !line.startsWith('#') -> {
                    require(awaitingUri && uris.size < MAX_SEGMENTS && line.length <= 4096)
                    output.append(segmentName(track, uris.size)).append('\n')
                    uris.add(line)
                    awaitingUri = false
                }
                line == "#EXT-X-ENDLIST" -> { require(!awaitingUri && index == lines.size - 2); output.append(line).append('\n') }
                line == "#EXT-X-DISCONTINUITY" || line == "#EXT-X-GAP" -> output.append(line).append('\n')
                safeTags.any(line::startsWith) -> {
                    val value = line.substringAfter(':')
                    require(line.length <= 160)
                    when (line.substringBefore(':')) {
                        "#EXT-X-PLAYLIST-TYPE" -> require(value in setOf("VOD", "EVENT"))
                        "#EXT-X-PROGRAM-DATE-TIME" -> require(runCatching {
                            java.time.OffsetDateTime.parse(value)
                        }.isSuccess)
                        "#EXT-X-TARGETDURATION" -> {
                            require(value.matches(Regex("[0-9]{1,3}")) && value.toInt() in 1..600)
                            targetDuration = true
                        }
                        else -> require(value.matches(Regex("[0-9]{1,18}")) && value.toLongOrNull() != null)
                    }
                    output.append(line).append('\n')
                }
                else -> Unit // discard unneeded proprietary tags, never their payloads
            }
        }
        require(!awaitingUri && targetDuration && uris.isNotEmpty())
        return Playlist(output.toString(), uris)
    }

    fun segmentName(track: Int, segment: Int) = "cc-$track-$segment.vtt"
    fun playlistName(track: Int) = "cc-$track.m3u8"
    fun masterTag(track: Int, language: String, label: String): String {
        require(track in 0..15 && validLanguage.matches(language))
        val safeLabel = label.filter { it >= ' ' && it != '"' && it != '\\' && it != '\u007f' }.take(64).ifBlank { language }
        return "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"pitwall-cc\",NAME=\"$safeLabel\",LANGUAGE=\"$language\",DEFAULT=NO,AUTOSELECT=YES,URI=\"${playlistName(track)}\"\n"
    }

    fun isWebVtt(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val start = if (bytes.size >= 9 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) 3 else 0
        val magic = "WEBVTT".toByteArray(Charsets.US_ASCII)
        if (bytes.size < start + magic.size || magic.indices.any { bytes[start + it] != magic[it] }) return false
        val end = start + magic.size
        return end == bytes.size || bytes[end] in byteArrayOf(10, 13, 32, 9)
    }
}
