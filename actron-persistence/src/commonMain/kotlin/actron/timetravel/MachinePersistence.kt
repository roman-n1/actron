package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.CommandRecoveryDecision
import actron.statechart.machine.CommandRecoveryStrategy
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineStore
import actron.statechart.machine.resumeMachineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.serializer
import kotlin.coroutines.CoroutineContext

/**
 * Platform-independent persistence of one executor checkpoint. The host owns storage and
 * lifecycle. No history, accepted-but-undecided inputs or platform objects are saved.
 * Running commands restart at least once; handlers must be safe to repeat.
 */
@ExperimentalActronApi
class MachinePersistence<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    codec: RecordingCodec<C, A, CMD, E>,
) {
    private val checkpoints = MachineCheckpointCodec(machine, codec)
    internal val definitionKey: String get() = checkpoints.definitionKey

    /** Captures the executor through its ordered mailbox before serializing it. */
    suspend fun save(store: MachineStore<C, A, CMD, E>): String = save(store.checkpoint())

    /** Rejects incompatible or non-resumable checkpoints rather than silently losing work. */
    fun save(checkpoint: ExecutorCheckpoint<C, CMD>): String = checkpoints.encode(checkpoint)

    /** Throws [IllegalArgumentException] for invalid, incompatible or non-resumable data. */
    fun restore(saved: String): ExecutorCheckpoint<C, CMD> = checkpoints.decode(saved)

    /** Returns an unstarted store; call start after attaching consumers. Timers pause during absence. */
    fun resume(
        saved: String,
        handler: CommandHandler<CMD, A>,
        scope: CoroutineScope,
        coroutineContext: CoroutineContext = scope.coroutineContext,
        clock: MachineClock = MachineClock.monotonic(),
        observers: List<DecisionObserver<C, A, CMD, E>> = emptyList(),
        recovery: CommandRecoveryStrategy<CMD, A> = CommandRecoveryStrategy { CommandRecoveryDecision.Restart },
    ): MachineStore<C, A, CMD, E> {
        val checkpoint = restore(saved)
        return if (checkpoint.snapshot.isStarted) {
            resumeMachineStore(machine, checkpoint, handler, scope, coroutineContext, clock, observers, recovery)
        } else {
            MachineStore(machine, checkpoint.snapshot.context, handler, scope, coroutineContext = coroutineContext, observers = observers, clock = clock)
        }
    }
}

/** Uses generated serializers; supply a [RecordingCodec] to [MachinePersistence] for custom serializers. */
@ExperimentalActronApi
inline fun <reified C : Any, reified A : Action, reified CMD : Any, reified E : Event> machinePersistence(
    machine: Machine<C, A, CMD, E>,
): MachinePersistence<C, A, CMD, E> = MachinePersistence(machine, RecordingCodec(serializer<C>(), serializer<A>(), serializer<CMD>(), serializer<E>()))
