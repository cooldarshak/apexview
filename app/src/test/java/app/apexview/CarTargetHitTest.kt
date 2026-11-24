package app.apexview

import androidx.compose.ui.geometry.Offset
import app.apexview.carTargetAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarTargetHitTest {
    @Test
    fun preservesThe44DpSquareHitArea() {
        val positions = listOf("VER" to Offset(100f, 100f))
        assertEquals("VER", carTargetAt(positions, Offset(122f, 100f), 22f))
        assertNull(carTargetAt(positions, Offset(122.1f, 100f), 22f))
    }

    @Test
    fun overlappingTargetsChooseNearestDriver() {
        val positions = listOf(
            "VER" to Offset(100f, 100f),
            "LEC" to Offset(120f, 100f),
        )
        assertEquals("VER", carTargetAt(positions, Offset(103f, 101f), 22f))
        assertEquals("LEC", carTargetAt(positions, Offset(118f, 101f), 22f))
    }

    @Test
    fun noMarkerHitReturnsNull() {
        assertNull(carTargetAt(listOf("NOR" to Offset(100f, 100f)), Offset(200f, 200f), 22f))
        assertNull(carTargetAt(emptyList(), Offset.Zero, 22f))
        assertNull(carTargetAt(listOf("NOR" to Offset.Zero), Offset.Zero, 0f))
    }
}
