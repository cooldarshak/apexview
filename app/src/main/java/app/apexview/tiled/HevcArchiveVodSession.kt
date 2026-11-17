package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal open class ArchiveVodBackendRequired(message: String) : IOException(message)
internal class ProtectedArchiveBackendRequired : ArchiveVodBackendRequired("Protected archive requires the existing Widevine composition backend")
internal class ArchiveVodMediaException(val reason: String, val retryable: Boolean) : IOException(reason)

internal class ArchiveVodLoadErrorPolicy : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val failure = generateSequence(info.exception as Throwable) { it.cause }.filterIsInstance<ArchiveVodMediaException>().firstOrNull()
        return if (failure?.retryable == false) C.TIME_UNSET else super.getRetryDelayMsFor(info)
    }
}

/**
 * Tizen-style replay delivery, implemented natively: one finite MPD, numbered on-demand
 * composed segments, a bounded cache and one warm-ahead task. No source player clock drives it.
 * Audio is supplied to the same Media3 player through an audio-only native HLS media source.
 * The clear composer independently rejects protected tracks; no DRM data is removed.
 */
internal class HevcArchiveVodSession(
    private val reader: CmafHttpReader,
    private val inputs: List<Input>,
    private val clockLabel: String,
    private val maximumOutputWidth: Int = 4096,
    val audioUri: Uri? = null,
    val audioUris: Map<String, Uri> = emptyMap(),
) : AutoCloseable {
    class Input(val label: String, val playlist: HlsMediaPlaylist, val format: Format?,
                val initialization: ByteArray, val report: CmafBoxInspector.Report)
    private data class Group(val startUs: Long, val durationUs: Long, val indexes: List<Int>)
    private val groups = align(inputs, clockLabel)
    private val cache = LinkedHashMap<Int, ByteArray>(4, 0.75f, true)
    private val cacheLock = Any()
    private val composeLock = Any()
    // A seek needs one fragment from each camera. Run those independent I/O requests
    // concurrently, bounded to four requests even for larger selections.
    private val fetchers = Executors.newFixedThreadPool(inputs.size.coerceIn(1, 4)) { r -> Thread(r, "ApexviewVodFetch").apply { isDaemon = true } }
    private val warmer = Executors.newSingleThreadExecutor { r -> Thread(r, "ApexviewVodWarm").apply { isDaemon = true } }
    private val warmLock = Any()
    private var warmNumber: Int? = null
    @Volatile private var warmTask: Future<*>? = null
    @Volatile private var closed = false
    private val owners = java.util.concurrent.atomic.AtomicInteger(1)
    private val baseReleased = java.util.concurrent.atomic.AtomicBoolean(false)
    fun acquireLease(): AutoCloseable {
        synchronized(cacheLock) { checkOpen(); owners.incrementAndGet() }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable { if (released.compareAndSet(false, true)) releaseOwner() }
    }
    private var initialization: ByteArray? = null
    private var presentationOffsetTicks: Long? = null
    private var timescale: Long = 0
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var frameRate: Double = 0.0
        private set
    var codedWidth: Int = 0
        private set
    var codedHeight: Int = 0
        private set
    val durationUs: Long get() = groups.last().let { it.startUs + it.durationUs }
    val sourceLabels: List<String> get() = inputs.map { it.label }
    val segmentCount: Int get() = groups.size
    val selectedHeight: Int? get() = inputs.first().format?.height
    val archiveStartTimeMs: Long? get() = inputs.firstOrNull { it.label == clockLabel }?.playlist?.let {
        if (it.hasProgramDateTime) it.startTimeUs / 1000L else null
    }

    /** A fixed probe proves composition. It is never required to catch another player's clock. */
    fun prepare(positionMs: Long) {
        require(inputs.size in 1..app.apexview.AppState.MAX_SINGLE_DECODER_FEEDS)
        inputs.forEach { input ->
            val track = input.report.tracks.single { it.handler == "vide" }
            if (track.encrypted) throw ProtectedArchiveBackendRequired()
            require(track.hevcConfiguration != null) { "Archive input is not HEVC CMAF" }
        }
        val probe = segmentAt(positionMs.coerceAtLeast(0) * 1000L)
        composeOrRead(probe).fill(0)
        // Validate the complete finite manifest with Media3 before any decoder is released.
        val parsed = androidx.media3.exoplayer.dash.manifest.DashManifestParser().parse(MANIFEST_URI,
            manifest().inputStream())
        require(!parsed.dynamic && parsed.periodCount == 1) { "Archive MPD must have one finite period" }
        android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_PREPARED feeds=${inputs.size} segments=$segmentCount " +
            "sourceHeight=$selectedHeight output=${width}x$height probe=$probe fullFrames=true clock=ONE_PLAYER")
    }

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { ResourceDataSource(this) }

    fun segmentAt(positionUs: Long): Int = groups.indexOfLast { it.startUs <= positionUs }.coerceAtLeast(0)

    /** Stop stale warm-ahead work as soon as a user seeks to a different fragment. */
    fun prioritizeSeek(positionMs: Long) {
        val target = segmentAt(positionMs.coerceAtLeast(0) * 1000L)
        synchronized(warmLock) {
            if (warmNumber != target && warmTask?.isDone == false) warmTask?.cancel(true)
        }
    }

    fun segment(number: Int): ByteArray {
        synchronized(warmLock) {
            if (warmNumber != number && warmTask?.isDone == false) warmTask?.cancel(true)
        }
        val bytes = composeOrRead(number)
        synchronized(warmLock) {
            if (!closed && number < groups.lastIndex && (warmTask == null || warmTask!!.isDone)) {
                warmNumber = number + 1
                warmTask = warmer.submit { runCatching { composeOrRead(number + 1).fill(0) } }
            }
        }
        return bytes
    }

    private fun composeOrRead(number: Int): ByteArray = synchronized(composeLock) {
        checkOpen()
        require(number in groups.indices) { "Archive segment is outside the finite timeline" }
        val cached = synchronized(cacheLock) { cache[number]?.copyOf() }
        if (cached != null) return@synchronized cached
        val started = System.nanoTime()
        val batches = mutableListOf<List<ByteArray>>()
        try {
            val anchor = number
            batches += fetch(number)
            // A finite DASH index may seek directly to any advertised chunk. A continuation
            // fragment cannot be advertised as an independent seek point merely because an
            // earlier GOP was inspected. Never feed a decoder a missing-reference picture.
            require(randomAccess(batches.single())) {
                "Archive segment starts mid-GOP; independent CMAF segment starts are required for native replay seeking"
            }
            val fetchMs = (System.nanoTime() - started) / 1_000_000L
            val firstTrack = inputs.first().report.tracks.single { it.handler == "vide" }
            val config = requireNotNull(firstTrack.hevcConfiguration)
            val sourceSps = config.sps.single()
            val sourcePps = config.pps.single()
            val columns = if (sourcePps.tilesEnabled) sourcePps.numTileColumns else 1
            val rows = if (sourcePps.tilesEnabled) sourcePps.numTileRows else 1
            require(sourceSps.displayWidth.toLong() * inputs.size <= maximumOutputWidth) {
                "Full archive feeds exceed decoder width; choose a lower common HEVC rendition"
            }
            val trackScale = requireNotNull(firstTrack.timescale)
            val firstFragment = CmafBoxInspector.inspect(batches.first().first(), inputs.first().report).fragments.single()
            val step = firstFragment.sampleTimings.map { requireNotNull(it.durationTicks) }.distinct().single()
            val divisor = java.math.BigInteger.valueOf(trackScale).gcd(java.math.BigInteger.valueOf(step)).longValueExact()
            val cells = inputs.indices.flatMap { feed -> (0 until rows).flatMap { row -> (0 until columns).map { col ->
                HevcTizenGridComposer.CellSelection(feed, row * columns + col, feed * columns + col, row)
            } } }
            val ownedInits = synchronized(cacheLock) { checkOpen(); inputs.map { it.initialization.copyOf() } }
            val composingFeeds = inputs.mapIndexed { i, input -> HevcClearCmafGridComposer.Feed(ownedInits[i], batches.map { it[i] }, input.label) }
            val composed = try { if (inputs.size == 1) HevcClearCmafGridComposer.passThrough(composingFeeds.single())
                else HevcClearCmafGridComposer.compose(HevcClearCmafGridComposer.Request(
                composingFeeds,
                HevcOutputGridDimensions(columns * inputs.size, rows), cells,
                firstOutputSequenceNumber = anchor + 1L,
                frameRateNumerator = (trackScale / divisor).toInt(), frameRateDenominator = (step / divisor).toInt()))
            } finally { ownedInits.forEach { it.fill(0) } }
            try {
                val report = CmafBoxInspector.inspect(composed.initializationSegment)
                val outputTrack = report.tracks.single()
                // Container sync flags are insufficient proof of random access. The clear
                // writer emits moof + mdat containing length-prefixed VCL NALs only.
                val outputBytes = composed.mediaFragments.single()
                val mdat = java.nio.ByteBuffer.wrap(outputBytes).int
                val firstNal = mdat + 8 + config.nalLengthSize
                require(inputs.size == 1 || ((outputBytes[firstNal].toInt() ushr 1) and 63) in 16..21) {
                    "Archive sync sample does not begin with a real HEVC IRAP picture"
                }
                val last = CmafBoxInspector.inspect(composed.mediaFragments.last(), report).fragments.single()
                val offset = requireNotNull(last.baseDecodeTimeTicks) - scaleUs(groups[number].startUs, trackScale)
                synchronized(cacheLock) {
                    checkOpen()
                    if (initialization == null) {
                        initialization = composed.initializationSegment.copyOf()
                        timescale = trackScale
                        presentationOffsetTicks = offset
                        width = requireNotNull(outputTrack.width)
                        height = requireNotNull(outputTrack.height)
                        frameRate = trackScale.toDouble() / step
                        codedWidth = composed.codedWidth
                        codedHeight = composed.codedHeight
                    } else {
                        require(initialization!!.contentEquals(composed.initializationSegment)) { "Archive representation changed its initialization" }
                        require(abs(requireNotNull(presentationOffsetTicks) - offset) <= 1L) { "Archive decode timestamps differ from the VOD timeline" }
                    }
                    val output = composed.mediaFragments.last().copyOf()
                    cache.put(number, output)?.fill(0)
                    while (cache.size > 3) cache.remove(cache.keys.first())?.fill(0)
                    android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_SEGMENT number=$number fetchMs=$fetchMs " +
                        "composeMs=${(System.nanoTime() - started) / 1_000_000L - fetchMs} durationMs=${groups[number].durationUs / 1000L} clock=PLAYER_REQUEST cache=${cache.size}")
                    output.copyOf()
                }
            } finally {
                composed.initializationSegment.fill(0)
                composed.mediaFragments.forEach { it.fill(0) }
            }
        } catch (error: Exception) {
            if (error is ProtectedArchiveBackendRequired) throw error
            val root = (error as? java.util.concurrent.ExecutionException)?.cause as? Exception ?: error
            val message = if (root is IllegalArgumentException || root is IllegalStateException) root.message.orEmpty()
                .replace(Regex("https?://\\S+"), "[URL]").take(220) else CaptureFailure.from(root).reason
            val retryable = root !is IllegalArgumentException && root !is IllegalStateException && CaptureFailure.from(root).retryable
            throw ArchiveVodMediaException("Archive segment $number: $message", retryable)
        } finally { batches.flatten().forEach { it.fill(0) } }
    }

    private fun fetch(number: Int): List<ByteArray> {
        val retained = mutableListOf<ByteArray>()
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        val futures = mutableListOf<Future<ByteArray>>()
        try {
        inputs.forEachIndexed { i, input -> futures += fetchers.submit<ByteArray> {
            checkOpen()
            val segment = input.playlist.segments[groups[number].indexes[i]]
            require(!segment.hasGapTag && segment.fullSegmentEncryptionKeyUri == null) { "Unsupported HLS gap or full-segment encryption" }
            val map = requireNotNull(segment.initializationSegment) { "Archive segment has no CMAF map" }
            val expectedMap = requireNotNull(input.playlist.segments.first().initializationSegment)
            require(map.url == expectedMap.url && map.byteRangeOffset == expectedMap.byteRangeOffset && map.byteRangeLength == expectedMap.byteRangeLength) {
                "Archive initialization changes mid-session"
            }
            val bytes = reader.read(UriUtil.resolveToUri(input.playlist.baseUri, segment.url), 8 * 1024 * 1024,
                segment.byteRangeOffset, segment.byteRangeLength).bytes
            synchronized(retained) {
                if (aborted.get() || closed || Thread.currentThread().isInterrupted) {
                    bytes.fill(0); throw IOException("Archive session closed")
                }
                retained += bytes
            }
            bytes
        } }
            val result = futures.map { it.get(25, TimeUnit.SECONDS) }
            checkOpen()
            return result
        } catch (error: Exception) {
            aborted.set(true)
            futures.forEach { it.cancel(true) }
            synchronized(retained) { retained.forEach { it.fill(0) } }
            throw error
        }
    }

    private fun randomAccess(bytes: List<ByteArray>): Boolean = inputs.indices.all { i ->
        val first = CmafBoxInspector.inspect(bytes[i], inputs[i].report).fragments.single()
        first.sampleTimings.first().sampleFlags?.let { (it and 0x00010000L) == 0L } == true
    }

    fun manifest(): ByteArray = synchronized(cacheLock) {
        checkOpen()
        val pto = requireNotNull(presentationOffsetTicks) { "Archive session is not prepared" }
        val timeline = groups.joinToString("") { "<S t=\"${pto + scaleUs(it.startUs, timescale)}\" d=\"${scaleUs(it.durationUs, timescale)}\"/>" }
        val urls = groups.indices.joinToString("") { "<SegmentURL media=\"s-$it.m4s\"/>" }
        val duration = java.math.BigDecimal.valueOf(durationUs, 6).stripTrailingZeros().toPlainString()
        // Preserve the verified HLS epoch as presentation metadata. Media3 intentionally leaves
        // static DASH windowStartTimeMs unset; FeedPlayer uses archiveStartTimeMs for overlays.
        val epoch = archiveStartTimeMs?.let { " availabilityStartTime=\"${java.time.Instant.ofEpochMilli(it)}\"" }.orEmpty()
        """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" minBufferTime="PT1S" mediaPresentationDuration="PT${duration}S"$epoch>
<Period id="archive-grid" start="PT0S" duration="PT${duration}S"><AdaptationSet contentType="video" mimeType="video/mp4" segmentAlignment="true">
<Representation id="archive-grid" codecs="hvc1" width="$width" height="$height" bandwidth="${inputs.sumOf { it.format?.bitrate?.coerceAtLeast(0) ?: 0 }.coerceAtLeast(1)}">
<SegmentList timescale="$timescale" presentationTimeOffset="$pto" startNumber="0"><Initialization sourceURL="init.mp4"/><SegmentTimeline>$timeline</SegmentTimeline>$urls</SegmentList>
</Representation></AdaptationSet></Period></MPD>""".toByteArray()
    }

    fun hlsPlaylist(): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:0\n")
        append("#EXT-X-TARGETDURATION:${kotlin.math.ceil(groups.maxOf { it.durationUs } / 1_000_000.0).toInt()}\n")
        append("#EXT-X-MAP:URI=\"init.mp4\"\n")
        archiveStartTimeMs?.let { append("#EXT-X-PROGRAM-DATE-TIME:${java.time.Instant.ofEpochMilli(it)}\n") }
        groups.forEachIndexed { i, group -> append("#EXTINF:${java.math.BigDecimal.valueOf(group.durationUs, 6).toPlainString()},\ns-$i.m4s\n") }
        append("#EXT-X-ENDLIST\n")
    }

    fun codecString(): String {
        val bytes = initialization()
        try {
            val c = requireNotNull(CmafBoxInspector.inspect(bytes).tracks.single().hevcConfiguration)
            val compatibility = java.lang.Integer.toHexString(java.lang.Integer.reverse(c.compatibilityFlags.toInt())).uppercase()
            val space = listOf("", "A", "B", "C")[c.profileSpace]
            val constraints = (0..5).map { ((c.constraintIndicatorFlags ushr ((5 - it) * 8)) and 255).toInt() }.toMutableList()
            while (constraints.isNotEmpty() && constraints.last() == 0) constraints.removeAt(constraints.lastIndex)
            return "hvc1.$space${c.profileIdc}.$compatibility.${if (c.tierFlag) "H" else "L"}${c.levelIdc}" +
                constraints.joinToString("") { ".%02X".format(it) }
        } finally { bytes.fill(0) }
    }

    fun initialization(): ByteArray = synchronized(cacheLock) { checkOpen(); requireNotNull(initialization).copyOf() }

    override fun close() {
        if (baseReleased.compareAndSet(false, true)) releaseOwner()
    }

    private fun releaseOwner() {
        val last = synchronized(cacheLock) {
            val remaining = owners.decrementAndGet()
            if (remaining == 0) { closed = true; true } else false
        }
        if (last) shutdown()
    }

    private fun shutdown() {
        closed = true
        synchronized(warmLock) { warmTask?.cancel(true); warmer.shutdownNow() }
        fetchers.shutdownNow()
        synchronized(cacheLock) {
            initialization?.fill(0); initialization = null; cache.values.forEach { it.fill(0) }; cache.clear()
            inputs.forEach { it.initialization.fill(0) }
        }
    }

    private fun checkOpen() { if (closed || Thread.currentThread().isInterrupted) throw IOException("Archive VOD session closed") }

    private class ResourceDataSource(private val session: HevcArchiveVodSession) : DataSource {
        private var uri: Uri? = null
        private var bytes: ByteArray? = null
        private var cursor = 0
        private var end = 0
        override fun addTransferListener(listener: TransferListener) {}
        override fun getUri(): Uri? = uri
        override fun open(spec: DataSpec): Long {
            close(); uri = spec.uri
            val body = when (val name = spec.uri.lastPathSegment) {
                "manifest.mpd" -> session.manifest()
                "init.mp4" -> session.initialization()
                else -> {
                    val number = name?.let { Regex("s-(\\d+)\\.m4s").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                        ?: throw IOException("Unknown archive resource")
                    session.segment(number)
                }
            }
            if (spec.position !in 0..body.size.toLong()) { body.fill(0); throw IOException("Archive range is out of bounds") }
            bytes = body; cursor = spec.position.toInt()
            end = if (spec.length == C.LENGTH_UNSET.toLong()) body.size else minOf(body.size.toLong(), spec.position + spec.length).toInt()
            return (end - cursor).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val body = bytes ?: throw IOException("Archive resource is closed")
            if (cursor == end) return C.RESULT_END_OF_INPUT
            val count = minOf(length, end - cursor); body.copyInto(buffer, offset, cursor, cursor + count); cursor += count; return count
        }
        override fun close() { bytes?.fill(0); bytes = null; uri = null; cursor = 0; end = 0 }
    }

    companion object {
        val MANIFEST_URI: Uri = Uri.parse("apexview-archive:///manifest.mpd")
        private fun scaleUs(us: Long, scale: Long): Long = java.math.BigInteger.valueOf(us)
            .multiply(java.math.BigInteger.valueOf(scale)).divide(java.math.BigInteger.valueOf(1_000_000)).longValueExact()

        private fun align(inputs: List<Input>, clockLabel: String): List<Group> {
            require(inputs.isNotEmpty())
            val clock = inputs.firstOrNull { it.label == clockLabel } ?: inputs.first()
            val usePdt = inputs.all { it.playlist.hasProgramDateTime }
            val offsets = inputs.map { if (usePdt) it.playlist.startTimeUs - clock.playlist.startTimeUs else 0L }
            val starts = inputs.mapIndexed { i, input -> input.playlist.segments.map { it.relativeStartTimeUs + offsets[i] } }
            return clock.playlist.segments.mapNotNull { reference ->
                val indexes = inputs.indices.map { i ->
                    val found = starts[i].binarySearch(reference.relativeStartTimeUs)
                    if (found >= 0) found else {
                        val at = -found - 1
                        listOf(at - 1, at).filter { it in starts[i].indices }.minByOrNull { abs(starts[i][it] - reference.relativeStartTimeUs) } ?: -1
                    }
                }
                if (indexes.indices.any { i -> indexes[i] < 0 || abs(starts[i][indexes[i]] - reference.relativeStartTimeUs) > 250_000 }) null else {
                    val segments = inputs.indices.map { inputs[it].playlist.segments[indexes[it]] }
                    require(segments.all { abs(it.durationUs - reference.durationUs) <= 1_000L && !it.hasGapTag }) { "Archive feeds have incompatible segment durations or gaps" }
                    Group(reference.relativeStartTimeUs, reference.durationUs, indexes)
                }
            }.also { groups ->
                require(groups.isNotEmpty()) { "Archive feeds have no aligned segments" }
                require(groups.first().startUs == 0L) { "Archive feeds do not share the audio timeline's beginning" }
                groups.zipWithNext().forEach { (a, b) -> require(abs(a.startUs + a.durationUs - b.startUs) <= 1_000L) { "Archive timeline has a feed coverage gap" } }
            }
        }
    }
}
