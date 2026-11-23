package app.apexview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M3 Expressive settings semantics for TalkBack and remote-select parity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpressiveUiAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theSettingsSwitchIsOneLabeledToggleWithStateAndClickAction() {
        var checked by mutableStateOf(false)
        var changed = 0
        rule.setContent {
            AppTheme {
                SwitchRow("Keep feeds clear of map and tower", checked) { next ->
                    checked = next
                    changed++
                }
            }
        }
        val node = rule.onNodeWithText("Keep feeds clear of map and tower")
        node.assertExists()
        node.assertIsOff()
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        node.performClick()
        rule.waitForIdle()
        node.assertIsOn()
        assertEquals(1, changed)
        node.performClick()
        rule.waitForIdle()
        node.assertIsOff()
        assertEquals(2, changed)
    }
}
