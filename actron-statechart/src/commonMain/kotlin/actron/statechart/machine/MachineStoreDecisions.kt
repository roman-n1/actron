package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution

/** Computes one decision and selects its outcome under the core's processing lock. */
internal class MachineStoreDecisions<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    private val observers: List<DecisionObserver<C, A, CMD, E>>,
    private val observe: ((DecisionObserver<C, A, CMD, E>) -> Unit) -> Unit,
    private val publication: MachineStorePublication<C, A, CMD, E>,
) {
    @Suppress("UNCHECKED_CAST")
    fun decide(
        snapshot: MachineSnapshot<C>,
        input: MachineInput<A>,
        inputId: InputAttribution,
        commit: (MachineSnapshot<C>) -> Unit,
    ) {
        val decision =
            if (observers.any { it is DecisionExplanationObserver || it is DecisionTraceObserver<*, *, *, *> }) {
                // Diagnostics observe this computation, never a second evaluation of user rules.
                val explained = machine.decideExplained(snapshot, input)
                observe { observer ->
                    (observer as? DecisionExplanationObserver)?.onExplained(inputId, explained.explanation)
                    (observer as? DecisionTraceObserver<C, A, CMD, E>)?.onDecided(inputId, input, explained)
                }
                explained.decision
            } else {
                machine.decide(snapshot, input)
            }
        when (val outcome = decision.outcome) {
            DecisionOutcome.Handled -> {
                publication.stage(decision, inputId, input)
                commit(decision.snapshot)
            }

            is DecisionOutcome.Ignored -> {
                observe { it.onIgnored(inputId, input, outcome.reason) }
            }

            is DecisionOutcome.Failed -> {
                observe { it.onFailed(inputId, input, outcome.failure) }
                throw outcome.cause
            }
        }
    }
}
