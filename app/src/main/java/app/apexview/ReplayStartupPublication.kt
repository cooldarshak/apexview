package app.apexview

internal enum class ReplayTimingStage { SAVED_WINDOW, TOPICS, CORE }

/** One replay load owns this object on the UI looper. A new load starts with no prior race. */
internal class ReplayStartupPublication {
    private var saved: SessionTiming? = null
    private var topics: SessionTiming? = null
    private var visible: SessionTiming? = null
    private var shape: TrackGeometry? = null
    private var shapeHasLine = false

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
        val map = if (currentMap) indexed else window.takeIf { windowMap }
        val tower = if (currentTower) indexed else window.takeIf { windowTower }
        val tracks = map?.tracks.orEmpty()
        val laps = tower?.laps.orEmpty()
        val states = tower?.states.orEmpty()
        val geometry = shape ?: current.geometry
        val towerFrom = if (tower === window) window?.previewTowerFromUtc ?: window?.firstTrackUtc else tower?.previewTowerFromUtc
        val towerThrough = when {
            tower == null -> Long.MIN_VALUE
            tower === window && tower.previewTowerFromUtc == null -> tower.lastTrackUtc ?: Long.MIN_VALUE
            else -> tower.towerThroughUtc
        }
        val towerComplete = tower === indexed && indexed?.towerIndexed == true
        val positionsComplete = map === indexed && indexed?.positionsIndexed == true
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
        for (owner in listOfNotNull(saved, topics, published)) {
            if (generations.put(owner, true) != null) continue
            add(owner.outline, owner.outline.size * 4L)
            owner.tracks.values.forEach {
                add(it.t, it.t.size * 8L); add(it.x, it.x.size * 4L); add(it.y, it.y.size * 4L)
            }
            owner.laps.values.forEach {
                add(it.times, it.times.size * 8L); add(it.crossings, it.crossings.size * 8L)
                add(it.lapTimes, it.lapTimes.size * 8L); add(it.lapCounts, it.lapCounts.size * 4L)
            }
            owner.states.values.forEach { add(it.t, it.t.size * 8L) }
        }
        return "publicationOwners=${generations.size} primitiveLogicalBytes=$logical primitiveUniqueBytes=$unique"
    }

    private fun clear() {
        saved = null
        topics = null
        visible = null
        shape = null
        shapeHasLine = false
    }
}
