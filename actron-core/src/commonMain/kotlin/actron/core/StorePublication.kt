package actron.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Owns committed snapshots and the event stream, including lazy restoration and ordered effects.
 * State commits require the processor's transition mutex; events may come from state launches.
 * Input accounting/revisions and plugin-round serialization remain with their existing owners.
 */
@OptIn(InternalActronApi::class)
internal class StorePublication<S : State, A : Action, E : Event>(
    private val configuration: StoreConfiguration<S, A, E>,
    private val inputs: StoreInputs<S, A, E>,
    private val execution: StoreExecution,
    private val reporting: StoreReporting<S, A, E>,
    private val onStatePublished: suspend (S, S) -> Unit,
    private val onEventEmitted: suspend (S, E) -> Unit,
) {
    private val _state: MutableStateFlow<S> by lazy {
        configuration.stateRead()
        MutableStateFlow(
            try {
                configuration.stateSaver.restore(configuration.initialState)
            } catch (t: Throwable) {
                reporting.handleException(t, input = InputAttribution.Unattributed)
                if (t is Exception) {
                    configuration.initialState
                } else {
                    throw t
                }
            },
        )
    }

    val state: StateFlow<S> get() = _state

    private val _event: MutableSharedFlow<E> = MutableSharedFlow()
    val event: Flow<E> = _event

    val currentState: S get() = _state.value

    // Once the new state is committed it is visible to collectors, so a failing saver or plugin
    // hook is reported instead of aborting the rest of the transition; otherwise the new state's
    // `enter {}` would never run and its runtime would be missing, so every later `launch {}` in
    // that state would fail.
    suspend fun commit(
        state: S,
        nextState: S,
    ) {
        inputs.commit(state, nextState) { _state.update { nextState } }
        try {
            configuration.stateSaver.save(nextState)
        } catch (t: Throwable) {
            reporting.reportWithoutAborting(t, inputs.current.attribution)
        }
        try {
            onStatePublished(state, nextState)
        } catch (t: StoreBoundaryFailure) {
            reporting.reportWithoutAborting(t, inputs.current.attribution)
        }
    }

    // The event has already reached its collectors, so a failing plugin hook is reported instead
    // of aborting the handler that emitted it.
    suspend fun emit(
        state: S,
        event: E,
    ) {
        val input = execution.attribution { inputs.current.attribution }
        _event.emit(event)
        reporting.trace { StoreTrace.EventEmitted(input, event) }
        // Emitted from a launched coroutine, this round runs outside the handler coroutines that
        // carry the marker; it holds the plugin lock, so awaiting the Store from the hook would
        // deadlock just the same.
        withContext(execution.inside) {
            try {
                onEventEmitted(state, event)
            } catch (t: StoreBoundaryFailure) {
                reporting.reportWithoutAborting(t, input)
            }
        }
    }
}
