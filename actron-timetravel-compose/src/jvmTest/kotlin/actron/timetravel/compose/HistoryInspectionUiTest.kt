package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryTimeline
import actron.timetravel.StateField
import actron.timetravel.recording
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HistoryInspectionUiTest {
    @Test
    fun textEditorsIsolateAllHistoryShortcutsWhileEventRowsKeepKeyboardNavigation() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            val machine = timeline.machine("Search")
            timeline.append(machine, 0, details("", "Paris"), "Paris")
            timeline.append(machine, 0, details("Paris", "Oslo"), "Oslo")
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 800.dp)) } }
            onNodeWithTag("history-entry-2").performClick()
            onNodeWithTag("history-entry-1").performClick()
            for (tag in listOf("history-search", "history-field-search")) {
                if (tag == "history-field-search") onNodeWithTag(tag).performScrollTo()
                onNodeWithTag(tag).performClick().assertIsFocused()
                for (arrow in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) {
                    onNodeWithTag(tag).performKeyInput {
                        keyDown(Key.AltLeft)
                        pressKey(arrow)
                        keyUp(Key.AltLeft)
                    }
                    runOnIdle { assertEquals(1L, state.selection.single().id, "Text editor $tag must isolate $arrow") }
                }
            }
            onNodeWithTag("history-entry-1").performClick().performKeyInput {
                keyDown(Key.AltLeft)
                pressKey(Key.DirectionDown)
                keyUp(Key.AltLeft)
            }
            runOnIdle { assertEquals(2L, state.selection.single().id) }
        }

    @Test
    fun cursorNavigationScrollsTheEventListToTheSelectedMoment() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            val machine = timeline.machine("Search")
            repeat(40) { timeline.append(machine, 0, details("$it", "${it + 1}"), "$it") }
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 800.dp)) } }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-navigation-menu").performClick()
            onNodeWithTag("history-last").performClick()
            onNodeWithTag("history-entry-40").assertIsDisplayed()
            onNodeWithTag("history-previous").performClick()
            onNodeWithTag("history-entry-39").assertIsDisplayed()
        }

    private fun details(
        before: String,
        after: String,
    ) = HistoryDetails(
        "QueryChanged($after)",
        "Action",
        "Handled",
        listOf(StateField("active", "Idle"), StateField("query", before)),
        listOf(StateField("active", "Debouncing"), StateField("query", after)),
    )

    @Test
    fun fieldNavigationIgnoresFiltersAndOtherMachinesWhileABPreservesTransition() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            val machine = timeline.machine("Search")
            timeline.append(machine, 0, details("", "Paris"), "Paris")
            timeline.append(timeline.machine("Weather"), 0, details("Paris", "Berlin"), "Berlin")
            timeline.append(machine, 0, details("Paris", "Oslo"), "Oslo")
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 800.dp)) } }
            onNodeWithTag("history-entry-3").performClick()
            onNodeWithTag("history-search").performTextInput("#3")
            onNodeWithTag("history-navigation-menu").performClick()
            onNodeWithTag("history-pin-comparison").performClick()
            // A equals B: use Full state to inspect an unchanged field.
            onNodeWithText(expectedHistoryText(text = HistoryText.FullState, language = Locale.getDefault().language)).performScrollTo().performClick()
            onNodeWithTag("history-field-menu-query").performScrollTo().performClick()
            onNodeWithTag("history-field-previous").performClick()
            runOnIdle { assertEquals(1L, state.selection.single().id) }
            onNodeWithTag("history-comparison").assertTextEquals("A: #3 → B: #1")
            onNodeWithTag("history-transition").assertExists()
            onNodeWithTag("history-selected-input").assertTextEquals("QueryChanged(Paris)")
            onNodeWithTag("history-selection-status").assertExists()
            onNodeWithTag("history-field-menu-query").performScrollTo().performClick()
            onNodeWithTag("history-pin-field").performClick()
            onNodeWithTag("history-field-active").assertDoesNotExist()
            onNodeWithTag("history-field-menu-query").performScrollTo().performClick()
            onNodeWithTag("history-field-next").performClick()
            runOnIdle { assertEquals(3L, state.selection.single().id) }
            onNodeWithTag("history-navigation-menu").performClick()
            onNodeWithTag("history-navigation-back").performClick()
            runOnIdle { assertEquals(1L, state.selection.single().id) }
            onNodeWithTag("history-search").performClick().performKeyInput {
                keyDown(Key.AltLeft)
                pressKey(Key.DirectionLeft)
                keyUp(Key.AltLeft)
            }
            runOnIdle { assertEquals(1L, state.selection.single().id, "Editing text must not navigate history") }
        }

    @Test
    fun bookmarksAndEvictionPreserveSelectedSnapshotWithExplicitStatus() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>(2)
            val machine = timeline.machine("Search")
            timeline.append(machine, 0, details("", "Paris"), "Paris")
            timeline.append(machine, 0, details("Paris", "Oslo"), "Oslo")
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 800.dp)) } }
            onNodeWithTag("history-entry-2").performClick()
            onNodeWithTag("history-events").performScrollToNode(hasTestTag("history-entry-1"))
            onNodeWithTag("history-entry-bookmark-1").assertIsOff().performClick().assertIsOn()
            runOnIdle {
                assertEquals(2L, state.selection.single().id)
                assertEquals("Oslo", state.preview.single().snapshot)
            }
            onNodeWithTag("history-entry-bookmark-1").performClick().assertIsOff()
            onNodeWithTag("history-entry-bookmark-1").performClick().assertIsOn()
            onNodeWithTag("history-bookmarks-only").performClick()
            onNodeWithTag("history-entry-2").assertDoesNotExist()
            onNodeWithTag("history-selection-status").assertExists()
            runOnIdle {
                timeline.append(machine, 0, details("Oslo", "Rome"), "Rome")
                timeline.append(machine, 0, details("Rome", "London"), "London")
            }
            onNodeWithTag("history-selection-status").assertTextEquals(expectedHistoryText(text = HistoryText.OutsideWindow, language = Locale.getDefault().language))
            onNodeWithTag("history-reveal-selection").assertDoesNotExist()
            runOnIdle {
                assertEquals("Oslo", state.preview.single().snapshot)
                assertTrue(state.inspection.bookmarks.isEmpty())
            }
        }

    @Test
    fun hostExplainsBlockedRestore() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            timeline.append(timeline.machine("Search"), 0, details("", "Paris"), "Paris")
            val state = HistoryPanelState<String>()
            var continued = 0
            setContent {
                MaterialTheme {
                    HistoryPanel(timeline, state, { continued++ }, continuation = { HistoryContinuation.Unavailable("A command completion has not been processed.") }, modifier = Modifier.size(1000.dp, 800.dp))
                }
            }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-continue").assertIsNotEnabled()
            onNodeWithTag("history-continuation-reason").assertTextEquals("A command completion has not been processed.")
            runOnIdle { assertEquals(0, continued) }
        }

    @Test
    fun importedRecordingKeepsInspectionToolsWithoutMutationControls() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            timeline.append(timeline.machine("Search"), 0, details("", "Paris"), "Paris")
            val recording = timeline.recording("test", { it })
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryRecordingPanel(recording, state, Modifier.size(1000.dp, 800.dp)) } }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-transition").assertExists()
            onNodeWithTag("history-search").assertExists()
            onNodeWithTag("history-continue").assertDoesNotExist()
            onNodeWithTag("history-live").assertDoesNotExist()
        }

    @Test
    fun narrowPanelCanReopenHistoryWithoutLosingTheTransition() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            val machine = timeline.machine("Search")
            timeline.append(machine, 0, details("", "Paris"), "Paris")
            timeline.append(machine, 0, details("Paris", "Oslo"), "Oslo")
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(400.dp, 740.dp)) } }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-events").assertDoesNotExist()
            onNodeWithTag("history-transition").assertIsDisplayed()
            onNodeWithTag("history-toggle-list").performClick()
            onNodeWithTag("history-events").performScrollToNode(hasTestTag("history-entry-2"))
            onNodeWithTag("history-entry-2").performClick()
            onNodeWithTag("history-events").assertDoesNotExist()
            onNodeWithTag("history-selected-input").assertTextEquals("QueryChanged(Oslo)")
            runOnIdle { assertEquals("Oslo", state.preview.single().snapshot) }
        }
}
