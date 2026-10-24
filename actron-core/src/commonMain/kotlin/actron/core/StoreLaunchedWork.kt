package actron.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Bridges state-owned launches to root-owned transaction and recovery inputs.
 * Borrows the processor's transition mutex; validity, snapshots and transitions use that lock.
 * StateRuntime and StoreExecution retain ownership of state/root coroutine lifetimes.
 */
@OptIn(InternalActronApi::class)
internal class StoreLaunchedWork<S : State, A : Action, E : Event>(
    private val execution: StoreExecution,
    private val inputs: StoreInputs<S, A, E>,
    private val mutex: Mutex,
    private val transitions: StoreTransitions<S, A, E>,
    private val readState: () -> S,
) {
    fun <LS : Any> launch(
        stateRuntime: StateRuntime,
        dispatcher: CoroutineContext,
        buildLaunchScope: suspend () -> LS,
        block: suspend LS.() -> Unit,
    ): Job =
        stateRuntime.scope.launch(dispatcher + inputs.current.launchOrigin()) {
            executeLaunch(
                stateRuntime = stateRuntime,
                dispatcher = dispatcher,
                buildLaunchScope = buildLaunchScope,
                block = block,
            )
        }

    private suspend fun <LS : Any> executeLaunch(
        stateRuntime: StateRuntime,
        dispatcher: CoroutineContext,
        buildLaunchScope: suspend () -> LS,
        block: suspend LS.() -> Unit,
    ) {
        val launchScope = buildLaunchScope()
        try {
            block(launchScope)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            // A failure during cancellation cleanup belongs to the cancelled request. Report it
            // normally, but never recover it by changing the current request's state.
            val failedJob = currentCoroutineContext().job
            if (failedJob.isCancelled) throw t
            // The recovery is an input of its own: it runs under the lock, after whatever is
            // being processed now, and may commit through recover {}.
            val origin = currentInputId()
            val input = inputs.accept(InputKind.Recovery(t, origin))
            val queued = QueuedStoreInput(input, inputs::discard)
            execution.scope
                .launch(dispatcher + execution.inside + InputOrigin(input)) {
                    mutex.withLock {
                        queued.locked = true
                        inputs.process(input) {
                            if (stateRuntime.scope.isActive && !failedJob.isCancelled) {
                                transitions.recover(readState(), t as Exception)
                            } else {
                                // The state exited before this report got the lock: there is no state to
                                // recover into, but the failure is not dropped. Like the failure of a
                                // launch that was already cancelled, it reaches the exception handler.
                                throw t
                            }
                        }
                    }
                }.invokeOnCompletion { cause -> if (cause is CancellationException) queued.onCancellation(cause) }
        }
    }

    suspend fun transaction(
        dispatcher: CoroutineContext,
        canRun: () -> Boolean,
        block: suspend (S) -> S,
    ) {
        execution.checkOutside("transaction")
        val input = inputs.accept(InputKind.Transaction(currentInputId()))
        val queued = QueuedStoreInput(input, inputs::discard)
        // A transaction belongs to the root scope: once admitted, cancelling its launch must
        // not split the atomic operation. Validity is checked before running its block.
        val job =
            execution.scope.launch(dispatcher + execution.inside + InputOrigin(input)) {
                mutex.withLock {
                    queued.locked = true
                    if (!canRun()) {
                        inputs.discard(input, DiscardReason.LaunchInactive)
                        return@withLock
                    }
                    inputs.process(input) {
                        val nextState =
                            try {
                                block(readState())
                            } catch (t: Throwable) {
                                rethrowIfNonRecoverable(t)
                                transitions.recover(readState(), t as Exception)
                                return@process
                            }
                        if (nextState != readState()) transitions.change(readState(), nextState)
                    }
                }
            }
        job.invokeOnCompletion { cause -> if (cause is CancellationException) queued.onCancellation(cause) }
        job.join()
    }
}
