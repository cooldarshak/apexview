package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TowerRowTextTest {
    @Test fun preparesStableVisualAndAccessibilityTextWithoutChangingSpeedUnits() {
        val state = CardState(position = 1, sectors = listOf(null, null, null),
            lapSectors = listOf(null, null, null), runningMs = null, lastLap = null,
            gap = null, inPit = true)
        val first = TowerRowText.prepare("VER", state, 200, 8, Settings.KMH)
        val second = TowerRowText.prepare("VER", state, 200, 8, Settings.KMH)
        assertEquals(first, second)
        assertEquals("PIT", first.lastLap)
        assertEquals("200", first.speed)
        assertEquals("8", first.gearText)
        // The same visible speed/gear values must not force high-rate TalkBack updates.
        val accessible = TowerAccessibilitySummary.prepare("VER", state, null)
        assertTrue(accessible.contains("Position 1, VER"))
        assertTrue(accessible.contains("In pit"))
        assertTrue(!accessible.contains("200"))
    }
}
