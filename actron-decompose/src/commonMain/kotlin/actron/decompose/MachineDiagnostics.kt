package actron.decompose

import actron.core.Action
import actron.core.Event
import actron.statechart.StoreConfiguration
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot

/** A lazy recording option: binding happens only for a newly retained instance, before startup. */
fun interface MachineRecording<C : Any, A : Action, CMD : Any, E : Event> {
    fun prepare(
        machine: Machine<C, A, CMD, E>,
        checkpoint: ExecutorCheckpoint<C, CMD>,
        handler: CommandHandler<CMD, A>,
    ): MachineDiagnostics<C, A, CMD, E>
}

/** Runtime inputs assembled by an option; the retained owner alone starts and closes the Store. */
class MachineDiagnostics<C : Any, A : Action, CMD : Any, E : Event>(
    val handler: CommandHandler<CMD, A>,
    val observers: List<DecisionObserver<C, A, CMD, E>> = emptyList(),
    val configure: StoreConfiguration<MachineSnapshot<C>, MachineInput<A>, E>.() -> Unit = {},
)
