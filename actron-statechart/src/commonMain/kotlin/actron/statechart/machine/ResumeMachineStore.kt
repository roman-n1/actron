package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

internal sealed interface StoreOrigin<out C : Any, out CMD : Any> {
    data object Fresh : StoreOrigin<Nothing, Nothing>

    data class Resumed<C : Any, CMD : Any>(
        val checkpoint: ExecutorCheckpoint<C, CMD>,
    ) : StoreOrigin<C, CMD>
}

/**
 * Continues an isolated run at a checkpoint without executing entry hooks again.
 * Running commands are executed again (at-least-once); use only with restartable handlers.
 * Queued commands retain their lanes, and timers retain their remaining delay. Old accepted
 * inputs and transient effects are not replayed. Checkpoints with unacknowledged effects or
 * ending commands are rejected because their undelivered outcomes cannot be reconstructed.
 * Close the original store when replacing it; this factory never mutates another store.
 */
@ExperimentalActronApi
fun <C : Any, A : Action, CMD : Any, E : Event> resumeMachineStore(
    machine: Machine<C, A, CMD, E>,
    checkpoint: ExecutorCheckpoint<C, CMD>,
    handler: CommandHandler<CMD, A>,
    scope: CoroutineScope,
    coroutineContext: CoroutineContext = scope.coroutineContext,
    clock: MachineClock = MachineClock.monotonic(),
    observers: List<DecisionObserver<C, A, CMD, E>> = emptyList(),
    recovery: CommandRecoveryStrategy<CMD, A> = CommandRecoveryStrategy { CommandRecoveryDecision.Restart },
): MachineStore<C, A, CMD, E> {
    require(checkpoint.snapshot.definition == machine.id && checkpoint.snapshot.version == machine.version) { "Checkpoint belongs to a different machine" }
    require(checkpoint.snapshot.isStarted) { "Resume requires a started snapshot" }
    require(checkpoint.ending.isEmpty()) { "Cannot resume commands with undelivered terminal outcomes" }
    require(checkpoint.effects.isEmpty()) { "Cannot resume unacknowledged effects" }
    require(machine.checkInvariants(checkpoint.snapshot).isEmpty()) { "Checkpoint violates machine invariants" }
    val resumedClock = clock.rebased(checkpoint.now)
    return MachineStoreImpl(
        machine,
        checkpoint.snapshot.context,
        handler,
        scope,
        resumedClock,
        coroutineContext,
        AdmissionPolicy.Unbounded,
        observers,
        MailboxConfig(),
        {},
        StoreOrigin.Resumed(checkpoint),
        recovery,
    )
}
