package app.apexview

internal enum class ReplayTimingStage { SAVED_WINDOW, TOPICS, CORE }

/** One replay load owns this object on the UI looper. A new load starts with no prior race. */
internal class ReplayStartupPublication {
    private var saved: SessionTiming? = null
    private var topics: SessionTiming? = null
    private var visible: SessionTiming? = null
    private var shape: TrackGeometry? = null
    private var shapeHasLine = false
    private data class MapPanel(val tracks: Map<String, DriverTrack>, val indexed: Boolean)
    private data class TowerPanel(val laps: Map<String, DriverLaps>, val states: Map<String, StateLog>,
                                  val start: Long, val from: Long?, val through: Long, val indexed: Boolean)
    // Keep at most one previous covered panel per topic, not an entire old
    // SessionTiming generation. A temporary seek gap must not destroy that data.
    private var lastMap: MapPanel? = null
    private var lastTower: TowerPanel? = null

    fun offer(next: SessionTiming, playhead: ReplayPlayhead): SessionTiming {
        val old = topics ?: saved
        if (old != null && (old.radioBase != next.radioBase || old.type != next.type ||
                old.circuitKey != next.circuitKey ||
                old.radioBase.isEmpty() && old.startUtcMs != next.startUtcMs)) clear()
        if (next.publicationStage == ReplayTimingStage.CORE) {
            clear()
            return next
        }
        if (next.publicationStage == ReplayTimingStage.SAVED_WINDOW) saved = next else topics = next
        if (shape == null && next.outline.size >= 8) {
            shape = next.geometry
            shapeHasLine = next.hasStartFinish
        }
        return requireNotNull(at(playhead))
    }

    fun at(playhead: ReplayPlayhead): SessionTiming? {
        val indexed = topics
        val window = saved
        val current = indexed ?: window ?: return null
        // A saved window uses the corrected archive clock. Incoming position
        // samples have absolute timestamps, while the tower may still be
        // anchored to a provisional heartbeat clock.
        val start = window?.startUtcMs ?: current.startUtcMs
        val utc = playhead.utcAt(start)
        val windowMap = window?.tracks?.values?.any { it.hasPositionAt(utc) } == true
        val windowTower = window != null &&
            utc >= (window.previewTowerFromUtc ?: window.firstTrackUtc ?: Long.MAX_VALUE) &&
            utc <= (if (window.previewTowerFromUtc == null) window.lastTrackUtc ?: Long.MIN_VALUE else window.towerThroughUtc) &&
            window.laps.values.any { it.hasRankAt(utc) }
        val currentMap = indexed?.let {
            it.positionsIndexed || it.tracks.values.any { track -> track.hasPositionAt(utc) }
        } == true
        val currentTower = indexed != null && indexed.startUtcMs == start &&
            (indexed.towerIndexed || utc >= (indexed.previewTowerFromUtc ?: Long.MIN_VALUE) &&
                utc <= indexed.towerThroughUtc && indexed.laps.values.any { it.hasRankAt(utc) })
        // Cold-cache topic publication can arrive in independent batches. Keep
        // the last visible, clock-covered panel only if the new panel is partial.
        // An indexed empty panel is authoritative, never a reason to borrow rows.
        val priorMap = lastMap?.takeIf { it.tracks.values.any { track -> track.hasPositionAt(utc) } }
        val priorTower = lastTower?.takeIf { it.start == start && utc >= (it.from ?: Long.MIN_VALUE) &&
            utc <= it.through && it.laps.values.any { history -> history.hasRankAt(utc) } }
        val mapSource = if (currentMap) indexed else if (windowMap) window else null
        val towerSource = if (currentTower) indexed else if (windowTower) window else null
        val map = mapSource?.let { source ->
            lastMap?.takeIf { it.tracks === source.tracks && it.indexed == source.positionsIndexed }
                ?: MapPanel(source.tracks, source.positionsIndexed)
        } ?: priorMap
        val tower = towerSource?.let {
            val from = if (it === window) it.previewTowerFromUtc ?: it.firstTrackUtc else it.previewTowerFromUtc
            val through = if (it === window && it.previewTowerFromUtc == null)
                it.lastTrackUtc ?: Long.MIN_VALUE else it.towerThroughUtc
            val source = it
            lastTower?.takeIf { previous -> previous.laps === source.laps &&
                previous.states === source.states && previous.start == start &&
                previous.from == from && previous.through == through && previous.indexed == source.towerIndexed }
                ?: TowerPanel(source.laps, source.states, start, from, through, source.towerIndexed)
        } ?: priorTower
        if (map != null) lastMap = map
        if (tower != null) lastTower = tower
        val tracks = map?.tracks.orEmpty()
        val laps = tower?.laps.orEmpty()
        val states = tower?.states.orEmpty()
        val geometry = shape ?: current.geometry
        val towerFrom = tower?.from
        val towerThrough = tower?.through ?: Long.MIN_VALUE
        val towerComplete = tower?.indexed == true
        val positionsComplete = map?.indexed == true
        val prev = visible
        if (prev != null && prev.tracks === tracks && prev.states === states && prev.laps === laps &&
            prev.geometry === geometry && prev.drivers === current.drivers && prev.startUtcMs == start &&
            prev.previewTowerFromUtc == towerFrom && prev.towerThroughUtc == towerThrough &&
            prev.towerIndexed == towerComplete && prev.positionsIndexed == positionsComplete) return prev
        return SessionTiming(current.type, current.drivers, tracks, states, geometry.outline,
            shapeHasLine, emptyList(), start, circuitKey = current.circuitKey,
            laps = laps, radioBase = current.radioBase, geometry = geometry).apply {
            archivePartial = true
            publicationStage = ReplayTimingStage.TOPICS
            previewTowerFromUtc = towerFrom
            towerThroughUtc = towerThrough
            towerIndexed = towerComplete
            positionsIndexed = positionsComplete
            visible = this
        }
    }

    /** Only archive primitive arrays visible to this publication owner. Shared arrays
     * are counted once across the saved window, incoming topics and displayed view. */
    fun backingFields(published: SessionTiming): String {
        val generations = java.util.IdentityHashMap<SessionTiming, Boolean>()
        val arrays = java.util.IdentityHashMap<Any, Boolean>()
        var logical = 0L
        var unique = 0L
        fun add(array: Any, bytes: Long) {
            logical += bytes
            if (arrays.put(array, true) == null) unique += bytes
        }
        // Never materialize compact Rust arrays just to measure their JVM
        // backing. Native payloads are already counted by the native registry.
        fun times(t: TimingTimestamps) { t.array?.let { add(it, it.size * 8L) } }
        fun tracks(values: Map<String, DriverTrack>) { values.values.forEach {
            times(it.timeline)
            it.coordinates.xArray?.let { a -> add(a, a.size * 4L) }
            it.coordinates.yArray?.let { a -> add(a, a.size * 4L) }
        } }
        fun tower(laps: Map<String, DriverLaps>, states: Map<String, StateLog>) {
            laps.values.forEach {
                times(it.snapshotTimeline); times(it.crossingTimeline); times(it.lapTimeline)
                add(it.lapCounts, it.lapCounts.size * 4L)
            }
            states.values.forEach { times(it.timeline) }
        }
        for (owner in listOfNotNull(saved, topics, published)) {
            if (generations.put(owner, true) != null) continue
            add(owner.outline, owner.outline.size * 4L)
            tracks(owner.tracks)
            tower(owner.laps, owner.states)
        }
        lastMap?.let { panel -> if (generations.keys.none { it.tracks === panel.tracks }) tracks(panel.tracks) }
        lastTower?.let { panel -> if (generations.keys.none { it.laps === panel.laps && it.states === panel.states })
            tower(panel.laps, panel.states) }
        return "publicationOwners=${generations.size} primitiveLogicalBytes=$logical primitiveUniqueBytes=$unique primitiveScope=JVM_BACKING_ONLY"
    }

    private fun clear() {
        saved = null
        topics = null
        visible = null
        shape = null
        shapeHasLine = false
        lastMap = null
        lastTower = null
    }
}
