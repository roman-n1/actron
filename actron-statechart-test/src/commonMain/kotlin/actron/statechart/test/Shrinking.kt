package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot

/** Replays inputs from the same snapshot and reports the first failure, without executing IO. */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.replaySequence(
    initial: MachineSnapshot<C>,
    inputs: List<MachineInput<A>>,
    accept: (SequenceFailure<C, A>) -> Unit,
): Boolean {
    val initialViolations =
        if (initial.isStarted) {
            checkInvariants(initial)
        } else {
            require(initial.definition == id && initial.version == version) { "[Actron] Snapshot belongs to another machine or version" }
            emptyList()
        }
    if (initialViolations.isNotEmpty()) {
        accept(SequenceFailure(emptyList(), initial, listOf(SequenceProblem.Invariants(initialViolations))))
        return true
    }
    var snapshot = initial
    val prefix = mutableListOf<MachineInput<A>>()
    for (input in inputs) {
        prefix += input
        val decision = decide(snapshot, input)
        if (sequenceFailure(prefix, decision, accept)) return true
        snapshot = decision.snapshot
    }
    return false
}

/**
 * Removes chunks, then single inputs, preserving at least one original failure identity. Each
 * attempt replays from [initial], including Start when present. Inputs keep their actual payloads,
 * times and ids: stale command/timer inputs become ignored through normal Machine semantics.
 *
 * An untruncated result is deletion-minimal, not guaranteed globally shortest. No payload shrinking
 * or reassigning recorded command ids is implied. [maxAttempts] bounds replay work.
 */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.shrink(
    initial: MachineSnapshot<C>,
    inputs: List<MachineInput<A>>,
    maxAttempts: Int = 1_000,
): ShrinkReport<C, A> =
    shrink(initial, inputs, maxAttempts) { original, candidate ->
        candidate.identities.any { it in original.identities }
    }

/** Custom semantic failure matching; the pure predicate must accept the original failure itself. */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.shrink(
    initial: MachineSnapshot<C>,
    inputs: List<MachineInput<A>>,
    maxAttempts: Int = 1_000,
    preservesFailure: (original: SequenceFailure<C, A>, candidate: SequenceFailure<C, A>) -> Boolean,
): ShrinkReport<C, A> {
    require(maxAttempts > 0) { "[Actron] Shrinking needs a positive attempt budget" }
    return SequenceShrinker(this, initial, inputs, maxAttempts, preservesFailure).run()
}
