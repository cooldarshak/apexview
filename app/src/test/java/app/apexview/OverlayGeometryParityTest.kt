package app.apexview

import java.util.Random
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

/** The nearest-index optimization must never move a driver dot on the map. */
class OverlayGeometryParityTest {
    private val random = Random(8112026L)

    private fun outline(size: Int) = FloatArray(size * 2).also { coords ->
        repeat(size) { index ->
            val angle = index * Math.PI * 2.0 / size
            val radius = 90.0 + 22.0 * kotlin.math.sin(index * .173)
            coords[2 * index] = (100.0 + kotlin.math.cos(angle) * radius).toFloat()
            coords[2 * index + 1] = (80.0 + kotlin.math.sin(angle) * radius).toFloat()
        }
    }

    private fun originalNearest(points: FloatArray, x: Float, y: Float): Int {
        var best = 0
        var bestSquared = Float.MAX_VALUE
        for (i in 0 until points.size / 2) {
            val dx = points[2 * i] - x
            val dy = points[2 * i + 1] - y
            val squared = dx * dx + dy * dy
            if (squared < bestSquared) { bestSquared = squared; best = i }
        }
        return best
    }

    @Test fun indexedNearestVertexEqualsExhaustiveSearchForThousandsOfLocations() {
        for (size in listOf(2, 3, 10, 360, 1100)) {
            val points = outline(size)
            val index = OverlayVertexIndex(points)
            repeat(2_000) {
                val x = random.nextFloat() * 350f - 80f
                val y = random.nextFloat() * 300f - 100f
                assertEquals("index $size at $x,$y",
                    originalNearest(points, x, y), index.nearest(x, y))
            }
            for (i in 0 until size) {
                assertEquals(originalNearest(points, points[2 * i], points[2 * i + 1]),
                    index.nearest(points[2 * i], points[2 * i + 1]))
            }
        }
    }

    @Test fun identicalOrEquidistantVerticesUseTheOldLowestIndexTieRule() {
        val points = floatArrayOf(0f, 0f, 2f, 0f, 0f, 0f, 2f, 2f)
        val index = OverlayVertexIndex(points)
        assertEquals(0, index.nearest(0f, 0f))
        assertEquals(0, index.nearest(1f, 0f))
        assertEquals(1, index.nearest(2f, 0f))
        assertEquals(3, index.nearest(2f, 2f))
    }

    private fun originalPointAt(points: FloatArray, fraction: Float): FloatArray {
        val n = points.size / 2
        val along = FloatArray(n)
        for (i in 1 until n) along[i] = along[i - 1] +
            hypot(points[(i - 1) * 2] - points[i * 2],
                points[(i - 1) * 2 + 1] - points[i * 2 + 1])
        val total = along[n - 1] + hypot(points[(n - 1) * 2] - points[0],
            points[(n - 1) * 2 + 1] - points[1])
        val target = fraction.mod(1f) * total
        for (i in 0 until n) {
            val j = (i + 1) % n
            val end = if (j == 0) total else along[j]
            if (target <= end || j == 0) {
                val span = end - along[i]
                val k = if (span == 0f) 0f else ((target - along[i]) / span).coerceIn(0f, 1f)
                val ax = points[i * 2]; val ay = points[i * 2 + 1]
                val bx = points[j * 2]; val by = points[j * 2 + 1]
                val len = hypot(bx - ax, by - ay).coerceAtLeast(0.001f)
                return floatArrayOf(ax + (bx - ax) * k, ay + (by - ay) * k,
                    (bx - ax) / len, (by - ay) / len)
            }
        }
        error("unreachable")
    }

    @Test fun binaryDistanceLookupProducesTheSamePathPositionAndNormal() {
        for (size in listOf(3, 40, 600)) {
            val points = outline(size)
            val geometry = TrackGeometry(points)
            for (step in 0..2_000) {
                val at = step / 2000f
                val expected = originalPointAt(points, at)
                val actual = geometry.pointAt(at)
                for (i in 0..3) {
                    assertEquals("point component $i / $size / $at", expected[i], actual[i], .0001f)
                }
            }
        }
    }

    @Test fun projectedTrackProgressStillMatchesOriginalNearestVertex() {
        val points = outline(600)
        val geometry = TrackGeometry(points)
        repeat(2_000) {
            val x = random.nextFloat() * 300f
            val y = random.nextFloat() * 300f
            assertEquals(originalNearest(points, x, y), geometry.nearestIndex(x, y))
            assertTrue(geometry.progress(x, y) in 0f..1f)
        }
    }
}
