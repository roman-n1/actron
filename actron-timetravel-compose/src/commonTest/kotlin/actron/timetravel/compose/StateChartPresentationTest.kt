package actron.timetravel.compose

import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.machine.TransitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateChartPresentationTest {
    @Test
    fun aDanglingEdgeDoesNotChangeTheIdentityOfTheNextSelectableEdge() {
        val a = StateId("A")
        val b = StateId("B")
        val chart =
            StateChartDefinition(
                a,
                listOf(AtomicState(a), AtomicState(b)),
                listOf(
                    Transition(a, StateId("Missing"), ActionMatcher("Invalid")),
                    Transition(a, b, ActionMatcher("Valid")),
                ),
            )
        val edges = chartEdges(chart, mapOf(a to (0 to 0), b to (1 to 0)), ChartSelection.Transition(TransitionId(1)), ChartInspection.Unrequested)
        assertEquals(1, edges.size)
        assertEquals(0 to 0, edges.single().from)
        assertEquals(1 to 0, edges.single().to)
        assertTrue(edges.single().highlighted)
    }
}
