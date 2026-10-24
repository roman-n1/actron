@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.timetravel.compose

import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.machine.TransitionId
import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryTimeline
import actron.timetravel.recording
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InspectionBoundaryRegressionTest {
    @Test
    fun replacingRecordingDoesNotShowPreviousFilesSnapshot() =
        runComposeUiTest {
            fun recording(value: String): actron.timetravel.HistoryRecording<String> {
                val timeline = HistoryTimeline<String>()
                timeline.append(timeline.machine("Search"), 0, HistoryDetails(value, "Action", "Handled", emptyList(), emptyList()), value)
                return timeline.recording("test", { it })
            }
            var current by mutableStateOf(recording("first-file"))
            val second = recording("second-file")
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryRecordingPanel(current, state, Modifier.size(1000.dp, 800.dp)) } }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-entry-bookmark-1").performClick()
            onNodeWithTag("history-search").performTextInput("first-file")
            runOnIdle {
                assertEquals("first-file", state.preview.single().snapshot)
                current = second
            }
            onNodeWithTag("history-entry-1").assertTextContains("second-file", substring = true)
            runOnIdle { assertFalse(state.preview.any { it.snapshot == "first-file" }, "New recording must not keep the old file's preview") }
        }

    @Test
    fun aPreselectedRecordingKeepsItsPreviewWhenTheSameViewerIsRemounted() =
        runComposeUiTest {
            val timeline = HistoryTimeline<String>()
            timeline.append(timeline.machine("Search"), 0, HistoryDetails("Load", "Action", "Handled", emptyList(), emptyList()), "preselected")
            val recording = timeline.recording("test", { it })
            val state = HistoryPanelState<String>().apply { select(timeline.entries.value.single()) }
            var visible by mutableStateOf(true)
            setContent {
                MaterialTheme {
                    if (visible) HistoryRecordingPanel(recording, state, Modifier.size(1000.dp, 800.dp))
                }
            }
            onNodeWithTag("history-selected-input").assertTextEquals("Load")
            runOnIdle { visible = false }
            onNodeWithTag("history-panel").assertDoesNotExist()
            runOnIdle { visible = true }
            onNodeWithTag("history-selected-input").assertTextEquals("Load")
            runOnIdle { assertEquals("preselected", state.preview.single().snapshot) }
        }

    @Test
    fun validEdgeAfterDanglingEdgeDisplaysOriginalTransitionNumber() =
        runComposeUiTest {
            val a = StateId("A")
            val b = StateId("B")
            val missing = StateId("Missing")
            val chart =
                StateChartDefinition(
                    a,
                    listOf(AtomicState(a), AtomicState(b)),
                    listOf(
                        Transition(a, missing, ActionMatcher("Dangling")),
                        Transition(a, b, ActionMatcher("Valid")),
                    ),
                )
            val viewport = GraphViewport()
            var layouts = emptyList<GraphLayout>()
            setContent {
                MaterialTheme {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
                    layouts =
                        listOf(
                            layoutGraph(chart) { transition ->
                                val text = measurer.measure(transition.triggerLabel, style, constraints = Constraints(maxWidth = 320))
                                Size(text.size.width.toFloat(), text.size.height.toFloat())
                            },
                        )
                    MachineGraph(HistoryGraph.Machine(chart, setOf(a), HistoryGraphCoverage.Recorded(mapOf(TransitionId(1) to 7L))), viewport, Modifier.size(900.dp, 800.dp))
                }
            }
            onNodeWithTag("machine-graph").performTouchInput {
                click(
                    layouts
                        .single()
                        .edges
                        .single()
                        .label.center * viewport.scale + viewport.offset,
                )
            }
            onNodeWithTag("graph-transition-hits").assertTextContains("7", substring = true)
            onNodeWithText("#2", substring = true).assertExists()
        }
}
