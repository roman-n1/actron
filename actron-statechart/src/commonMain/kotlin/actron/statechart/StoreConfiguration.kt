@file:OptIn(actron.core.InternalActronApi::class)

package actron.statechart

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.AutoStartPolicy
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.InternalActronApi
import actron.core.Plugin
import actron.core.PluginExecutionPolicy
import actron.core.State
import actron.core.StateSaver
import actron.core.StoreBuilder
import actron.core.StoreProbe
import actron.observability.recordTo
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Machine runtime settings. The runtime owns its initial state
 * and handlers; this scope cannot install competing handlers or write a machine snapshot.
 * Settings called repeatedly use the last value; plugins and probes append in call order.
 */
@ActronStoreDsl
internal open class StoreConfiguration<S : State, A : Action, E : Event> internal constructor(
    @PublishedApi internal val delegate: StoreBuilder<S, A, E>,
    private val ownerContext: CoroutineContext = EmptyCoroutineContext,
    private val adaptStateSaver: (StateSaver<S>) -> StateSaver<S> = { it },
) {
    /** Sets the Store processing context. Command execution retains the MachineStore's own scope. */
    fun coroutineContext(context: CoroutineContext) = delegate.coroutineContext(context + ownerContext)

    /** Sets application-owned snapshot persistence; see the adapter's restoration contract. */
    fun stateSaver(saver: StateSaver<S>) = delegate.stateSaver(adaptStateSaver(saver))

    /** Handles failures which have not been recovered by the chart. Machines report failures here. */
    fun exceptionHandler(handler: ExceptionHandler) = delegate.exceptionHandler(handler)

    /** Selects which implicit triggers start the Store; explicit start is always available. */
    fun autoStartPolicy(policy: AutoStartPolicy) = delegate.autoStartPolicy(policy)

    /** Selects plugin execution order. All matching hooks finish before processing continues. */
    fun pluginExecutionPolicy(policy: PluginExecutionPolicy) = delegate.pluginExecutionPolicy(policy)

    /** Appends plugins observing the adapter's committed snapshots and inputs. */
    fun plugin(
        first: Plugin<S, A, E>,
        vararg rest: Plugin<S, A, E>,
    ) = delegate.plugin(first, *rest)

    /** Appends internal processing-boundary observers for journals and inspectors. */
    @InternalActronApi
    fun probe(
        first: StoreProbe<S, A, E>,
        vararg rest: StoreProbe<S, A, E>,
    ) = delegate.probe(first, *rest)
}

/** Records adapter processing using the same journal contract as the core Store builder. */
internal fun <S : State, A : Action, E : Event> StoreConfiguration<S, A, E>.recordTo(
    session: actron.observability.RecordingSession,
    store: actron.observability.StoreInstanceId,
    policy: actron.observability.PayloadPolicy<S, A, E> = actron.observability.PayloadPolicy.metadataOnly(),
) {
    delegate.recordTo(session, store, policy)
}

/** Journal scheduler inputs while application plugins receive normal feature actions. */
fun <C : Any, A : Action, E : Event> actron.statechart.machine.MachineConfiguration<C, A, E>.recordTo(
    session: actron.observability.RecordingSession,
    store: actron.observability.StoreInstanceId,
    policy: actron.observability.PayloadPolicy<actron.statechart.machine.MachineSnapshot<C>, actron.statechart.machine.MachineInput<A>, E> = actron.observability.PayloadPolicy.metadataOnly(),
) {
    runtime.recordTo(session, store, policy)
}
