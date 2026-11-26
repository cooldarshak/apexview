package app.apexview

internal enum class ReplayTimingStage { SAVED_WINDOW, TOPICS, CORE }

/** One replay load owns this object on the UI looper. A new load starts with no prior race. */
internal class ReplayStartupPublication {
    private var saved: SessionTiming? = null
    private var topics: SessionTiming? = null
    private var visible: SessionTiming? = null
    private var shape: TrackGeometry? = null
    private var shapeHasLine = false
    private var lastChoice = -1

    fun offer(next: SessionTiming, utc: Long): SessionTiming {
        val old = visible
        if (old != null && (old.radioBase != next.radioBase || old.startUtcMs != next.startUtcMs ||
                old.circuitKey != next.circuitKey)) clear()
        if (next.publicationStage == ReplayTimingStage.CORE) {
            clear()
            return next
        }
        if (next.publicationStage == ReplayTimingStage.SAVED_WINDOW) saved = next else topics = next
        if (shape == null && next.outline.size >= 8) {
            shape = next.geometry
            shapeHasLine = next.hasStartFinish
        }
        lastChoice = -1
        return requireNotNull(at(utc))
    }

    fun at(utc: Long): SessionTiming? {
        val current = topics ?: saved ?: return null
        val window = saved
        // The persisted window contains bracketing observations. Never extend its last
        // tower row indefinitely across a seek or manifest-clock correction.
        val windowCovers = window != null && window.tracks.values.any { it.hasPositionAt(utc) }
        val currentMap = topics?.let { it.positionsIndexed || it.tracks.values.any { t -> t.hasPositionAt(utc) } } == true
        val currentTower = topics?.let { it.towerIndexed || utc <= it.towerThroughUtc && it.laps.isNotEmpty() } == true
        val map = if (currentMap) topics else window.takeIf { windowCovers }
        val tower = if (currentTower) topics else window.takeIf { windowCovers }
        val choice = (if (currentMap) 1 else 0) + (if (currentTower) 2 else 0) + (if (windowCovers) 4 else 0)
        if (lastChoice == choice) return visible
        lastChoice = choice
        val tracks = map?.tracks.orEmpty()
        val laps = tower?.laps.orEmpty()
        val states = tower?.states.orEmpty()
        val geometry = shape ?: current.geometry
        val prev = visible
        if (prev != null && prev.tracks === tracks && prev.states === states && prev.laps === laps &&
            prev.geometry === geometry && prev.drivers === current.drivers) return prev
        return SessionTiming(current.type, current.drivers, tracks, states, geometry.outline,
            shapeHasLine, emptyList(), current.startUtcMs, circuitKey = current.circuitKey,
            laps = laps, radioBase = current.radioBase, geometry = geometry).apply {
            archivePartial = true
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
        lastChoice = -1
    }
}
