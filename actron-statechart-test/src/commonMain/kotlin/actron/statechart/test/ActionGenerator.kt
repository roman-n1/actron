package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime

/** Finite deterministic payload domain. Seeded randomness belongs to the caller, not wall time. */
class ActionGenerator<C : Any, A : Action>(
    val matcher: ActionMatcher,
    private val generate: (MachineSnapshot<C>, MachineTime) -> List<A>,
) {
    fun actions(
        snapshot: MachineSnapshot<C>,
        now: MachineTime,
    ): List<A> =
        generate(snapshot, now).also {
            require(it.all(matcher::matches)) { "[Actron] Generator emitted an action outside its declared matcher" }
        }
}
