package app.apexview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TowerAccessibilitySummaryTest {
    @get:Rule val rule = createComposeRule()

    private val state = CardState(
        position = 1, sectors = emptyList(), lapSectors = emptyList(),
        runningMs = null, lastLap = SectorCell("1:32.429", Pace.PURPLE),
        gap = null, inPit = false, ahead = "+0.310", toLeader = "LEAD",
        bestLap = SectorCell("1:32.429", Pace.PURPLE)
    )

    @Test fun summaryKeepsMeaningfulTimingAndDoesNotDependOnGearOrMicroSectors() {
        val original = TowerAccessibilitySummary.prepare("HAM", state, Tyre("SOFT", 6))
        val changedStrip = state.copy(sectors = listOf(SectorCell("27.000", Pace.GREEN, listOf(3, 2, 1))))
        assertEquals(original, TowerAccessibilitySummary.prepare("HAM", changedStrip, Tyre("SOFT", 6)))
        assertTrue(original.contains("Position 1, HAM"))
        assertTrue(original.contains("Last lap 1:32.429"))
        assertTrue(original.contains("Interval +0.310"))
        assertTrue(original.contains("SOFT, 6 laps"))
        assertTrue(TowerAccessibilitySummary.prepare("HAM", state.copy(inPit = true), null).contains("In pit"))
        assertTrue(TowerAccessibilitySummary.prepare("HAM", state.copy(retired = true), null).contains("Retired"))
    }

    @Test fun phoneTowerExposesOneClickableNodeDespiteChangingSpeed() {
        val session = SessionTiming("Race", emptyMap(), emptyMap(), emptyMap(), floatArrayOf(),
            false, emptyList(), 0L)
        var speed by mutableIntStateOf(190)
        var clicked = 0
        val description = TowerAccessibilitySummary.prepare("44", state, null)
        rule.setContent {
            TowerRow(session, "44", state, null, kmh = speed, gear = 7,
                highlight = false, onClick = { clicked++ })
        }
        rule.onNodeWithContentDescription(description).assertExists().performClick()
        rule.onAllNodesWithText("LAST", useUnmergedTree = true).assertCountEquals(0)
        rule.runOnIdle { speed = 251 }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(description).assertExists().performClick()
        assertEquals(2, clicked)
    }

    @Test fun tvTowerSummaryIsStableAcrossRapidTelemetryButStillClickable() {
        var speed by mutableIntStateOf(175)
        var clicked = 0
        val description = TowerAccessibilitySummary.prepare("44", state, null)
        rule.setContent {
            TvTowerRow(null, "44", state, null, kmh = speed, gear = 5,
                speedUnit = Settings.KMH, highlight = false, rowHeight = 34.dp,
                onClick = { clicked++ })
        }
        rule.onNodeWithContentDescription(description).assertExists().performClick()
        rule.runOnIdle { speed = 203 }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(description).assertExists().performClick()
        assertEquals(2, clicked)
    }
}
