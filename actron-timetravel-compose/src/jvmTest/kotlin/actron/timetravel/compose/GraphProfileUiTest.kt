@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.timetravel.compose

import actron.statechart.*
import actron.statechart.machine.*
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class GraphProfileUiTest {
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
                                    androidx.compose.ui.geometry
                                        .Size(it.width.toFloat(), it.height.toFloat())
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
