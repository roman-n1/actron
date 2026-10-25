package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineSnapshot

/** Tracks the checked prefix by identity: recorder.clear() may replace even an equally long history. */
internal class DriverInvariantHistory<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
) {
    private sealed interface Cursor<out C : Any> {
        data object Start : Cursor<Nothing>

        data class Checked<C : Any>(
            val count: Int,
            val last: MachineSnapshot<C>,
        ) : Cursor<C>
    }

    private var cursor: Cursor<C> = Cursor.Start

    fun check(states: List<MachineSnapshot<C>>) {
        var index = nextIndex(states)
        while (index < states.size) {
            val state = states[index]
            if (state.isStarted) checkSnapshot(state)
            index++
            cursor = Cursor.Checked(index, state)
        }
    }

    private fun nextIndex(states: List<MachineSnapshot<C>>): Int =
        when (val checked = cursor) {
            Cursor.Start -> 0
            is Cursor.Checked -> if (states.size >= checked.count && states[checked.count - 1] === checked.last) checked.count else 0
        }

    private fun checkSnapshot(state: MachineSnapshot<C>) {
        val violations = machine.checkInvariants(state)
        if (violations.isNotEmpty()) throw AssertionError("[Actron] Invariants violated at revision ${state.revision}: ${violations.map { it.name }}")
    }
}
