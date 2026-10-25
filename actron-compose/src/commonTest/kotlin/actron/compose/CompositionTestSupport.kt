package actron.compose

import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.Store
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withRunningRecomposer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext

internal suspend fun TestScope.withComposition(
    content: @Composable () -> Unit,
    afterSetContent: suspend () -> Unit = {},
) {
    withNodeComposition(TestNode(), content) { afterSetContent() }
}

internal suspend fun TestScope.withNodeComposition(
    root: TestNode,
    content: @Composable () -> Unit,
    afterSetContent: suspend (pumpFrame: suspend () -> Unit) -> Unit = {},
) {
    val frameClock = BroadcastFrameClock()
    var frameTimeNanos = 0L

    suspend fun pumpFrame() {
        testScheduler.runCurrent()
        Snapshot.sendApplyNotifications()
        frameTimeNanos += 16_000_000L
        frameClock.sendFrame(frameTimeNanos)
        testScheduler.runCurrent()
    }

    withContext(frameClock) {
        withRunningRecomposer { recomposer ->
            val composition = Composition(TestApplier(root), recomposer)
            try {
                composition.setContent(content)
                repeat(2) { pumpFrame() }

                afterSetContent(::pumpFrame)
                repeat(2) { pumpFrame() }
            } finally {
                composition.dispose()
                pumpFrame()
            }
        }
    }
}

internal sealed interface UiState : State {
    data object Loading : UiState

    data object Busy : UiState

    data class Ready(
        val value: Int,
    ) : UiState
}

internal sealed interface UiAction : Action {
    data object Increment : UiAction
}

internal sealed interface UiEvent : Event {
    data object Reset : UiEvent

    data class ValueChanged(
        val value: Int,
    ) : UiEvent
}

internal class TestStore(
    initialState: UiState,
) : Store<UiState, UiAction, UiEvent> {
    override val state = MutableStateFlow(initialState)
    internal val eventFlow = MutableSharedFlow<UiEvent>()
    override val event: Flow<UiEvent> = eventFlow
    override val currentState: UiState get() = state.value

    val dispatchedActions = mutableListOf<UiAction>()
    var closeCount: Int = 0
        private set

    override fun start() = Unit

    override fun dispatch(action: UiAction) {
        dispatchedActions += action
    }

    override fun collectState(state: (UiState) -> Unit) = Unit

    override fun collectEvent(event: (UiEvent) -> Unit) = Unit

    override fun close() {
        closeCount++
    }
}

@Composable
internal fun BucketReader(
    viewStore: ViewStore<UiState, UiAction, UiEvent>,
    log: MutableList<Int>,
) {
    val bucket = viewStore.select { (it as UiState.Ready).value / 10 }
    log += bucket
}

@Composable
internal fun StateNode(state: UiState) {
    ComposeNode<TestNode, TestApplier>(
        factory = { TestNode() },
        update = {
            set(state) {
                this.state = it
            }
        },
    )
}

internal class TestNode {
    val children = mutableListOf<TestNode>()
    val renderHistory = mutableListOf<UiState>()
    var state: UiState
        get() = renderHistory.last()
        set(value) {
            renderHistory += value
        }

    val singleChild: TestNode
        get() = children.single()
}

internal class TestApplier(
    root: TestNode,
) : AbstractApplier<TestNode>(root) {
    override fun insertTopDown(
        index: Int,
        instance: TestNode,
    ) = Unit

    override fun insertBottomUp(
        index: Int,
        instance: TestNode,
    ) {
        current.children.add(index, instance)
    }

    override fun move(
        from: Int,
        to: Int,
        count: Int,
    ) {
        val children = current.children
        val moved = children.subList(from, from + count).toList()
        repeat(count) {
            children.removeAt(from)
        }
        children.addAll(if (to > from) to - count else to, moved)
    }

    override fun remove(
        index: Int,
        count: Int,
    ) {
        repeat(count) {
            current.children.removeAt(index)
        }
    }

    override fun onClear() {
        root.children.clear()
    }
}
