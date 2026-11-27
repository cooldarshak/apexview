package app.apexview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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

    @Test fun onboardBadgeKeepsAllDynamicValuesInOneAccessibleNode() {
        var speed by mutableIntStateOf(250)
        val sectors = listOf(SectorCell("27.0", Pace.GREEN), SectorCell("28.0", Pace.PURPLE), null)
        fun description(kmh: Int) = onboardBadgeDescription("1", "VER", 2,
            "1:23.4", "+0.310", "${kmh}km/h", sectors)
        rule.setContent {
            OnboardBadgeContent(number = "1", shownName = "VER", position = 2,
                lapClock = "1:23.4" to androidx.compose.ui.graphics.Color.White,
                gapText = "+0.310", speedText = "${speed}km/h", sectorData = sectors,
                team = androidx.compose.ui.graphics.Color.Blue, scale = 1f, compact = true,
                opacity = .7f)
        }
        rule.onNodeWithContentDescription(description(250)).assertExists()
        rule.onAllNodesWithText("250km/h", useUnmergedTree = false).assertCountEquals(0)
        rule.runOnIdle { speed = 301 }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(description(301)).assertExists()
        rule.onNodeWithContentDescription(description(250)).assertDoesNotExist()
        val summary = description(301)
        listOf("Driver 1, VER", "Position 2", "Lap time 1:23.4", "Gap +0.310",
            "Speed 301km/h", "Sector 1, personal best", "Sector 2, session best")
            .forEach { assertTrue(summary.contains(it)) }
        val missing = onboardBadgeDescription("44", "HAM", null, "PIT", null, "--", null)
        assertTrue(missing.contains("In pit"))
        assertTrue(missing.contains("Speed unavailable"))
        assertTrue(!missing.contains("Position"))
    }

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
        // TalkBack reads the merged tree. The unmerged debug tree intentionally
        // retains child Text nodes even behind clearAndSetSemantics.
        rule.onAllNodesWithText("LAST", useUnmergedTree = false).assertCountEquals(0)
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

    @Test fun phoneTowerScreenUsesTheCanvasRowWithOneAccessibleDriverAction() {
        val session = SessionTiming("Qualifying", mapOf("44" to DriverInfo("44", "HAM", android.graphics.Color.RED)),
            emptyMap(), emptyMap(), floatArrayOf(), false, emptyList(), 0L)
        val description = TowerAccessibilitySummary.prepare("HAM", state, Tyre("SOFT", 6))
        var clicked = 0
        rule.setContent {
            Box(androidx.compose.ui.Modifier.size(420.dp, 220.dp)) {
                TimingTowerContents(
                    timing = session, rows = listOf("44" to state),
                    header = TowerSessionHeaderData("QUALIFYING", "TRACK CLEAR", androidx.compose.ui.graphics.Color.Green, "Q1 1:22", emptyList()),
                    tyres = mapOf("44" to Tyre("SOFT", 6)), speeds = mapOf("44" to (200 to 8)),
                    onTop = "44", speedUnit = Settings.KMH, tvLayout = false,
                    onDriver = { clicked++ },
                )
            }
        }
        rule.onNodeWithContentDescription(description).assertExists().performClick()
        assertEquals(1, clicked)
        // Speed is painted into one Canvas, not exposed as hundreds of individual
        // dynamic Text semantics nodes while playing at 50 fps.
        rule.onAllNodesWithText("200", useUnmergedTree = true).assertCountEquals(0)
    }
}
