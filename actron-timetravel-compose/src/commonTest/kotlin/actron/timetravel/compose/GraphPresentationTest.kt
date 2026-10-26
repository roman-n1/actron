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
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class GraphPresentationTest {
    @Test
    fun selectionOverridesHeatAndCoverageWhileOtherEdgesDimAndEndpointsStandOut() {
        val a = StateId("A")
        val b = StateId("B")
        val chart = StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b)), listOf(Transition(a, b, ActionMatcher("Go")), Transition(b, a, ActionMatcher("Back"))))
        val graph =
            HistoryGraph.Machine(
                chart,
                emptySet(),
                HistoryGraphCoverage.Recorded(mapOf(TransitionId(0) to 3)),
                HistoryGraphProfile.Recorded(
                    MachineProfileSnapshot(
                        DefinitionId("graph"),
                        DefinitionVersion("1"),
                        transitions =
                            mapOf(
                                TransitionId(0) to TransitionProfile(3, processing = DurationProfile(1, 10.milliseconds, 10.milliseconds)),
                            ),
                    ),
                ),
            )
        val layout = layoutGraph(chart)
        val selection = GraphInteraction()
        val measurements = graphMeasurements(graph)
        val colors = lightColorScheme()
        val coverageOnly = graph.copy(profile = HistoryGraphProfile.Unavailable)
        val covered = graphAppearance(coverageOnly, layout, selection, graphMeasurements(coverageOnly), colors)
        assertEquals(colors.tertiary, covered.edges[0].color)
        assertEquals(2.5f, covered.edges[0].stroke.width)
        assertTrue(covered.edges[1].stroke.dashed)
        assertEquals(listOf(true, false), covered.nodes.map { it.initial })
        val hot = graphAppearance(graph, layout, selection, measurements, colors)
        assertTrue(hot.showProfileWindow)
        assertEquals(colors.error, hot.edges[0].color)
        selection.select(layout.edges[1].label.center, layout, GraphViewport())
        val focused = graphAppearance(graph, layout, selection, measurements, colors)
        assertEquals(colors.primary, focused.edges[1].color)
        assertEquals(3f, focused.edges[1].stroke.width)
        assertFalse(focused.edges[1].stroke.dashed)
        assertEquals(colors.secondaryContainer, focused.edges[1].labelFill)
        assertEquals(colors.error.copy(alpha = 0.25f), focused.edges[0].color)
        focused.nodes.forEach {
            assertEquals(colors.primary, it.outline)
            assertEquals(3f, it.stroke)
        }
        selection.select(layout.nodes[0].bounds.topLeft + Offset(10f, 10f), layout, GraphViewport())
        val source = graphAppearance(graph, layout, selection, measurements, colors)
        assertEquals(colors.primary, source.edges[0].color)
        assertEquals(colors.outline, source.edges[1].color)
        assertEquals(colors.surface, source.edges[1].labelFill)
    }

    @Test
    fun unavailableCoverageAndProfileRemainDistinctFromRecordedZero() {
        val a = StateId("A")
        val chart = StateChartDefinition(a, listOf(AtomicState(a)), listOf(Transition(a, a, ActionMatcher("Again"))))
        val graph = HistoryGraph.Machine(chart, setOf(a))
        val selection = GraphInteraction()
        val appearance = graphAppearance(graph, layoutGraph(chart), selection, graphMeasurements(graph), lightColorScheme())
        assertFalse(appearance.showProfileWindow)
        assertFalse(
            appearance.edges
                .single()
                .stroke.dashed,
        )
        val zero = graph.copy(coverage = HistoryGraphCoverage.Recorded(emptyMap()))
        val recorded = graphAppearance(zero, layoutGraph(chart), selection, graphMeasurements(zero), lightColorScheme())
        assertTrue(
            recorded.edges
                .single()
                .stroke.dashed,
        )
    }
}
