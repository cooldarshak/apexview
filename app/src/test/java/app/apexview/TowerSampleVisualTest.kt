package app.apexview

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TowerSampleVisualTest {
    private val state = CardState(
        position = 1, sectors = emptyList(), lapSectors = emptyList(), runningMs = null,
        lastLap = null, gap = null, inPit = false
    )
    private val header = TowerSessionHeaderData("QUALIFYING", "TRACK CLEAR", Color.Green,
        "00:00", emptyList())

    private fun sample(tyre: Tyre?, speed: Int = 256, row: CardState = state) = TowerSample(
        rows = listOf("44" to row), header = header,
        tyres = mapOf("44" to tyre), speeds = mapOf("44" to (speed to 7))
    )

    @Test
    fun equalTyreValuesInNewPollingObjectsDoNotInvalidateTheTower() {
        assertTrue(sample(Tyre("S", 6)).visuallyEquals(sample(Tyre("S", 6))))
    }

    @Test
    fun changedLapAgeSpeedAndPositionInvalidateTheTower() {
        val baseline = sample(Tyre("S", 6))
        assertFalse(baseline.visuallyEquals(sample(Tyre("S", 7))))
        assertFalse(baseline.visuallyEquals(sample(Tyre("S", 6), speed = 257)))
        assertFalse(baseline.visuallyEquals(sample(Tyre("S", 6), row = state.copy(position = 2))))
    }
}
