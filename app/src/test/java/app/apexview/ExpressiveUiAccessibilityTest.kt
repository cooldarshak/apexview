package app.apexview

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
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
    @Test fun realExpressiveThemePreservesRacingContrast() {
        var primary = Color.Unspecified
        rule.setContent {
            AppTheme {
                val actualPrimary = MaterialTheme.colorScheme.primary
                SideEffect { primary = actualPrimary }
                Text("Pitwall")
            }
        }
        rule.waitForIdle()
        assertEquals(Ink.accent, primary)
    }

    @Test fun material15SliderReceivesRemoteArrowsWithoutLosingRepeatedChanges() {
        var sliderValue by mutableFloatStateOf(0.50f)
        val focus = FocusRequester()
        val changes = mutableListOf<Float>()
        rule.setContent {
            val mode = LocalInputModeManager.current
            LaunchedEffect(Unit) { mode.requestInputMode(InputMode.Keyboard) }
            AppTheme {
                AppleSlider(
                    value = sliderValue,
                    onValueChange = { next -> sliderValue = next; changes += next },
                    valueRange = 0f..1f,
                    modifier = Modifier.testTag("expressive-slider").focusRequester(focus),
                )
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        rule.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft)
        }
        rule.waitForIdle()
        org.junit.Assert.assertEquals(3, changes.size)
        org.junit.Assert.assertEquals(0.51f, sliderValue, 0.001f)
    }


    @Test fun captionEnableSwitchAcceptsOneRemoteSelectWithLabeledSemantics() {
        var captions by mutableStateOf(false)
        val focus = FocusRequester()
        rule.setContent {
            AppTheme {
                SwitchRow(
                    "Show captions",
                    checked = captions,
                    onChange = { captions = it },
                    modifier = Modifier.focusRequester(focus),
                )
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        val target = rule.onNodeWithText("Show captions")
        target.assertIsOff()
        target.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        // Match remote centre/select, not an unlabelled nested Switch.
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        target.assertIsOn()
        assertEquals(true, captions)
    }

    @Test fun settingsChoiceAnnouncesSelectedRadioRoleWithOneClickTargetPerRow() {
        var chosen by mutableStateOf("Single")
        rule.setContent {
            AppTheme {
                androidx.compose.foundation.layout.Column {
                    ChoiceRow("Single", chosen == "Single") { chosen = "Single" }
                    ChoiceRow("Multiple", chosen == "Multiple") { chosen = "Multiple" }
                }
            }
        }
        rule.onNodeWithText("Single").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.onNodeWithText("Multiple").assertIsNotSelected()
        rule.onAllNodes(hasClickAction()).assertCountEquals(2)
        rule.onNodeWithText("Multiple").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Multiple").assertIsSelected()
        rule.onNodeWithText("Single").assertIsNotSelected()
        assertEquals("Multiple", chosen)
    }

    @Test fun segmentedChoiceHasRoleSelectionAndKeepsSingleAccessibleClick() {
        var chosen by mutableStateOf("Single")
        rule.setContent {
            AppTheme {
                Segments(
                    options = listOf("Single", "Split", "Grid"),
                    chosen = chosen, label = { it }, onPick = { chosen = it },
                )
            }
        }
        val single = rule.onNodeWithText("Single")
        single.assertIsSelected()
        single.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.onNodeWithText("Split").assertIsNotSelected()
        rule.onAllNodes(hasClickAction()).assertCountEquals(3)
        rule.onNodeWithText("Split").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Split").assertIsSelected()
        single.assertIsNotSelected()
        assertEquals("Split", chosen)
    }


}
