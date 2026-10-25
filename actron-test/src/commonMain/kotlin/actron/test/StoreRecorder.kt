package actron.test

import actron.core.Action
import actron.core.Event
import actron.core.Plugin
import actron.core.PluginScope
import actron.core.State
import actron.core.Store

/**
 * Default in-memory recorder for tests. Implemented as a [Plugin] so it integrates with the Store
 * through the same lifecycle hooks as other plugins.
 *
 * Records the state at Store startup (which is the [actron.core.StateSaver]-restored value
 * when present, otherwise the Store's initial state), every committed state transition, and every
 * emitted event, in insertion order.
 *
 * The events can also be received one by one, in order, with [receiveEvent]: a cursor over
 * [events] that a test moves as it accounts for each event, so a test that ends with
 * [assertNoUnconsumedEvents] (or [assertNoPendingWork]) has seen every event the Store emitted
 * and none it did not expect. [events] keeps every event whether received or not.
 *
 * [states] and [events] are live read-only views; [unconsumedEvents] is a snapshot. Inspect or
 * clear history after the Store has settled (for example after [awaitIdle]); history and cursor
 * operations are not a concurrent collection API. One recorder accepts only one Store owner,
 * even if two Stores attempt startup concurrently. Clearing history does not release ownership.
 */
class StoreRecorder<S : State, A : Action, E : Event> internal constructor() : Plugin<S, A, E> {
    private val history = RecorderHistory<S, E>()
    private val owner = RecorderOwner<S, A>()

    /**
     * State snapshots recorded for this Store.
     */
    val states: List<S> = history.states

    /**
     * Events recorded for this Store.
     */
    val events: List<E> = history.events

    /**
     * The recorded events not yet received with [receiveEvent], in order.
     */
    val unconsumedEvents: List<E> get() = history.unconsumedEvents

    /**
     * Clears all recorded history and the cursor.
     */
    fun clear() {
        history.clear()
    }

    /**
     * Receives the next unconsumed event, which must be an [E2]: returns it and moves the cursor.
     *
     * @throws AssertionError if every recorded event was received already, or the next one is
     * not an [E2]; the cursor does not move then
     */
    inline fun <reified E2 : E> receiveEvent(): E2 {
        val next = peekUnconsumed("an event of type ${E2::class.simpleName}")
        if (next !is E2) throw AssertionError("[Actron] Expected an event of type ${E2::class.simpleName}, but the next unconsumed event is $next; unconsumed: $unconsumedEvents")
        consumeNext()
        return next
    }

    /**
     * Receives the next unconsumed event, which must satisfy [predicate]: returns it and moves
     * the cursor.
     *
     * @throws AssertionError if every recorded event was received already, or the next one does
     * not satisfy [predicate]; the cursor does not move then
     * @throws IllegalStateException if the predicate clears history or consumes another event;
     * its own changes remain, but this call does not consume a second event
     */
    fun receiveEvent(predicate: (E) -> Boolean): E = history.receiveEvent(predicate)

    /**
     * Fails when a recorded event was not received with [receiveEvent].
     *
     * @throws AssertionError listing the unconsumed events
     */
    fun assertNoUnconsumedEvents() = history.assertNoUnconsumedEvents()

    @PublishedApi
    internal fun peekUnconsumed(expected: String): E = history.peekUnconsumed(expected)

    @PublishedApi
    internal fun consumeNext() = history.consumeNext()

    override suspend fun onStart(
        scope: PluginScope<S, A>,
        state: S,
    ) {
        owner.claim(scope)
        history.recordState(state)
    }

    override suspend fun onState(
        scope: PluginScope<S, A>,
        prevState: S,
        state: S,
    ) {
        history.recordState(state)
    }

    override suspend fun onEvent(
        scope: PluginScope<S, A>,
        state: S,
        event: E,
    ) {
        history.recordEvent(event)
    }
}

/**
 * Creates and registers a [StoreRecorder] for this Store.
 *
 * The recorder is registered as a [Plugin] via [patch], which must happen before the Store is started.
 *
 * @return The registered [StoreRecorder]
 * @throws IllegalStateException if the Store has already been started or is starting
 * @throws IllegalStateException if the Store is not backed by Actron's internal implementation
 */
fun <S : State, A : Action, E : Event> Store<S, A, E>.createRecorder(): StoreRecorder<S, A, E> {
    val recorder = StoreRecorder<S, A, E>()
    try {
        patch { plugin(recorder) }
    } catch (e: IllegalStateException) {
        throw IllegalStateException("[Actron] createRecorder() must be called before the Store is started, dispatched to or its state collected", e)
    }
    return recorder
}

/**
 * Creates a [StoreRecorder], registers it, and runs [block] with this Store as the receiver and
 * the recorder as the argument.
 *
 * Intended for tests that want to scope a recording session to a single block. Inside the block,
 * use [startAndAwait] and [dispatchAndAwait] to drive the Store, then assert against the recorder's
 * `states` and `events`.
 *
 * @param block Test body that receives the registered [StoreRecorder]
 * @throws IllegalStateException if the Store has already been started or is starting
 * @throws IllegalStateException if the Store is not backed by Actron's internal implementation
 */
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.record(
    block: suspend Store<S, A, E>.(StoreRecorder<S, A, E>) -> Unit,
) {
    val recorder = createRecorder()
    block(recorder)
}
