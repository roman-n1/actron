@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.timetravel.compose

import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryTimeline
import actron.timetravel.recording
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HistoryPanelCompositionTest {
    @Test
    fun resizingAcrossTheSidebarBoundaryPreservesGraphViewportAndPreview() =
        runComposeUiTest {
            val idle = StateId("Idle")
            val loading = StateId("Loading")
            val chart = StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(loading)), listOf(Transition(idle, loading, ActionMatcher("Load"))))
            val timeline = HistoryTimeline<StateId>()
            val machine = timeline.machine("Search")
            val details = HistoryDetails("Load", "Action", "Handled", emptyList(), emptyList())
            timeline.append(machine, 0, details, idle)
            timeline.append(machine, 0, details, loading)
            val state = HistoryPanelState<StateId>()
            var width by mutableStateOf(1000.dp)
            var continuations = 0
            setContent {
                MaterialTheme {
                    HistoryPanel(timeline, state, { continuations++ }, Modifier.size(width, 800.dp), graph = { HistoryGraph.Machine(chart, setOf(it.snapshot)) })
                }
            }
            onNodeWithTag("history-entry-2").performClick()
            onNodeWithTag("history-graph-tab").performClick()
            val initial =
                onNodeWithTag("graph-scale")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
                    .text
            onNodeWithTag("graph-zoom-in").performClick()
            val zoomed =
                onNodeWithTag("graph-scale")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
                    .text
            assertNotEquals(initial, zoomed)
            runOnIdle { width = 400.dp }
            onNodeWithTag("history-toggle-list").assertIsDisplayed().performClick()
            onNodeWithTag("history-events").assertDoesNotExist()
            onNodeWithTag("graph-scale").assertTextEquals(zoomed)
            onNodeWithTag("machine-graph").assertContentDescriptionContains("Loading", substring = true)
            runOnIdle { width = 1000.dp }
            onNodeWithTag("history-events").assertIsDisplayed()
            onNodeWithTag("graph-scale").assertTextEquals(zoomed)
            onNodeWithTag("history-previous").performClick()
            onNodeWithTag("machine-graph").assertContentDescriptionContains("Idle", substring = true)
            runOnIdle {
                assertEquals(idle, state.preview.single().snapshot)
                assertEquals(0, continuations)
            }
        }

    @Test
    fun payloadExpansionBelongsToTheSelectedEventAndSurvivesBookmarkRecomposition() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            val machine = timeline.machine("Search")
            val payload = "Loaded(values=" + "sample,".repeat(40) + ")"
            val details = HistoryDetails(payload, "Action", "Handled", emptyList(), emptyList())
            timeline.append(machine, 0, details, "first")
            timeline.append(machine, 0, details, "second")
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 800.dp)) } }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-action-payload").performClick()
            onNodeWithTag("history-entry-bookmark-1").performClick()
            onNodeWithTag("history-selected-input").assertTextEquals(payload)
            onNodeWithTag("history-next").performClick()
            onNodeWithTag("history-selected-input").assertTextEquals("Loaded")
            onNodeWithTag("history-previous").performClick()
            onNodeWithTag("history-selected-input").assertTextEquals("Loaded")
            runOnIdle { assertEquals("first", state.preview.single().snapshot) }
        }

    @Test
    fun recordingViewerKeepsHostActionsWhileSelectionRemainsReadOnly() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            val details = HistoryDetails("Load", "Action", "Handled", emptyList(), emptyList())
            timeline.append(timeline.machine("Search"), 0, details, "snapshot")
            val recording = timeline.recording("test", { it })
            val state = HistoryPanelState<String>()
            var exports = 0
            setContent {
                MaterialTheme {
                    HistoryRecordingPanel(recording, state, Modifier.size(1000.dp, 800.dp), actions = {
                        TextButton({ exports++ }, Modifier.testTag("host-export")) { Text("Export") }
                    })
                }
            }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("host-export").assertIsDisplayed().performClick()
            onNodeWithTag("history-continue").assertDoesNotExist()
            onNodeWithTag("history-live").assertDoesNotExist()
            onNodeWithTag("history-transition").assertExists()
            runOnIdle {
                assertEquals(1, exports)
                assertEquals("snapshot", state.preview.single().snapshot)
                assertEquals(1, recording.entries.size)
            }
        }
}
