package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryTimeline
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HistorySelectionScrollTest {
    @Test
    fun clickingVisibleRowsPreservesTheListPosition() =
        runComposeUiTest {
            val timeline = timeline()
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 900.dp)) } }
            // Keep both click targets fully visible regardless of localized row height.
            onNodeWithTag("history-events").performScrollToIndex(16)
            val viewport = onNodeWithTag("history-events").fetchSemanticsNode().boundsInRoot
            val nextRow = onNodeWithTag("history-entry-18").fetchSemanticsNode().boundsInRoot
            assertTrue(nextRow.bottom <= viewport.bottom)
            val initialTop = onNodeWithTag("history-entry-17").fetchSemanticsNode().boundsInRoot.top
            onNodeWithTag("history-entry-17").performMouseInput { click() }
            assertEquals(initialTop, onNodeWithTag("history-entry-17").fetchSemanticsNode().boundsInRoot.top, "A mouse selection must not move the row")
            onNodeWithTag("history-entry-18").performMouseInput { click() }
            assertEquals(initialTop, onNodeWithTag("history-entry-17").fetchSemanticsNode().boundsInRoot.top, "The next mouse selection must keep the same viewport")
            runOnIdle { assertEquals(18L, state.selection.single().id) }
        }

    @Test
    fun arrowNavigationStillRevealsTheSelectedRowAfterAMouseSelection() =
        runComposeUiTest {
            val timeline = timeline()
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 900.dp)) } }
            onNodeWithTag("history-events").performScrollToIndex(15)
            onNodeWithTag("history-entry-17").performMouseInput { click() }
            onNodeWithTag("history-entry-17").performKeyInput {
                keyDown(Key.AltLeft)
                pressKey(Key.DirectionDown)
                keyUp(Key.AltLeft)
            }
            runOnIdle { assertEquals(18L, state.selection.single().id) }
            assertEquals(
                onNodeWithTag("history-events").fetchSemanticsNode().boundsInRoot.top,
                onNodeWithTag("history-entry-18").fetchSemanticsNode().boundsInRoot.top,
                "Keyboard navigation must keep revealing the current event",
            )
        }

    private fun timeline(): HistoryTimeline<String> {
        val timeline = HistoryTimeline<String>()
        val machine = timeline.machine("Search")
        repeat(40) { timeline.append(machine, 0, HistoryDetails("QueryChanged($it)", "Action", "Handled", emptyList(), emptyList()), "$it") }
        return timeline
    }
}
