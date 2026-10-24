package actron.core

/**
 * Optional lifecycle capability, separate from [Store] to preserve existing Store implementations.
 * Actron's Store factories implement it. Custom stores and wrappers can implement or delegate it.
 */
interface StoreLifecycle<S : State, E : Event> {
    /**
     * Observes state in the Store's execution context, with the same startup policy as [Store.collectState].
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
     * it would wait for itself. Use [Store.close] there. A peer Store can be closed and awaited.
     */
    suspend fun closeAndJoin()
}

/** See [StoreLifecycle.observeState]. Requires a Store implementing [StoreLifecycle]. */
fun <S : State, A : Action, E : Event> Store<S, A, E>.observeState(observer: (S) -> Unit): AutoCloseable = lifecycle().observeState(observer)

/** See [StoreLifecycle.observeEvent]. Requires a Store implementing [StoreLifecycle]. */
fun <S : State, A : Action, E : Event> Store<S, A, E>.observeEvent(observer: (E) -> Unit): AutoCloseable = lifecycle().observeEvent(observer)

/** See [StoreLifecycle.closeAndJoin]. Requires a Store implementing [StoreLifecycle]. */
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.closeAndJoin() = lifecycle().closeAndJoin()

private fun <S : State, A : Action, E : Event> Store<S, A, E>.lifecycle(): StoreLifecycle<S, E> = this as? StoreLifecycle<S, E> ?: error("[Actron] This Store does not implement StoreLifecycle; custom implementations and wrappers must provide or delegate that capability")
