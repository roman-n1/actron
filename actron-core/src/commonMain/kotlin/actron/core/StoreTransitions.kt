package actron.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Runs only inside the coordinator's mutex; does not admit inputs or launch root work. */
@OptIn(InternalActronApi::class)
internal class StoreTransitions<S : State, A : Action, E : Event>(
    private val handlers: StoreHandlers<S, A, E>,
    private val stateScopes: StoreStateScopes<S, A, E>,
    private val inputs: StoreInputs<S, A, E>,
    private val effects: StoreTransitionEffects<S, A>,
) {
    suspend fun dispatch(
        state: S,
        action: A,
    ) {
        try {
            effects.beforeAction(state, action)
            val nextState = stateScopes.action(state, action, handlers.onAction)
            commitTransition(state, nextState, inErrorHandling = false)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            recover(effects.currentState, t as Exception)
        }
    }

    suspend fun change(
        state: S,
        nextState: S,
    ) {
        try {
            commitTransition(state, nextState, inErrorHandling = false)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            recover(effects.currentState, t as Exception)
        }
    }

    /**
     * Applies [nextState] after a handler finished in [state].
     *
     * When the state variant changes, the old state exits first and pending actions are cleared
     * (per [PendingActionPolicy]) before the new state is committed, so actions dispatched in
     * reaction to the new state, for example by plugins or state collectors, are kept.
     */
    private suspend fun commitTransition(
        state: S,
        nextState: S,
        inErrorHandling: Boolean,
    ) {
        // Nothing commits after close(): a handler past its last suspension point would otherwise
        // still update the state, call the saver and the plugins after the Store was closed. The
        // current coroutine is checked, not the Store scope, so the cancellation seen here is the
        // handler's own and is never mistaken for a handler failure.
        currentCoroutineContext().ensureActive()
        val variantChanged = state::class != nextState::class
        if (variantChanged) {
            // Refused before anything is exited, so the state entered last keeps its runtime.
            check(enterChainDepth < MAX_ENTER_CHAIN) {
                "[Actron] More than $MAX_ENTER_CHAIN states were entered in one transition; enter {} handlers moving to each other in a loop?"
            }
            stateScopes.exit(state, handlers.onExit)
            // An exit hook may finish cleanup under NonCancellable after close() cancelled us.
            currentCoroutineContext().ensureActive()
            effects.clearOnStateExit()
        }

        if (state != nextState) {
            effects.publishState(state, nextState)
        }

        if (variantChanged) {
            enter(nextState, inErrorHandling = inErrorHandling)
        }
    }

    suspend fun enter(
        state: S,
        inErrorHandling: Boolean = false,
    ) {
        try {
            enterChainDepth++
            val nextState = stateScopes.enter(state, handlers.onEnter)
            commitTransition(state, nextState, inErrorHandling = inErrorHandling)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            if (inErrorHandling) {
                throw StoreBoundaryFailure(t)
            }
            recover(effects.currentState, t as Exception)
        } finally {
            enterChainDepth--
        }
    }

    // Depth of nested `enter {}` transitions in the current commit; a chain longer than
    // [MAX_ENTER_CHAIN] is an enter loop, which would otherwise overflow the stack or, when the
    // handlers suspend, spin forever while holding the lock.
    private var enterChainDepth = 0

    suspend fun recover(
        state: S,
        exception: Exception,
    ) {
        // A handler that failed after the Store was closed (an exit {} in NonCancellable cleanup,
        // for example) is not recovered: nothing may run in a closed Store, and the transition it
        // would produce could not be committed anyway.
        currentCoroutineContext().ensureActive()
        try {
            val nextState = stateScopes.recover(state, exception, handlers.onError)
            handlers.validateRecoveredState(state, nextState)
            commitTransition(state, nextState, inErrorHandling = true)
            inputs.current.recover(exception)
        } catch (t: Throwable) {
            // A failing recover {}, or a failing enter {} of the state it moved to, must not hide
            // the error that was being handled.
            val failure = t.boundaryCause()
            if (failure !== exception && failure !is CancellationException) failure.addSuppressed(exception)
            rethrowIfNonRecoverable(t)
            throw StoreBoundaryFailure(t)
        }
    }

    private companion object {
        const val MAX_ENTER_CHAIN = 500
    }
}

/** Ordered effects at the transition boundary, supplied by the mutex owner. */
internal interface StoreTransitionEffects<S : State, A : Action> {
    val currentState: S

    suspend fun beforeAction(
        state: S,
        action: A,
    )

    suspend fun publishState(
        state: S,
        nextState: S,
    )

    fun clearOnStateExit()
}
