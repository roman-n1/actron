package actron.decompose

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock

/** Lazy checkpoint restoration/tracking, usable without recording or any debug dependency. */
class MachineRestoration<C : Any, A : Action, CMD : Any, E : Event>(
    val clock: MachineClock = MachineClock.monotonic(),
    val observers: (Machine<C, A, CMD, E>, ExecutorCheckpoint<C, CMD>) -> List<DecisionObserver<C, A, CMD, E>> = { _, _ -> emptyList() },
    val restore: (Machine<C, A, CMD, E>, C) -> ExecutorCheckpoint<C, CMD>,
)
