package actron.decompose

import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.Store
import actron.core.observeEvent
import actron.core.observeState
import com.arkivanov.essenty.lifecycle.Lifecycle

/**
 * Observes until the handle closes or [lifecycle] is destroyed, without closing the Store.
 * Registration and handle closing must run on the lifecycle's owning thread.
 * Callbacks retain the Store's execution context, startup policy and asynchronous registration.
 * An already destroyed lifecycle does not register an observer or consume the Store's context.
 */
fun <S : State, A : Action, E : Event> Store<S, A, E>.observeState(
    lifecycle: Lifecycle,
    observer: (S) -> Unit,
): AutoCloseable = lifecycle.observe { observeState(observer) }

/**
 * Observes transient events until handle close or lifecycle destruction. Does not start the Store.
 * See [observeState] for threading guarantees. The event collector is ready when this method
 * returns; attach before starting the Store to observe its startup events.
 */
fun <S : State, A : Action, E : Event> Store<S, A, E>.observeEvent(
    lifecycle: Lifecycle,
    observer: (E) -> Unit,
): AutoCloseable = lifecycle.observe { observeEvent(observer) }

private fun Lifecycle.observe(register: () -> AutoCloseable): AutoCloseable {
    if (state == Lifecycle.State.DESTROYED) return AutoCloseable {}
    val handle = register()
    // An immediate initial-state callback may synchronously destroy the lifecycle.
    if (state == Lifecycle.State.DESTROYED) {
        handle.close()
        return handle
    }
    val observation = LifecycleObservation(this, handle)
    subscribe(observation)
    return observation
}

private class LifecycleObservation(
    private val lifecycle: Lifecycle,
    private val handle: AutoCloseable,
) : Lifecycle.Callbacks,
    AutoCloseable {
    override fun onDestroy() = close()

    override fun close() {
        lifecycle.unsubscribe(this)
        handle.close()
    }
}
