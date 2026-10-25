package actron.timetravel.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Decision
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.test.ScenarioStep
import actron.timetravel.HistoryDetails
import actron.timetravel.StateField
import actron.timetravel.historyFields
import kotlinx.serialization.json.JsonElement

/** View-only projection of one step; recording identity, time and cumulative coverage stay with the export. */
internal class ScenarioHistoryDetails<C : Any>(
    private val describe: (C) -> JsonElement,
) {
    fun <A : Action, CMD : Any, E : Event> project(step: ScenarioStep<C, A, CMD, E>): HistoryDetails =
        HistoryDetails(
            input = inputLabel(step.input),
            source = source(step.input),
            outcome =
                step.explained.decision.outcome
                    .toString(),
            before = fields(step.before),
            after = fields(step.after),
            operations = operations(step.explained.decision),
            explanation = step.explained.explanation.describe(),
        )

    private fun fields(state: MachineSnapshot<C>): List<StateField> =
        listOf(
            StateField("active", state.configuration.active.joinToString()),
            StateField("revision", state.revision.toString()),
        ) + historyFields(describe(state.context))

    private fun inputLabel(input: MachineInput<*>): String =
        when (input) {
            is MachineInput.Dispatch -> input.action.toString()
            is MachineInput.CommandResult -> input.action.toString()
            else -> input.toString()
        }

    private fun source(input: MachineInput<*>): String =
        when (input) {
            is MachineInput.Dispatch -> "Action"
            is MachineInput.CommandResult -> "Command result"
            is MachineInput.TimerFired -> "Timer"
            else -> "Runtime"
        }

    private fun operations(decision: Decision<C, *, *>): List<String> =
        decision.commands.map { "Start ${it.command}" } +
            decision.timersScheduled.map { "Schedule $it" } +
            decision.timersCancelled.map { "Cancel timer $it" } +
            decision.cancelledScopes.map { "Cancel scope $it" } +
            decision.effects.map { "Emit ${it.event}" }
}
