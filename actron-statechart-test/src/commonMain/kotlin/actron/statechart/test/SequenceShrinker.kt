package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot

/** One reduction run owns its replay budget and original failure identity. */
internal class SequenceShrinker<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    private val initial: MachineSnapshot<C>,
    inputs: List<MachineInput<A>>,
    private val maxAttempts: Int,
    private val preserves: (SequenceFailure<C, A>, SequenceFailure<C, A>) -> Boolean,
) {
    private enum class Reduction { Reduced, Unchanged, Exhausted }

    private val original = originalFailure(inputs)
    private var best = original
    private var attempts = 1

    init {
        require(preserves(original, original)) { "[Actron] Preservation predicate rejected the original failure" }
    }

    fun run(): ShrinkReport<C, A> {
        var size = maxOf(1, best.inputs.size / 2)
        var truncated = false
        while (best.inputs.isNotEmpty()) {
            when (reduce(size)) {
                Reduction.Exhausted -> {
                    truncated = true
                    break
                }

                Reduction.Reduced -> {
                    size = maxOf(1, best.inputs.size / 2)
                }

                Reduction.Unchanged -> {
                    if (size == 1) break
                    size = maxOf(1, size / 2)
                }
            }
        }
        return ShrinkReport(best, attempts, truncated)
    }

    private fun reduce(size: Int): Reduction {
        var position = 0
        while (position < best.inputs.size) {
            if (attempts >= maxAttempts) return Reduction.Exhausted
            val candidate = best.inputs.take(position) + best.inputs.drop(position + size)
            attempts++
            var reduced = false
            machine.replaySequence(initial, candidate) { failure ->
                if (preserves(original, failure)) {
                    best = failure
                    reduced = true
                }
            }
            if (reduced) return Reduction.Reduced
            position += size
        }
        return Reduction.Unchanged
    }

    private fun originalFailure(inputs: List<MachineInput<A>>): SequenceFailure<C, A> {
        var observed: () -> SequenceFailure<C, A> = { error("[Actron] Failure callback was not called") }
        require(machine.replaySequence(initial, inputs) { failure -> observed = { failure } }) { "[Actron] Sequence does not fail" }
        return observed()
    }
}
