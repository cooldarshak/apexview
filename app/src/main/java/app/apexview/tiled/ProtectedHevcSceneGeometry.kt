package app.apexview.tiled

/**
 * A compositor view interprets a decoded HEVC tile atlas as independently
 * positioned original camera feeds, not as one giant tall camera.
 * The codec, Widevine license and encrypted bytes are not changed.
 */
internal object ProtectedHevcSceneGeometry {
    data class Layout(
        val atlasWidth: Int,
        val atlasHeight: Int,
        val geometry: ProtectedHevcLiveComposition.SourceGeometry,
        val frameRate: Double
    )

    fun fromWindow(window: ProtectedHevcLiveComposition.OutputWindow, feeds: Int,
                   clearOutput: Boolean = false): Layout {
        require(feeds in 2..6) { "Protected scene requires two to six feeds" }
        val report = CmafBoxInspector.inspect(window.initializationSegment)
        require(report.errors.isEmpty() && report.initializationSegmentPresent &&
            report.tracks.size == 1) {
            "Protected scene initialization must be independently validated CMAF"
        }
        val track = report.tracks.single()
        require(track.handler == "vide" && (if (clearOutput) {
            !track.encrypted && track.protectionScheme == null
        } else {
            track.encrypted && track.protectionScheme == "cbcs"
        })) {
            "Composed scene protection mode does not match the initialized HEVC track"
        }
        val width = requireNotNull(track.width)
        val height = requireNotNull(track.height)
        require(width > 0 && height > 0) { "Protected atlas dimensions are missing" }
        val source = window.sourceGeometry ?: inferEqualTileGeometry(track, feeds)
        require(source.atlasColumns in 1..feeds && source.atlasRows > 0 &&
            source.codedWidth > 0 && source.codedHeight > 0 &&
            source.displayWidth in 1..source.codedWidth &&
            source.displayHeight in 1..source.codedHeight &&
            (source.mixedSourceRects.isEmpty() || source.mixedSourceRects.size == feeds)) {
            "Protected source tiles do not describe the selected scene feeds"
        }
        source.mixedSourceRects.forEachIndexed { index, rect ->
            require(rect.x >= 0 && rect.y >= 0 && rect.width > 0 && rect.height > 0 &&
                rect.x.toLong() + rect.width <= width &&
                rect.y.toLong() + rect.height <= height) {
                "Protected feed $index source rect is outside the decoded atlas"
            }
        }
        val fragment = window.fragments.firstOrNull()
            ?: throw IllegalArgumentException("Protected scene has no media fragment")
        require(fragment.width == width && fragment.height == height &&
            fragment.timescale > 0L && fragment.sampleCount > 0 &&
            fragment.durationTicks > 0L) {
            "Protected fragment geometry/timeline disagrees with its initialization"
        }
        val fps = fragment.sampleCount.toDouble() * fragment.timescale / fragment.durationTicks
        require(fps.isFinite() && fps in 1.0..120.0) {
            "Protected frame rate is invalid"
        }
        return Layout(width, height, source, fps)
    }

    /** Equal-quality feeds use one selected HEVC source tile each. */
    private fun inferEqualTileGeometry(
        track: CmafBoxInspector.TrackMetadata,
        feeds: Int
    ): ProtectedHevcLiveComposition.SourceGeometry {
        val hevc = requireNotNull(track.hevcConfiguration) {
            "Protected scene cannot infer tile crops without hvcC"
        }
        require(hevc.parameterSetErrors.isEmpty()) {
            "Protected scene output has invalid HEVC parameters"
        }
        val sps = hevc.sps.single()
        val pps = hevc.pps.single()
        val columns = requireNotNull(pps.tileColumnWidthsInCtbs)
        val rows = requireNotNull(pps.tileRowHeightsInCtbs)
        require(columns.size * rows.size == feeds &&
            columns.distinct().size == 1 && rows.distinct().size == 1) {
            "Protected scene cannot prove a one-tile-per-feed output mapping"
        }
        val w = Math.multiplyExact(columns[0], sps.ctbSize)
        val h = Math.multiplyExact(rows[0], sps.ctbSize)
        val rects = List(feeds) { i ->
            HevcMixedArchiveAtlasPlan.SourceRect(
                (i % columns.size) * w, (i / columns.size) * h, w, h
            )
        }
        return ProtectedHevcLiveComposition.SourceGeometry(
            w, h, w, h, columns.size, rows.size, rects
        )
    }
}
