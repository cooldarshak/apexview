package app.apexview

import kotlin.math.ceil
import kotlin.math.min

/** A 16:9 tile's place on the canvas, in pixels. */
data class Slot(val x: Float, val y: Float, val w: Float) {
    val h get() = w * 9f / 16f
}

enum class LayoutMode(val label: String) {
    FEATURED("Main + strip"),
    GRID("Grid"),
    /** Broadcast-style TV view: video left, timing tower top-right, track map below. */
    TV("TV"),
    /** The arrangement saved with Save layout; falls back to main + strip where there's none. */
    CUSTOM("My layout"),
}

/** How the reserved timing tower and map share the screen beside the video feeds. */
enum class OverlayLayoutMode(val label: String) {
    DOCKED("Stacked"),
    FLOATING("Move and overlap"),
}

/** The feed area and the two overlay areas for the combined reservation layout. */
data class CombinedOverlayPlan(
    val slots: List<Slot>,
    val feeds: Area,
    val workspace: Area,
    val tower: Area,
    val map: Area,
    val floating: Boolean,
)

/**
 * Fits n 16:9 tiles into the canvas. In [LayoutMode.FEATURED], slot 0 is the big one and the
 * rest share a strip beside or below it, whichever leaves more picture on screen.
 */
object Layouts {
    /**
     * Gives the video feeds a dedicated main area and reserves the rest for the timing tower and
     * map together. Portrait uses stacked horizontal bands; landscape uses a right-hand sidebar.
     * In floating mode the two panels share one workspace and may overlap, but never cover video.
     */
    fun withCombinedOverlays(
        mode: LayoutMode,
        n: Int,
        width: Float,
        height: Float,
        overlayMode: OverlayLayoutMode,
    ): CombinedOverlayPlan {
        val portrait = height >= width
        val floating = overlayMode == OverlayLayoutMode.FLOATING
        val feeds: Area
        val workspace: Area
        val tower: Area
        val map: Area
        if (portrait) {
            val feedFraction = if (floating) 0.50f else 0.34f
            feeds = Area(0f, 0f, width, height * feedFraction)
            workspace = Area(0f, feeds.h, width, height - feeds.h)
            if (floating) {
                tower = workspace
                map = workspace
            } else {
                val towerHeight = workspace.h * 0.56f
                tower = Area(0f, workspace.y, width, towerHeight)
                map = Area(0f, workspace.y + towerHeight, width, workspace.h - towerHeight)
            }
        } else {
            val feedFraction = 0.66f
            feeds = Area(0f, 0f, width * feedFraction, height)
            workspace = Area(feeds.w, 0f, width - feeds.w, height)
            if (floating) {
                tower = workspace
                map = workspace
            } else {
                val towerHeight = height * 0.78f
                tower = Area(workspace.x, 0f, workspace.w, towerHeight)
                map = Area(workspace.x, towerHeight, workspace.w, height - towerHeight)
            }
        }
        val slots = place(mode, n, feeds.w, feeds.h).map { Slot(it.x + feeds.x, it.y + feeds.y, it.w) }
        return CombinedOverlayPlan(slots, feeds, workspace, tower, map, floating)
    }

    fun place(mode: LayoutMode, n: Int, width: Float, height: Float): List<Slot> = when {
        n == 0 -> emptyList()
        mode == LayoutMode.GRID || n == 1 -> grid(n, 0f, 0f, width, height, center = true)
        else -> featured(n, width, height)
    }

    private class Fit(val cols: Int, val rows: Int, val w: Float)

    /** Column count that gives the widest tiles for n tiles in a rw x rh area. */
    private fun bestFit(n: Int, rw: Float, rh: Float): Fit {
        var best = Fit(1, n, 0f)
        for (cols in 1..n) {
            val rows = ceil(n / cols.toDouble()).toInt()
            val w = min(rw / cols, rh / rows * 16f / 9f)
            if (w > best.w) best = Fit(cols, rows, w)
        }
        return best
    }

    /** Rows of equal tiles, each row centered, the block centered in the area when [center]. */
    private fun grid(n: Int, ax: Float, ay: Float, aw: Float, ah: Float, center: Boolean): List<Slot> {
        val fit = bestFit(n, aw, ah)
        val tileH = fit.w * 9f / 16f
        val blockH = fit.rows * tileH
        val top = ay + if (center) (ah - blockH) / 2 else 0f
        return (0 until n).map { i ->
            val row = i / fit.cols
            val inRow = min(fit.cols, n - row * fit.cols)
            val left = ax + (aw - inRow * fit.w) / 2
            Slot(left + (i % fit.cols) * fit.w, top + row * tileH, fit.w)
        }
    }

    private fun featured(n: Int, width: Float, height: Float): List<Slot> {
        val others = n - 1
        val maxMain = min(width, height * 16f / 9f)
        var best: List<Slot>? = null
        var bestScore = 0f

        var f = 1f
        while (f >= 0.5f) {
            val mainW = maxMain * f
            val mainH = mainW * 9f / 16f

            // Strip below the main tile.
            val below = bestFit(others, width, height - mainH)
            if (below.w >= mainW * 0.25f) {
                val stripH = below.rows * below.w * 9f / 16f
                val top = (height - mainH - stripH) / 2
                val slots = listOf(Slot((width - mainW) / 2, top, mainW)) +
                    grid(others, 0f, top + mainH, width, stripH, center = false)
                val score = score(mainW, others, below.w)
                if (score > bestScore) { bestScore = score; best = slots }
            }

            // Strip to the right of the main tile.
            val right = bestFit(others, width - mainW, height)
            if (right.w >= mainW * 0.25f) {
                val left = (width - mainW - right.cols * right.w) / 2
                val slots = listOf(Slot(left, (height - mainH) / 2, mainW)) +
                    grid(others, left + mainW, 0f, right.cols * right.w, height, center = true)
                val score = score(mainW, others, right.w)
                if (score > bestScore) { bestScore = score; best = slots }
            }
            f -= 0.02f
        }
        return best ?: grid(n, 0f, 0f, width, height, center = true)
    }

    /** Total picture area, with the main feed counting a little more than the strip. */
    private fun score(mainW: Float, others: Int, otherW: Float) =
        mainW * mainW + others * otherW * otherW * 0.6f

    class Arrangement(val slots: List<Slot>, val map: Area?)

    /**
     * Tiles plus room for the track map. Tries the plain layout (map in whatever gap is left) and
     * layouts squeezed to leave a band at the bottom or right, and keeps the one showing the most
     * picture, counting map area the same as video area.
     */
    fun withMap(mode: LayoutMode, n: Int, width: Float, height: Float): Arrangement {
        val minSide = min(width, height) * 0.22f
        fun score(slots: List<Slot>, map: Area?) =
            slots.sumOf { (it.w * it.h).toDouble() } + (map?.let { (it.w * it.h).toDouble() } ?: 0.0)

        val candidates = mutableListOf<Arrangement>()
        candidates += place(mode, n, width, height).let { Arrangement(it, largestFree(it, width, height)) }
        for (fraction in listOf(0.25f, 0.3f, 0.35f, 0.4f)) {
            candidates += place(mode, n, width, height * (1 - fraction))
                .let { Arrangement(it, largestFree(it, width, height)) }
            candidates += place(mode, n, width * (1 - fraction), height)
                .let { Arrangement(it, largestFree(it, width, height)) }
        }
        return candidates
            .filter { it.map != null && min(it.map.w, it.map.h) >= minSide }
            .maxByOrNull { score(it.slots, it.map) }
            ?: candidates.first()
    }

    /**
     * Finds an arrangement that leaves a real rectangle for the timing tower and, when requested,
     * a separate rectangle for the track map. The tower keeps its requested dimensions; the map
     * gets the largest remaining free rectangle. This is the rectangular counterpart of [withMap].
     */
    fun withOverlays(
        mode: LayoutMode,
        n: Int,
        width: Float,
        height: Float,
        towerWidth: Float,
        towerHeight: Float,
        mapMinSide: Float? = null,
    ): OverlayArrangement {
        val panelW = towerWidth.coerceIn(1f, width)
        val panelH = towerHeight.coerceIn(1f, height)
        val minMapSide = mapMinSide?.coerceAtMost(min(width, height))
        val candidates = mutableListOf<List<Slot>>()
        candidates += place(mode, n, width, height)
        // Exact bands give the tower predictable room at the right or bottom edge.
        candidates += place(mode, n, (width - panelW).coerceAtLeast(1f), height)
        candidates += place(mode, n, width, (height - panelH).coerceAtLeast(1f))
        if (minMapSide != null) {
            // Leave a second, non-overlapping band for the map as well.
            candidates += place(mode, n, (width - panelW).coerceAtLeast(1f), (height - minMapSide).coerceAtLeast(1f))
            candidates += place(mode, n, (width - minMapSide).coerceAtLeast(1f), (height - panelH).coerceAtLeast(1f))
            candidates += place(mode, n, (width - panelW).coerceAtLeast(1f), (height - panelH).coerceAtLeast(1f))
        }
        for (fraction in listOf(0.25f, 0.3f, 0.35f, 0.4f, 0.45f, 0.5f, 0.55f, 0.6f, 0.65f, 0.7f)) {
            candidates += place(mode, n, width, height * (1f - fraction))
            candidates += place(mode, n, width * (1f - fraction), height)
        }

        var best: OverlayArrangement? = null
        var bestScore = Double.NEGATIVE_INFINITY
        candidates.forEach { slots ->
            val free = freeAreas(slots, width, height)
            free.filter { it.w >= panelW && it.h >= panelH }.forEach gapLoop@ { gap ->
                val tower = Area(gap.x, gap.y, panelW, panelH)
                val map = if (minMapSide == null) null else free
                    .filter { area ->
                        area.w >= minMapSide && area.h >= minMapSide && !area.overlaps(tower)
                    }
                    .maxByOrNull { it.w.toDouble() * it.h }
                if (minMapSide != null && map == null) return@gapLoop
                val picture = slots.sumOf { (it.w * it.h).toDouble() }
                val score = picture + panelW.toDouble() * panelH + (map?.let { it.w.toDouble() * it.h } ?: 0.0)
                if (score > bestScore) {
                    bestScore = score
                    best = OverlayArrangement(slots, tower, map)
                }
            }
        }
        return best ?: OverlayArrangement(place(mode, n, width, height), null, null)
    }

    /** Every empty axis-aligned rectangle formed by tile edges, largest first. */
    private fun freeAreas(slots: List<Slot>, width: Float, height: Float): List<Area> {
        val xs = (listOf(0f, width) + slots.flatMap { listOf(it.x, it.x + it.w) })
            .map { it.coerceIn(0f, width) }.distinct().sorted()
        val ys = (listOf(0f, height) + slots.flatMap { listOf(it.y, it.y + it.h) })
            .map { it.coerceIn(0f, height) }.distinct().sorted()
        val result = mutableListOf<Area>()
        for (i in xs.indices) for (j in i + 1 until xs.size) for (k in ys.indices) for (l in k + 1 until ys.size) {
            val area = Area(xs[i], ys[k], xs[j] - xs[i], ys[l] - ys[k])
            if (area.w <= 0f || area.h <= 0f) continue
            if (slots.none { area.overlaps(it) }) result += area
        }
        return result.distinctBy { listOf(it.x, it.y, it.w, it.h) }
            .sortedByDescending { it.w.toDouble() * it.h }
    }

    /**
     * The biggest empty rectangle left between the tiles, for the map. Candidate edges are the
     * area's edges and every tile edge; with at most four tiles that's a small search.
     */
    fun largestFree(slots: List<Slot>, width: Float, height: Float): Area? {
        val xs = (listOf(0f, width) + slots.flatMap { listOf(it.x, it.x + it.w) })
            .map { it.coerceIn(0f, width) }.distinct().sorted()
        val ys = (listOf(0f, height) + slots.flatMap { listOf(it.y, it.y + it.h) })
            .map { it.coerceIn(0f, height) }.distinct().sorted()
        var best: Area? = null
        for (i in xs.indices) for (j in i + 1 until xs.size) for (k in ys.indices) for (l in k + 1 until ys.size) {
            val area = Area(xs[i], ys[k], xs[j] - xs[i], ys[l] - ys[k])
            if (area.w * area.h <= (best?.let { it.w * it.h } ?: 0f)) continue
            if (slots.none { area.overlaps(it) }) best = area
        }
        return best
    }
}

data class OverlayArrangement(val slots: List<Slot>, val tower: Area?, val map: Area?)

data class Area(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun overlaps(s: Slot) = x < s.x + s.w - 1 && s.x < x + w - 1 && y < s.y + s.h - 1 && s.y < y + h - 1
    fun overlaps(other: Area) = x < other.x + other.w - 1 && other.x < x + w - 1 &&
        y < other.y + other.h - 1 && other.y < y + h - 1
}
