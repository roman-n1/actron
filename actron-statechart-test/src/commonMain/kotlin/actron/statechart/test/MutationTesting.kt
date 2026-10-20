@file:OptIn(actron.core.InternalActronApi::class)

package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.*
import actron.statechart.machine.Machine
import actron.statechart.machine.TransitionId
import kotlinx.coroutines.CancellationException

enum class MutationKind { RemoveTransition, RemoveGuard, RemoveEffect }

data class TransitionMutation(
    val transition: TransitionId,
    val kind: MutationKind,
)

enum class MutationOutcome { Killed, Survived, Invalid, Error }

data class MutationResult(
    val mutation: TransitionMutation,
    val outcome: MutationOutcome,
    val detail: String,
)

data class MutationReport(
    val results: List<MutationResult>,
) {
    val survivors: List<MutationResult> get() = results.filter { it.outcome == MutationOutcome.Survived }

    /** Invalid mutants and test harness errors cannot inflate the detected-mutation score. */
    fun assertAllKilled() {
        if (results.isEmpty() || results.any { it.outcome != MutationOutcome.Killed }) throw AssertionError("[Actron] Mutation testing incomplete: $results")
    }
}

/** Finite mutations of declarations; arbitrary Kotlin guard/reducer bodies are not rewritten. */
fun StateChartDefinition.mutationCandidates(): List<TransitionMutation> =
    transitions.flatMapIndexed { index, transition ->
        buildList {
            add(TransitionMutation(TransitionId(index), MutationKind.RemoveTransition))
            if (transition.guard != GuardCondition.Unconditional) add(TransitionMutation(TransitionId(index), MutationKind.RemoveGuard))
            if (transition.effect != TransitionEffect.NoEffect) add(TransitionMutation(TransitionId(index), MutationKind.RemoveEffect))
        }
    }

/** A passing original suite is mandatory. Only assertion failures count as detected mutations. */
suspend fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.testMutations(
    mutations: List<TransitionMutation> = chart.mutationCandidates(),
    verify: suspend (Machine<C, A, CMD, E>) -> Unit,
): MutationReport {
    require(mutations.isNotEmpty() && mutations.distinct().size == mutations.size)
    require(mutations.all { it in chart.mutationCandidates() }) { "[Actron] Mutation does not apply to this graph" }
    verify(this)
    val results =
        mutations.map { mutation ->
            val transitions =
                chart.transitions
                    .withIndex()
                    .filterNot {
                        it.index == mutation.transition.index && mutation.kind == MutationKind.RemoveTransition
                    }.map { (index, transition) ->
                        if (index != mutation.transition.index) {
                            transition
                        } else {
                            when (mutation.kind) {
                                MutationKind.RemoveTransition -> transition
                                MutationKind.RemoveGuard -> transition.copy(guard = GuardCondition.Unconditional)
                                MutationKind.RemoveEffect -> transition.copy(effect = TransitionEffect.NoEffect)
                            }
                        }
                    }
            val variant = chart.copy(transitions = transitions)
            try {
                val machine = withTestChart(variant)
                try {
                    verify(machine)
                    MutationResult(mutation, MutationOutcome.Survived, "The suite accepted the changed machine")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (assertion: AssertionError) {
                    MutationResult(mutation, MutationOutcome.Killed, assertion.message.orEmpty())
                } catch (error: Exception) {
                    MutationResult(mutation, MutationOutcome.Error, error.toString())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                MutationResult(mutation, MutationOutcome.Invalid, invalid.toString())
            }
        }
    return MutationReport(results)
}
