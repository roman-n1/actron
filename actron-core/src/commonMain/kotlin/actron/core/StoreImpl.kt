package actron.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(InternalActronApi::class)
internal class StoreImpl<S : State, A : Action, E : Event>(
    private val processor: StoreProcessor<S, A, E>,
    internalApi: StoreInternalApi<S, A, E>,
) : Store<S, A, E>,
    StoreInternalApi<S, A, E> by internalApi,
    StoreLifecycle<S, E> {
    internal val handlerRegistry: HandlerRegistry<S, A, E> get() = processor.handlerRegistry

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    override val state: StateFlow<S> by lazy {
        object : StateFlow<S> {
            override val replayCache: List<S> get() = processor.state.replayCache
            override val value: S get() = processor.state.value

            // Collect in the caller's coroutine so first()/take(n) can stop collection. Request
            // startup only after reading the initial value, before delivering it to the collector.
            override suspend fun collect(collector: FlowCollector<S>): Nothing {
                var startupRequested = false
                processor.state.collect { value ->
                    if (!startupRequested) {
                        startupRequested = true
                        if (processor.configuration.autoStartPolicy == AutoStartPolicy.OnDispatchOrStateCollection) {
                            processor.launchStartup()
                        }
                    }
                    collector.emit(value)
                }
            }
        }
    }

    override val event: Flow<E> get() = processor.event
    override val currentState: S get() = processor.currentState

    override fun dispatch(action: A) {
        processor.launchDispatch(action)
    }

    override fun start() {
        processor.launchStartup()
    }

    override fun collectState(state: (S) -> Unit) {
        observeState(state)
    }

    override fun collectEvent(event: (E) -> Unit) {
        // Preserve the legacy asynchronous registration; the new API registers before returning.
        processor.execution.scope.launch { this@StoreImpl.event.collect { event(it) } }
    }

    override fun observeState(observer: (S) -> Unit): AutoCloseable {
        val job = processor.execution.scope.launch { state.collect { observer(it) } }
        return AutoCloseable { job.cancel() }
    }

    override fun observeEvent(observer: (E) -> Unit): AutoCloseable {
        val job = processor.execution.scope.launch(start = CoroutineStart.UNDISPATCHED) { event.collect { observer(it) } }
        return AutoCloseable { job.cancel() }
    }

    override suspend fun closeAndJoin() = processor.execution.closeAndJoin()

    override fun close() {
        processor.execution.close()
    }
}
