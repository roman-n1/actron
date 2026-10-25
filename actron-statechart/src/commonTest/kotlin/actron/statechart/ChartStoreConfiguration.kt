@file:OptIn(actron.core.InternalActronApi::class)

package actron.statechart

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.core.StoreBuilder
import actron.core.StoreScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Recovery of chart data without access to configuration, timers, or arbitrary state writes. */
@ActronStoreDsl
internal interface ChartRecoveryScope<C : Any, E : Event, T : Exception> {
    /** The underlying handler scope, for extensions such as `store.message(...)`. */
    val store: StoreScope

    /** The failure being handled. */
    val error: T

    /** Working context; committed once if this recovery handler completes successfully. */
    var context: C

    /** Emits an event immediately, before recovery commits. */
    suspend fun event(event: E)
}

/** Chart runtime settings and recovery handlers which can change context only. */
@ActronStoreDsl
internal class ChartStoreConfiguration<C : Any, A : Action, E : Event> internal constructor(
    delegate: StoreBuilder<ChartState<C>, A, E>,
) : StoreConfiguration<ChartState<C>, A, E>(delegate) {
    /**
     * Handles [T], updating only context. The first matching handler wins. A throwing handler
     * commits no context change and reaches the exception handler. To change active nodes,
     * dispatch a declared chart action after recovery.
     */
    inline fun <reified T : Exception> recover(
        dispatcher: CoroutineContext = EmptyCoroutineContext,
        noinline block: suspend ChartRecoveryScope<C, E, T>.() -> Unit,
    ) {
        delegate.state<ChartState<C>> {
            recover<T>(dispatcher) {
                val underlying = this
                val scope =
                    object : ChartRecoveryScope<C, E, T> {
                        override val store: StoreScope = underlying
                        override val error: T = underlying.error
                        override var context: C = underlying.state.context

                        override suspend fun event(event: E) = underlying.event(event)
                    }
                scope.block()
                nextState { state.copy(context = scope.context) }
            }
        }
    }
}
