package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.GuardCheck
import actron.statechart.machine.ExplainedDecision
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime

/** One independent prefix; no path merges contexts, histories or temporal obligations. */
internal data class ExplorationPath<C : Any, A : Action, CMD : Any, E : Event>(
    val snapshot: MachineSnapshot<C>,
    val inputs: List<MachineInput<A>>,
    val now: MachineTime,
    val depth: Int,
    val coverage: MachineCoverage,
    val steps: List<ScenarioStep<C, A, CMD, E>> = emptyList(),
) {
    fun advance(
        input: MachineInput<A>,
        explained: ExplainedDecision<C, CMD, E>,
        depth: Int,
        retainSteps: Boolean,
    ): ExplorationPath<C, A, CMD, E> =
        ExplorationPath(
            explained.decision.snapshot,
            inputs + input,
            input.now,
            depth,
            observedCoverage(explained),
            if (retainSteps) steps + ScenarioStep(snapshot, input, explained) else emptyList(),
        )

    private fun observedCoverage(explained: ExplainedDecision<C, CMD, E>): MachineCoverage {
        val transitions = coverage.transitions.covered + explained.decision.transitions
        val guards =
            explained.explanation.guards
                .filter { it.result == GuardCheck.Allowed || it.result == GuardCheck.Rejected }
                .map { GuardOutcome(it.transition, it.result == GuardCheck.Allowed) }
        return coverage.copy(
            states = coverage.states.copy(covered = coverage.states.covered + explained.explanation.active + explained.decision.snapshot.configuration.active + explained.decision.entered.map { it.node }),
            transitions = coverage.transitions.copy(covered = transitions),
            guards = coverage.guards.copy(covered = coverage.guards.covered + guards),
            timers = coverage.timers.copy(covered = transitions intersect coverage.timers.expected),
        )
    }
}
