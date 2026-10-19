package actron.statechart

import actron.statechart.machine.TransitionId
import kotlin.test.*

class GraphDiagnosticsTest {
    private val idle = StateId("Idle")
    private val done = StateId("Done")
    private val lost = StateId("Lost")

    @Test
    fun findsUnreachableDeadEndAndExactShadowedArrow() {
        val trigger = ActionMatcher("Go")
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(done), AtomicState(lost)), listOf(Transition(idle, done, trigger), Transition(idle, done, trigger)))
        val issues = chart.diagnostics
        assertTrue(issues.any { it.code == GraphIssueCode.Unreachable && lost in it.states })
        assertTrue(issues.any { it.code == GraphIssueCode.NoExit && done in it.states })
        assertEquals(setOf(TransitionId(1)), issues.single { it.code == GraphIssueCode.ShadowedTransition }.transitions)
        assertTrue(issues.all { it.severity == GraphIssueSeverity.Warning })
        assertSame(issues, chart.diagnostics)
    }

    @Test
    fun validFinalAndAncestorExitDoNotProduceDeadEndWarnings() {
        val parent = StateId("Root")
        val chart = StateChartDefinition(parent, listOf(CompoundState(parent, initial = idle), AtomicState(idle, parent = parent), FinalState(done)), listOf(Transition(parent, done, ActionMatcher("Go"))))
        assertTrue(chart.diagnostics.isEmpty(), chart.diagnostics.toString())
    }

    @Test
    fun invalidEndpointsAndParentCyclesAreReportedWithoutExploration() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle)), listOf(Transition(idle, lost, ActionMatcher("Go"))))
        assertEquals(GraphIssueCode.UnknownEndpoint, chart.diagnostics.single().code)
        assertEquals(GraphIssueSeverity.Error, chart.diagnostics.single().severity)
        val cycle = StateChartDefinition(idle, listOf(CompoundState(idle, initial = done, parent = done), CompoundState(done, initial = idle, parent = idle)), emptyList())
        assertTrue(cycle.diagnostics.any { it.code == GraphIssueCode.InvalidHierarchy })
    }

    @Test
    fun budgetExhaustionNeverClaimsUnreachableAndDoesNotInvokeMatchers() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done), AtomicState(lost)), listOf(Transition(idle, done, ActionMatcher("Go"))))
        val issues = chart.diagnose(maxConfigurations = 1)
        assertTrue(issues.any { it.code == GraphIssueCode.AnalysisLimit })
        assertFalse(issues.any { it.code == GraphIssueCode.Unreachable })
    }
}
