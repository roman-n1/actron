package actron.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Core Actron interface for reading state, dispatching actions, and observing one-off events.
 *
 * Store startup is lazy.
 * Startup processing begins when [start] is called, when an action is [dispatch]ed, and,
 * with [AutoStartPolicy.OnDispatchOrStateCollection], when [state] starts being collected
 * or [observeState] is called.
 * Collecting [event], calling [observeEvent], or reading [currentState] alone does not start the
 * Store.
 */
interface Store<S : State, A : Action, E : Event> : AutoCloseable {
    /**
     * Hot stream of committed state snapshots.
     *
     * Collecting this flow starts the Store if it has not started yet and the configured
     * [AutoStartPolicy] includes state collection as a trigger.
     */
    val state: StateFlow<S>

    /**
     * Hot stream of one-off events emitted by the Store.
     *
     * Collecting this flow does not start the Store by itself.
     * Past events are not replayed to new collectors.
     */
    val event: Flow<E>

    /**
     * Current state snapshot without collecting [state].
     *
     * Reading this property does not start the Store.
     * If a [StateSaver] restores a saved state, this may return that restored snapshot
     * even before startup processing begins.
     */
    val currentState: S

    /**
     * Starts the Store if it has not started yet.
     *
     * This method returns immediately after requesting startup processing.
     * It does not wait for startup to finish.
     * Calling this method more than once has no additional effect after startup succeeds.
     * If a plugin's startup hook fails, a later call retries the unsuccessful hooks.
     */
    fun start()

    /**
     * Enqueues an action for processing.
     *
     * This enqueues the action and returns immediately.
     * It does not wait for action handling to complete.
     * If the Store has not started yet, this also triggers startup processing before the action
     * runs.
     * Actions are processed one at a time, in the order they were dispatched, on every dispatcher.
     *
     * @param action The action to dispatch
     */
    fun dispatch(action: A)

    /**
     * Observes state in the Store's execution context, with the same startup policy as state collection.
     * Closing the handle cancels only this observer, repeatedly and without waiting. An already
     * executing callback may finish. Closing the Store cancels all its observers.
     * Registration is asynchronous; StateFlow conflation and callback failure policy still apply.
     */
    fun observeState(observer: (S) -> Unit): AutoCloseable

    /**
     * Observes events in the Store's execution context without starting it or replaying past events.
     * The collector is registered before this method returns, so subsequent startup or dispatch
     * cannot overtake registration. Past events are not replayed. Callbacks run in the Store's
     * execution context. The handle has the same cancellation guarantees as [observeState].
     */
    fun observeEvent(observer: (E) -> Unit): AutoCloseable

    /**
     * Cancels the Store and waits for every owned coroutine, including cancellation cleanup.
     * Repeated calls are safe. Waiting is cancellable; cancelling the caller never reopens the Store.
     * Calling from an owned coroutine (including a launch, hook or observer) throws before closing:
     * it would wait for itself. Use [close] there. A peer Store can be closed and awaited.
     */
    suspend fun closeAndJoin()

    /**
     * Cancels the Store and releases its resources.
     *
     * Active state-scoped coroutines, plugin coroutines, and callback collectors are cancelled.
     */
    override fun close()
}
