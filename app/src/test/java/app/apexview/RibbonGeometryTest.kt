package app.apexview

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

/** Geometric parity against the original Pair-returning per-frame ribbon. */
class RibbonGeometryTest {
    private fun oldRibbonAt(l: Float, t: Float, r: Float, b: Float,
                            radius: Float, clockwise: Boolean, f: Float): Pair<Offset, Offset> {
        val rr = radius.coerceIn(0f, minOf(r - l, b - t) / 2)
        val top = (r - l) - 2 * rr
        val side = (b - t) - 2 * rr
        val arc = (Math.PI * rr / 2).toFloat()
        val perimeter = 2 * top + 2 * side + 4 * arc
        val shift = top / 2
        var d = ((if (clockwise) f else -f) * perimeter + shift).mod(perimeter)
        fun corner(cx: Float, cy: Float, degrees: Float): Pair<Offset, Offset> {
            val angle = Math.toRadians((degrees + 90f * d / arc).toDouble())
            val p = Offset(cx + rr * cos(angle).toFloat(), cy + rr * sin(angle).toFloat())
            val normal = Offset(cx - p.x, cy - p.y).let { if (rr > 0f) it / rr else it }
            return p to normal
        }
        if (d < top) return Offset(l + rr + d, t) to Offset(0f, 1f)
        d -= top
        if (d < arc) return corner(r - rr, t + rr, -90f)
        d -= arc
        if (d < side) return Offset(r, t + rr + d) to Offset(-1f, 0f)
        d -= side
        if (d < arc) return corner(r - rr, b - rr, 0f)
        d -= arc
        if (d < top) return Offset(r - rr - d, b) to Offset(0f, -1f)
        d -= top
        if (d < arc) return corner(l + rr, b - rr, 90f)
        d -= arc
        if (d < side) return Offset(l, b - rr - d) to Offset(1f, 0f)
        d -= side
        return corner(l + rr, t + rr, 180f)
    }

    @Test fun allSegmentsDirectionsCornersAndWrapKeepIdenticalPixelsAndNormals() {
        for (clockwise in listOf(true, false)) {
            for (radius in listOf(0f, 4f, 24f, 80f)) {
                val l = 6f; val t = 9f; val r = 201f; val b = 121f
                val ribbon = Ribbon(l, t, r, b, radius, clockwise)
                for (step in -200..1_200) {
                    val f = step / 1_000f
                    val before = oldRibbonAt(l, t, r, b, radius, clockwise, f)
                    val point = ribbon.point(f)
                    val inward = ribbon.inward(f)
                    assertEquals("x f=$f radius=$radius clockwise=$clockwise",
                        before.first.x, point.x, 0.00001f)
                    assertEquals("y f=$f radius=$radius clockwise=$clockwise",
                        before.first.y, point.y, 0.00001f)
                    assertEquals("inward x f=$f radius=$radius clockwise=$clockwise",
                        before.second.x, inward.x, 0.00001f)
                    assertEquals("inward y f=$f radius=$radius clockwise=$clockwise",
                        before.second.y, inward.y, 0.00001f)
                }
            }
        }
    }

    @Test fun veryNarrowPortraitAndWideLandscapeHaveNoAllocationDrivenGeometryChange() {
        for ((width, height, radius) in listOf(
            Triple(52f, 620f, 18f), Triple(640f, 44f, 12f),
            Triple(80f, 80f, 40f), Triple(180f, 300f, 0f),
        )) {
            val ribbon = Ribbon(0f, 0f, width, height, radius)
            for (i in 0..200) {
                val f = i / 200f
                val expected = oldRibbonAt(0f, 0f, width, height, radius, true, f)
                assertEquals(expected.first.x, ribbon.point(f).x, 0.00001f)
                assertEquals(expected.first.y, ribbon.point(f).y, 0.00001f)
                assertEquals(expected.second.x, ribbon.inward(f).x, 0.00001f)
                assertEquals(expected.second.y, ribbon.inward(f).y, 0.00001f)
            }
        }
    }
}
