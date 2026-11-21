package app.apexview

/**
 * Balanced, immutable 2-D nearest-vertex index for large F1 circuit outlines.
 * Unlike a heuristic "look near the previous car location" search, this always
 * returns the same exact vertex as TrackGeometry's original full scan,
 * including ties (lowest source index). Rebuilt only for a new outline.
 */
internal class OverlayVertexIndex(private val xy: FloatArray) {
    private data class Node(
        val index: Int,
        val axis: Int,
        val low: Node?,
        val high: Node?,
    )
    private val count = xy.size / 2
    private val root: Node? = create((0 until count).toList(), 0)

    private fun create(indices: List<Int>, depth: Int): Node? {
        if (indices.isEmpty()) return null
        val axis = depth and 1
        val sorted = indices.sortedWith(compareBy<Int> { xy[2 * it + axis] }.thenBy { it })
        val middle = sorted.size / 2
        return Node(
            sorted[middle], axis,
            create(sorted.subList(0, middle), depth + 1),
            create(sorted.subList(middle + 1, sorted.size), depth + 1),
        )
    }

    fun nearest(x: Float, y: Float): Int {
        if (count == 0) return 0
        var best = 0
        var squared = Float.MAX_VALUE

        fun search(node: Node?) {
            if (node == null) return
            val px = xy[node.index * 2]
            val py = xy[node.index * 2 + 1]
            val dx = px - x
            val dy = py - y
            val distance = dx * dx + dy * dy
            if (distance < squared || distance == squared && node.index < best) {
                best = node.index
                squared = distance
            }
            val side = if (node.axis == 0) x - px else y - py
            if (side < 0f) {
                search(node.low)
                if (side * side <= squared) search(node.high)
            } else {
                search(node.high)
                if (side * side <= squared) search(node.low)
            }
        }
        search(root)
        return best
    }
}
