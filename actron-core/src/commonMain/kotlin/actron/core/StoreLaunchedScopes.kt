package actron.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext

/** Adapts a launch's lifetime and action to the public DSL, without owning Store state or a lock. */
internal class StoreLaunchedScopes<S : State, E : Event>(
    private val execution: StoreScopeExecution<S, E>,
) {
    // A transaction runs in the Store's root scope so that it is atomic once started, but it is
    // skipped if, by the time it gets the lock, its state has exited or the coroutine that
    // requested it was cancelled (for example by [LaunchControl.CancelPrevious] or
    // `cancelLaunch()`); otherwise a cancelled launch could still commit a stale result.
    private fun canRunLaunchOperation(
        stateScope: CoroutineScope,
        caller: Job,
        owner: Job,
    ): Boolean {
        // NonCancellable replaces the caller's Job during cleanup. Keep the original launch's
        // cancellation identity too, so cleanup cannot resurrect a cancelled request.
        return stateScope.isActive && caller.isActive && !owner.isCancelled
    }

    suspend fun enter(stateScope: CoroutineScope): EnterLaunchScope<S, E, S> {
        val owner = currentCoroutineContext().job
        return object : EnterLaunchScope<S, E, S> {
            override val isActive: Boolean get() = stateScope.isActive

            override suspend fun event(event: E) {
                if (canRunLaunchOperation(stateScope, currentCoroutineContext().job, owner)) execution.emit(event)
            }

            override suspend fun transaction(
                dispatcher: CoroutineContext,
                block: suspend EnterTransactionScope<S, E, S>.() -> Unit,
            ) {
                val caller = currentCoroutineContext().job
                execution.transaction(
                    dispatcher = dispatcher,
                    canRun = { canRunLaunchOperation(stateScope, caller, owner) },
                ) { snapshot ->
                    var nextState: S = snapshot
                    block(
                        object : EnterTransactionScope<S, E, S> {
                            override val state: S = snapshot

                            override fun nextState(block: () -> S) {
                                nextState = block()
                            }

                            override fun clearPendingActions() {
                                execution.clearPendingActions()
                            }

                            override suspend fun event(event: E) {
                                execution.emit(event)
                            }
                        },
                    )
                    nextState
                }
            }
        }
    }

    suspend fun <A : Action> action(
        stateScope: CoroutineScope,
        launchedAction: A,
    ): ActionLaunchScope<S, A, E, S> {
        val owner = currentCoroutineContext().job
        return object : ActionLaunchScope<S, A, E, S> {
            override val isActive: Boolean get() = stateScope.isActive
            override val action: A = launchedAction

            override suspend fun event(event: E) {
                if (canRunLaunchOperation(stateScope, currentCoroutineContext().job, owner)) execution.emit(event)
            }

            override suspend fun transaction(
                dispatcher: CoroutineContext,
                block: suspend ActionTransactionScope<S, A, E, S>.() -> Unit,
            ) {
                val caller = currentCoroutineContext().job
                execution.transaction(
                    dispatcher = dispatcher,
                    canRun = { canRunLaunchOperation(stateScope, caller, owner) },
                ) { snapshot ->
                    var nextState: S = snapshot
                    block(
                        object : ActionTransactionScope<S, A, E, S> {
                            override val state: S = snapshot
                            override val action: A = launchedAction

                            override fun nextState(block: () -> S) {
                                nextState = block()
                            }

                            override fun clearPendingActions() {
                                execution.clearPendingActions()
                            }

                            override suspend fun event(event: E) {
                                execution.emit(event)
                            }
                        },
                    )
                    nextState
                }
            }
        }
    }
}
