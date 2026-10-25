@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.timetravel.compose

import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.DurationProfile
import actron.statechart.machine.MachineProfileSnapshot
import actron.statechart.machine.TransitionId
import actron.statechart.machine.TransitionProfile
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class GraphProfileUiTest {
    @Test
    fun snapshotUpdatesKeepSelectionAndProfileWhileDefinitionChangesResetThemWithoutRefitting() =
        runComposeUiTest {
            val a = StateId("A")
            val b = StateId("B")
            var definition by mutableStateOf(StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b)), listOf(Transition(a, b, ActionMatcher("Load")))))
            var active by mutableStateOf(setOf(a))
            val profile = HistoryGraphProfile.Recorded(MachineProfileSnapshot(DefinitionId("graph"), DefinitionVersion("1")))
            val viewport = GraphViewport()
            setContent {
                MaterialTheme {
                    MachineGraph(HistoryGraph.Machine(definition, active, profile = profile), viewport, Modifier.size(800.dp, 800.dp))
                }
            }
            onNodeWithTag("graph-profile-toggle").performClick()
            onNodeWithTag("machine-graph").performTouchInput {
                click(
                    Offset(80f, 60f) * viewport.scale + viewport.offset,
                )
            }
            onNodeWithTag("graph-state-transitions").assertExists()
            onNodeWithText("Load: B").assertExists()
            onNodeWithTag("graph-profile-window").assertExists()
            onNodeWithTag("graph-zoom-in").performClick()
            var scale = 0f
            var offset = Offset.Zero
            runOnIdle {
                scale = viewport.scale
                offset = viewport.offset
                active = setOf(b)
            }
            onNodeWithTag("machine-graph").assertContentDescriptionContains("B", substring = true)
            onNodeWithTag("graph-state-transitions").assertExists()
            onNodeWithText("Load: B").assertExists()
            onNodeWithTag("graph-profile-window").assertExists()
            runOnIdle { definition = definition.copy(transitions = emptyList()) }
            onNodeWithTag("graph-state-transitions").assertDoesNotExist()
            onNodeWithTag("graph-profile-window").assertDoesNotExist()
            onNodeWithTag("machine-graph").performTouchInput {
                click(
                    Offset(80f, 60f) * viewport.scale + viewport.offset,
                )
            }
            onNodeWithTag("graph-state-transitions").assertExists()
            runOnIdle {
                assertEquals(scale, viewport.scale)
                assertEquals(offset, viewport.offset)
            }
        }

    @Test fun selectedGraphUsesFrozenMeasurementsAndKeepsTimingsSeparate() =
        runComposeUiTest {
            val a = StateId("A")
            val b = StateId("B")
            val chart = StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b)), listOf(Transition(a, b, ActionMatcher("Load"))))
            val old =
                MachineProfileSnapshot(
                    DefinitionId("profile"),
                    DefinitionVersion("1"),
                    transitions =
                        mapOf(
                            TransitionId(0) to
                                TransitionProfile(
                                    2,
                                    DurationProfile(2, 10.milliseconds, 7.milliseconds),
                                    DurationProfile(2, 4.milliseconds, 3.milliseconds),
                                ),
                        ),
                )
            var recorded by mutableStateOf(old)
            val viewport = GraphViewport()
            var layouts = emptyList<GraphLayout>()
            setContent {
                MaterialTheme {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
                    layouts =
                        listOf(
                            layoutGraph(chart) { transition ->
                                measurer.measure(transition.triggerLabel, style, constraints = Constraints(maxWidth = 320)).size.let {
                                    Size(it.width.toFloat(), it.height.toFloat())
                                }
                            },
                        )
                    MachineGraph(HistoryGraph.Machine(chart, setOf(a), profile = HistoryGraphProfile.Recorded(recorded)), viewport, Modifier.size(800.dp, 800.dp))
                }
            }
            onNodeWithTag("graph-profile-toggle").performClick()
            onNodeWithTag("graph-profile-window").assertExists()
            onNodeWithTag("machine-graph").performTouchInput {
                click(
                    layouts
                        .single()
                        .edges
                        .single()
                        .label.center * viewport.scale + viewport.offset,
                )
            }
            onNodeWithTag("profile-duration-InputQueue").assertTextContains("5ms", substring = true)
            onNodeWithTag("profile-duration-InputProcessing").assertTextContains("2ms", substring = true)
            runOnIdle { recorded = old.copy(transitions = mapOf(TransitionId(0) to TransitionProfile(3, processing = DurationProfile(1, 30.milliseconds, 30.milliseconds)))) }
            onNodeWithTag("profile-duration-InputProcessing").assertTextContains("30ms", substring = true)
            runOnIdle { recorded = old }
            onNodeWithTag("profile-duration-InputProcessing").assertTextContains("2ms", substring = true)
            onNodeWithTag("graph-profile-toggle").performClick()
            onNodeWithTag("profile-duration-InputProcessing").assertDoesNotExist()
        }
}
