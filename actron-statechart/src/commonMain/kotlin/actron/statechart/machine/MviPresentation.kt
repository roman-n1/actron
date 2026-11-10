package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The UI sees only its immutable display state and sends only its own events. */
interface MviPresentation<U : Any, I : Any> {
    val state: StateFlow<U>

    fun onUiEvent(event: I)
}

/** Maps UI payloads to domain actions; an obsolete UI event may emit no action. */
fun interface UiActionMapper<I : Any, C : Any, A : Action> {
    fun map(
        event: I,
        state: C,
        dispatch: (A) -> Unit,
    )
}

/** Both mappers belong to the feature's presentation boundary, outside its machine definition. */
fun <C : Any, A : Action, CMD : Any, E : Event, U : Any, I : Any> MachineStore<C, A, CMD, E>.present(
    stateMapper: (MachineSnapshot<C>) -> U,
    actionMapper: UiActionMapper<I, C, A>,
): MviPresentation<U, I> = present(state.projectState(stateMapper), actionMapper)

/** Also supports a screen whose immutable display state combines several independent machines. */
fun <C : Any, A : Action, CMD : Any, E : Event, U : Any, I : Any> MachineStore<C, A, CMD, E>.present(
    uiState: StateFlow<U>,
    actionMapper: UiActionMapper<I, C, A>,
): MviPresentation<U, I> =
    object : MviPresentation<U, I> {
        override val state: StateFlow<U> = uiState

        override fun onUiEvent(event: I) {
            val snapshot = this@present.state.value
            if (snapshot.isStarted) actionMapper.map(event, snapshot.context, this@present::dispatch)
        }
    }

/** A projection, not a second mutable state owner. A collector owns its subscriptions. */
fun <S : Any, U : Any> StateFlow<S>.projectState(mapper: (S) -> U): StateFlow<U> = ProjectedStateFlow({ mapper(value) }, map(mapper).distinctUntilChanged())

/** Reads both current values directly; no shared scope, eager job or copied mutable state. */
fun <A : Any, B : Any, U : Any> combineStates(
    first: StateFlow<A>,
    second: StateFlow<B>,
    mapper: (A, B) -> U,
): StateFlow<U> = ProjectedStateFlow({ mapper(first.value, second.value) }, combine(first, second, mapper).distinctUntilChanged())

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ProjectedStateFlow<U : Any>(
    private val current: () -> U,
    private val source: kotlinx.coroutines.flow.Flow<U>,
) : StateFlow<U> {
    override val value: U get() = current()
    override val replayCache: List<U> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<U>): Nothing {
        source.collect(collector)
        error("StateFlow collection must not complete")
    }
}
