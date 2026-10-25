@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.timetravel.compose

import actron.core.Action
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.FinalState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StateChartPanelTest {
    private data object Go : Action

    @Test
    fun snapshotChangesKeepSelectionAndDefinitionChangesResetItWithoutEvaluatingGuards() =
        runComposeUiTest {
            val idle = StateId("idle")
            val done = StateId("done")
            var definition by mutableStateOf(StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, ActionMatcher.of<Go>("go"), guard = actron.statechart.GuardKey("allowed")))))
            var guardCalls = 0
            val machine =
                Machine<Unit, Go, Nothing, Event>(DefinitionId("lifetime"), DefinitionVersion("1"), definition) {
                    guard("allowed") { _, _ ->
                        guardCalls++
                        true
                    }
                }
            val started = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
            val finished = machine.decide(started, MachineInput.Dispatch(Go, MachineTime.Zero)).snapshot
            var snapshot by mutableStateOf(started)
            setContent { StateChartPanel(definition, snapshot, previousActive = setOf(idle)) }
            onNodeWithTag("chart-transition-0").performClick()
            onNodeWithTag("chart-detail").assertTextContains("T0", substring = true)
            runOnIdle { snapshot = finished }
            onNodeWithTag("chart-detail").assertExists()
            onNodeWithTag("chart-node-idle").assertTextContains("previous", substring = true)
            onNodeWithTag("chart-node-done").assertTextContains("active", substring = true)
            runOnIdle { definition = definition.copy(transitions = emptyList()) }
            onNodeWithTag("chart-detail").assertDoesNotExist()
            runOnIdle { assertEquals(1, guardCalls, "Composition must only display recorded guard evaluations") }
        }

    @Test fun selectingNodesAndTransitionsShowsActivityAndActualGuardResults() =
        runComposeUiTest {
            val idle = StateId("idle")
            val done = StateId("done")
            val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, ActionMatcher.of<Go>("go"), guard = actron.statechart.GuardKey("allowed"))))
            val machine =
                Machine<Unit, Go, Nothing, Event>(DefinitionId("ui"), DefinitionVersion("1"), chart) {
                    guard("allowed") { _, _ -> false }
                }
            val snapshot = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
            val explained = machine.decideExplained(snapshot, MachineInput.Dispatch(Go, MachineTime.Zero))
            setContent { StateChartPanel(chart, snapshot, inspection = ChartInspection.Decided(explained.explanation, explained.decision.outcome.toString(), emptyList())) }
            onNodeWithTag("chart-node-idle").performClick()
            onNodeWithTag("chart-detail").assertTextContains("activation a1", substring = true)
            onNodeWithTag("chart-transition-0").performClick()
            onNodeWithTag("chart-detail").assertTextContains("GuardRejected", substring = true)
            onNodeWithText("allowed = false").assertExists()
        }
}
