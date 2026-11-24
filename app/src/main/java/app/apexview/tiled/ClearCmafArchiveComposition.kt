package app.apexview.tiled

/** Runtime adapter for genuinely clear HEVC CMAF. Protected tracks remain rejected by the composer. */
internal class ClearCmafArchiveComposition(private val fullPictures: Boolean = false) {
    private var geometry: ProtectedHevcLiveComposition.SourceGeometry? = null
    private var initialization: ByteArray? = null
    private val history = mutableListOf<ProtectedHevcLiveComposition.OutputFragment>()
    private var sequence = 1L
    private val sourceHistory = mutableMapOf<String, MutableList<ByteArray>>()
    var status: String = "WAITING_FOR_CLEAR_CMAF"
        private set

    fun compose(feeds: List<HevcClearCmafGridComposer.Feed>) {
        val source = CmafBoxInspector.inspect(feeds.first().initializationSegment)
        val track = source.tracks.single { it.handler == "vide" }
        require(!track.encrypted) { "Clear runtime rejects encrypted source tracks" }
        val fragment = CmafBoxInspector.inspect(feeds.first().mediaFragments.first(), source).fragments.single()
        val timescale = requireNotNull(track.timescale)
        val sampleDuration = fragment.sampleTimings.map { requireNotNull(it.durationTicks) }.distinct().single()
        val divisor = java.math.BigInteger.valueOf(timescale).gcd(java.math.BigInteger.valueOf(sampleDuration)).longValueExact()
        val configuration = requireNotNull(track.hevcConfiguration)
        val sps = configuration.sps.single()
        val pps = configuration.pps.single()
        val sourceCellHeight = if (pps.tilesEnabled) {
            minOf(sps.height, requireNotNull(pps.tileRowHeightsInCtbs).first() * sps.ctbSize)
        } else sps.height
        // A partial bottom CTB cannot become an interior row without changing CABAC data.
        // Keep those feeds in one row; genuinely full source tiles can use the 2x2 grid.
        val atlas = if (fullPictures) requireNotNull(HevcArchiveAtlasPlan.choose(feeds.size, sps.width, sps.height, 4096, 2160)) {
            "Complete clear source pictures exceed the HD TV atlas limit"
        } else null
        val sourceColumns = if (pps.tilesEnabled) pps.numTileColumns else 1
        val sourceRows = if (pps.tilesEnabled) pps.numTileRows else 1
        val columns = atlas?.columns ?: if (feeds.size == 4 && sourceCellHeight % sps.ctbSize == 0) 2 else feeds.size
        val rows = atlas?.rows ?: feeds.size / columns
        geometry = if (fullPictures) ProtectedHevcLiveComposition.SourceGeometry(sps.width, sps.height,
            sps.displayWidth, sps.displayHeight, columns, rows) else null
        val independentlyDecodable = fragment.sampleTimings.first().sampleFlags?.let { (it and 0x00010000L) == 0L } == true
        val previous = if (independentlyDecodable) 0 else sourceHistory[feeds.first().label]?.size ?: 0
        require(previous < 3) { "Clear HLS archive did not provide a random-access picture within the bounded source window" }
        val composingFeeds = feeds.map { feed ->
            feed.copy(mediaFragments = (if (independentlyDecodable) emptyList() else sourceHistory[feed.label].orEmpty()) + feed.mediaFragments)
        }
        val output = HevcClearCmafGridComposer.compose(HevcClearCmafGridComposer.Request(
            composingFeeds, HevcOutputGridDimensions(columns * (if (fullPictures) sourceColumns else 1), rows * (if (fullPictures) sourceRows else 1)),
            if (fullPictures) (0 until columns * rows).flatMap { slot ->
                (0 until sourceRows).flatMap { y -> (0 until sourceColumns).map { x ->
                    HevcTizenGridComposer.CellSelection(minOf(slot, feeds.lastIndex), y * sourceColumns + x,
                        (slot % columns) * sourceColumns + x, (slot / columns) * sourceRows + y)
                } }
            } else feeds.indices.map { HevcTizenGridComposer.CellSelection(it, 0, it % columns, it / columns) },
            firstOutputSequenceNumber = sequence - previous,
            frameRateNumerator = (timescale / divisor).toInt(), frameRateDenominator = (sampleDuration / divisor).toInt()
        ))
        val initReport = CmafBoxInspector.inspect(output.initializationSegment)
        val outputTrack = initReport.tracks.single()
        output.mediaFragments.take(previous).forEach { it.fill(0) }
        val newFragments = output.mediaFragments.drop(previous).mapIndexed { index, bytes ->
            val metadata = CmafBoxInspector.inspect(bytes, initReport).fragments.single()
            ProtectedHevcLiveComposition.OutputFragment(sequence + index, bytes, timescale,
                requireNotNull(metadata.baseDecodeTimeTicks), requireNotNull(metadata.totalDurationTicks), metadata.sampleCount,
                requireNotNull(outputTrack.width), requireNotNull(outputTrack.height), feeds.associate { it.label to 0L })
        }
        try {
            history.lastOrNull()?.let { previous ->
                require(previous.baseDecodeTimeTicks + previous.durationTicks == newFragments.first().baseDecodeTimeTicks) {
                    "Clear archive output is not decode-time contiguous"
                }
                require(initialization!!.contentEquals(output.initializationSegment)) { "Clear archive initialization changed" }
            }
            if (initialization == null) initialization = output.initializationSegment.copyOf()
            history += newFragments
            feeds.forEach { feed ->
                val stored = sourceHistory.getOrPut(feed.label) { mutableListOf() }
                if (independentlyDecodable) { stored.forEach { it.fill(0) }; stored.clear() }
                stored += feed.mediaFragments.map { it.copyOf() }
            }
            sequence += newFragments.size
            while (history.size > ArchiveBufferPolicy.RETAINED_SEGMENTS) history.removeAt(0).zero()
            status = "CLEAR_HEVC_CMAF_COMPOSED_NOT_YET_PLAYED; layout=${columns}x${rows}"
        } catch (error: Throwable) { newFragments.forEach { it.zero() }; throw error
        } finally { output.initializationSegment.fill(0) }
    }

    fun outputTiming(): CmafWindowTiming? = CmafWindowTiming.from(history)

    fun hasOutput(): Boolean = initialization != null && history.isNotEmpty()

    fun outputWindow(): ProtectedHevcLiveComposition.OutputWindow? = initialization?.let {
        ProtectedHevcLiveComposition.OutputWindow(it.copyOf(), history.map { fragment -> fragment.detached() }, geometry)
    }

    fun reset() {
        initialization?.fill(0); initialization = null; geometry = null
        history.forEach { it.zero() }; history.clear()
        sourceHistory.values.flatten().forEach { it.fill(0) }; sourceHistory.clear(); sequence = 1L; status = "WAITING_FOR_CLEAR_CMAF"
    }
}
