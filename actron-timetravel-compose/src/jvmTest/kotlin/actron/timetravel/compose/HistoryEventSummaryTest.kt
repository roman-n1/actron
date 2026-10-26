package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryTimeline
import actron.timetravel.StateField
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HistoryEventSummaryTest {
    @Test
    fun eventRowsShowTheActionNameAndOnlyChangedStateFields() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            val payload = "QueryChanged(query=Oslo, request=full-action-payload)"
            timeline.append(
                timeline.machine("Search"),
                0,
                HistoryDetails(
                    payload,
                    "Action",
                    "Handled",
                    listOf(StateField("active", "Idle"), StateField("query", "Paris"), StateField("unchanged", "do-not-repeat"), StateField("revision", "1")),
                    listOf(StateField("active", "Debouncing"), StateField("query", "Oslo"), StateField("unchanged", "do-not-repeat"), StateField("revision", "2")),
                ),
                "Oslo",
            )
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 900.dp)) } }
            onNodeWithTag("history-entry-action-1", useUnmergedTree = true).assertTextEquals("QueryChanged")
            onNodeWithTag("history-entry-before-1", useUnmergedTree = true).assertTextEquals("Idle")
            onNodeWithTag("history-entry-after-1", useUnmergedTree = true).assertTextEquals("Debouncing")
            val action = onNodeWithTag("history-entry-action-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val before = onNodeWithTag("history-entry-before-1-card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val after = onNodeWithTag("history-entry-after-1-card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(before.top > action.bottom)
            assertTrue(after.top > action.bottom)
            assertTrue(before.center.x < action.center.x)
            assertTrue(after.center.x > action.center.x)
            onNodeWithTag("history-entry-changes-1", useUnmergedTree = true).assertTextContains("query: Paris → Oslo")
            onNodeWithText("do-not-repeat", substring = true).assertDoesNotExist()
            onNodeWithText("revision:", substring = true).assertDoesNotExist()
            onNodeWithText("full-action-payload", substring = true).assertDoesNotExist()
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-selected-input").assertTextEquals(payload)
        }
}
