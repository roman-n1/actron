package actron.timetravel.compose

import actron.statechart.*
import actron.timetravel.*
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MachineGraphUiTest {
    @Test
    fun coverageFollowsHistoricalSnapshotIndependentlyOfActiveState() =
        runComposeUiTest {
            val idle = StateId("Idle")
            val chart = StateChartDefinition(idle, listOf(AtomicState(idle)), listOf(Transition(idle, idle, ActionMatcher("Again"))))
            val timeline = HistoryTimeline<Map<actron.statechart.machine.TransitionId, Long>>()
            val machine = timeline.machine("Counter")
            val details = HistoryDetails("Again", "Action", "Handled", emptyList(), emptyList())
            timeline.append(machine, 0, details, emptyMap())
            timeline.append(machine, 0, details, mapOf(actron.statechart.machine.TransitionId(0) to 2L))
            val state = HistoryPanelState<Map<actron.statechart.machine.TransitionId, Long>>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 800.dp), graph = { HistoryGraph.Machine(chart, setOf(idle), HistoryGraphCoverage.Recorded(it.snapshot)) }) } }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-graph-tab").performClick()
            onNodeWithTag("graph-coverage").assertTextContains("0 / 1", substring = true)
            onNodeWithTag("history-entry-2").performClick()
            onNodeWithTag("graph-coverage").assertTextContains("1 / 1", substring = true)
            onNodeWithTag("machine-graph").assertContentDescriptionContains("Idle", substring = true)
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("graph-coverage").assertTextContains("0 / 1", substring = true)
        }

    @Test
    fun historySelectionChangesHighlightAndTabsPreserveViewportWithoutContinuing() =
        runComposeUiTest {
            val idle = StateId("Idle")
            val loading = StateId("Loading")
            val chart = StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(loading)), listOf(Transition(idle, loading, ActionMatcher("Search"))))
            val timeline = HistoryTimeline<StateId>()
            val machine = timeline.machine("Search")
            val details = HistoryDetails("Search", "Action", "Handled", emptyList(), emptyList())
            timeline.append(machine, 0, details, idle)
            timeline.append(machine, 0, details, loading)
            val state = HistoryPanelState<StateId>()
            var continued = 0
            setContent { MaterialTheme { HistoryPanel(timeline, state, { continued++ }, Modifier.size(1000.dp, 800.dp), graph = { HistoryGraph.Machine(chart, setOf(it.snapshot)) }) } }
            onNodeWithTag("history-entry-1").performClick()
            onNodeWithTag("history-graph-tab").performClick()
            onNodeWithTag("machine-graph").assertContentDescriptionContains("Idle", substring = true)
            val initial =
                onNodeWithTag("graph-scale")
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                    .single()
                    .text
            onNodeWithTag("graph-zoom-in").performClick()
            val zoomed =
                onNodeWithTag("graph-scale")
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                    .single()
                    .text
            assertNotEquals(initial, zoomed)
            onNodeWithTag("history-entry-2").performClick()
            onNodeWithTag("machine-graph").assertContentDescriptionContains("Loading", substring = true)
            onNodeWithTag("graph-scale").assertTextEquals(zoomed)
            onNodeWithTag("history-event-tab").performClick()
            onNodeWithTag("history-transition").assertExists()
            onNodeWithTag("history-graph-tab").performClick()
            onNodeWithTag("graph-scale").assertTextEquals(zoomed)
            onNodeWithTag("graph-fit").performClick()
            onNodeWithTag("graph-scale").assertTextEquals(initial)
            runOnIdle {
                assertEquals(0, continued)
                assertEquals(loading, state.preview.single().snapshot)
            }
        }

    @Test
    fun longActionLabelsSelectExactDuplicateTransitionAfterPanAndZoom() =
        runComposeUiTest {
            val a = StateId("A")
            val b = StateId("B")
            val action = "A long action name that must wrap without being truncated ".repeat(3)
            val chart =
                StateChartDefinition(
                    a,
                    listOf(AtomicState(a), AtomicState(b)),
                    listOf(
                        Transition(a, b, ActionMatcher(action)),
                        Transition(a, b, ActionMatcher(action)),
                    ),
                )
            val viewport = GraphViewport()
            var layouts = emptyList<GraphLayout>()
            setContent {
                MaterialTheme {
                    val measurer =
                        androidx.compose.ui.text
                            .rememberTextMeasurer()
                    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
                    layouts =
                        listOf(
                            layoutGraph(chart) { transition ->
                                val text =
                                    measurer.measure(
                                        transition.triggerLabel,
                                        style,
                                        constraints =
                                            androidx.compose.ui.unit
                                                .Constraints(maxWidth = 320),
                                    )
                                androidx.compose.ui.geometry
                                    .Size(text.size.width.toFloat(), text.size.height.toFloat())
                            },
                        )
                    MachineGraph(HistoryGraph.Machine(chart, setOf(a)), viewport, Modifier.size(700.dp, 800.dp))
                }
            }
            runOnIdle { viewport.transform(Offset(250f, 250f), Offset(10f, 15f), 1.1f) }
            onNodeWithTag("machine-graph").performTouchInput {
                click(
                    layouts
                        .single()
                        .edges[1]
                        .label.center * viewport.scale + viewport.offset,
                )
            }
            onNodeWithTag("graph-transition-details").assertExists()
            onNodeWithText("#2", substring = true).assertExists()
            onNodeWithText(action, substring = true).assertExists()
        }

    @Test
    fun touchPanPinchAndZoomControlsWorkInNarrowPanel() =
        runComposeUiTest {
            val id = StateId("Idle")
            val viewport = GraphViewport()
            val graph = HistoryGraph.Machine(StateChartDefinition(id, listOf(AtomicState(id)), emptyList()), setOf(id))
            setContent { MaterialTheme { MachineGraph(graph, viewport, Modifier.size(360.dp, 500.dp)) } }
            onNodeWithTag("machine-graph").performTouchInput {
                val node =
                    layoutGraph(graph.definition)
                        .nodes
                        .single()
                        .bounds.center
                click(node * viewport.scale + viewport.offset)
            }
            onNodeWithTag("graph-state-transitions").assertExists()
            onNodeWithTag("graph-fit").performClick()
            var before = Offset.Zero
            runOnIdle { before = viewport.offset }
            onNodeWithTag("machine-graph").performTouchInput { swipe(center, center + Offset(60f, 40f)) }
            runOnIdle { assertNotEquals(before, viewport.offset) }
            var scale = 0f
            runOnIdle { scale = viewport.scale }
            onNodeWithTag("machine-graph").performTouchInput { pinch(center - Offset(30f, 0f), center + Offset(30f, 0f), center - Offset(80f, 0f), center + Offset(80f, 0f)) }
            runOnIdle { assertTrue(viewport.scale > scale) }
            onNodeWithTag("graph-zoom-out").performClick()
            onNodeWithTag("graph-fit").performClick()
            runOnIdle { assertEquals(before, viewport.offset) }
            onNodeWithTag("machine-graph").performMouseInput {
                moveTo(center)
                scroll(1f)
            }
            runOnIdle {
                assertNotEquals(before, viewport.offset)
                scale = viewport.scale
            }
            onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
            onNodeWithTag("machine-graph").performMouseInput {
                moveTo(center)
                scroll(-1f)
            }
            onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
            runOnIdle { assertTrue(viewport.scale > scale) }
        }
}
