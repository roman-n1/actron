package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot

/** Pure replay comparison; snapshot fields precede intents, with timer diagnostics first. */
internal object ReplayComparison {
    fun <C : Any, A : Action, CMD : Any, E : Event> differences(
        base: MachineSnapshot<C>,
        step: RecordedStep<C, A, CMD, E>,
        actual: Decision<C, CMD, E>,
    ): List<String> {
        val differences = mutableListOf<String>()
        differences += timerDifferences(base, step.input)
        when (step) {
            is RecordedStep.Committed -> {
                if (actual.outcome != DecisionOutcome.Handled) {
                    differences += "outcome: recorded Handled, now ${actual.outcome}"
                } else {
                    differences += compare(step.decision, actual)
                }
            }

            is RecordedStep.Ignored -> {
                val outcome = actual.outcome
                if (outcome !is DecisionOutcome.Ignored) {
                    differences += "outcome: recorded Ignored(${step.reason}), now $outcome"
                } else if (outcome.reason != step.reason) {
                    differences += "ignore reason: recorded ${step.reason}, now ${outcome.reason}"
                }
            }

            is RecordedStep.Failed -> {
                val outcome = actual.outcome
                if (outcome !is DecisionOutcome.Failed) {
                    differences += "outcome: recorded Failed(${step.failure.typeLabel}), now $outcome"
                } else if (!outcome.failure.sameType(step.failure)) {
                    differences += "failure: recorded ${step.failure.typeLabel}, now ${outcome.failure.typeLabel}"
                }
            }
        }
        return differences
    }

    private fun <C : Any, A : Action> timerDifferences(
        base: MachineSnapshot<C>,
        input: MachineInput<A>,
    ): List<String> {
        val differences = mutableListOf<String>()
        if (input is MachineInput.TimerFired) {
            if (input.timer in base.timers) {
                val timer = base.timers.getValue(input.timer)
                if (input.now < timer.deadline) differences += "timer ${input.timer} fired at ${input.now}, before its deadline ${timer.deadline}"
            }
        }
        return differences
    }

    private fun <C : Any, CMD : Any, E : Event> compare(
        expected: Decision<C, CMD, E>,
        actual: Decision<C, CMD, E>,
    ): List<String> = snapshotDifferences(expected.snapshot, actual.snapshot) + intentDifferences(expected, actual)

    private fun <C : Any> snapshotDifferences(
        expected: MachineSnapshot<C>,
        actual: MachineSnapshot<C>,
    ): List<String> =
        buildList {
            if (expected.revision != actual.revision) add("revision: recorded ${expected.revision}, now ${actual.revision}")
            if (expected.configuration != actual.configuration) add("configuration: recorded ${expected.configuration.active}, now ${actual.configuration.active}")
            if (expected.context != actual.context) add("context: recorded ${expected.context}, now ${actual.context}")
            if (expected.activations != actual.activations) add("activations: recorded ${expected.activations}, now ${actual.activations}")
            if (expected.commands != actual.commands) add("registered commands: recorded ${expected.commands.keys}, now ${actual.commands.keys}")
            if (expected.timers != actual.timers) add("scheduled timers: recorded ${expected.timers}, now ${actual.timers}")
            if (expected.counters != actual.counters) add("counters: recorded ${expected.counters}, now ${actual.counters}")
        }

    private fun <C : Any, CMD : Any, E : Event> intentDifferences(
        expected: Decision<C, CMD, E>,
        actual: Decision<C, CMD, E>,
    ): List<String> {
        val differences = mutableListOf<String>()
        if (expected.transitions != actual.transitions) differences.add("transitions: recorded ${expected.transitions}, now ${actual.transitions}")
        if (expected.exited != actual.exited) differences.add("exited: recorded ${expected.exited}, now ${actual.exited}")
        if (expected.entered != actual.entered) differences.add("entered: recorded ${expected.entered}, now ${actual.entered}")
        if (expected.commands != actual.commands) differences.add("commands: recorded ${expected.commands}, now ${actual.commands}")
        if (expected.cancelledScopes != actual.cancelledScopes) differences.add("cancelled scopes: recorded ${expected.cancelledScopes}, now ${actual.cancelledScopes}")
        if (expected.timersScheduled != actual.timersScheduled) differences.add("timers scheduled: recorded ${expected.timersScheduled}, now ${actual.timersScheduled}")
        if (expected.timersCancelled != actual.timersCancelled) differences.add("timers cancelled: recorded ${expected.timersCancelled}, now ${actual.timersCancelled}")
        if (expected.effects != actual.effects) differences.add("effects: recorded ${expected.effects}, now ${actual.effects}")
        return differences
    }
}
