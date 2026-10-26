package actron.compose

import actron.core.Store
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoreOwnershipTest {
    @Test
    fun compositionHarness_appliesSnapshotChanges() =
        runTest(UnconfinedTestDispatcher()) {
            val value = mutableStateOf(1)
            val seen = mutableListOf<Int>()
            withComposition(content = { seen += value.value }, afterSetContent = { value.value = 2 })
            assertEquals(listOf(1, 2), seen)
        }

    @Test
    fun equalStores_replaceStateEventsDispatchAndCloseOwnership() =
        runTest(UnconfinedTestDispatcher()) {
            val first = EqualStore(1)
            val second = EqualStore(1)
            assertEquals(first, second)
            assertNotSame(first, second)
            second.backing.state.value = UiState.Ready(10)
            val current = mutableStateOf<Store<UiState, UiAction, UiEvent>>(first, referentialEqualityPolicy())
            val views = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()
            val handled = mutableListOf<Int>()
            withNodeComposition(TestNode(), content = {
                val view = rememberViewStore(current.value, autoClose = true)
                views += view
                view.eventEffect<UiEvent.ValueChanged> { handled += it.value }
            }, afterSetContent = { pumpFrame ->
                val original = views.last()
                assertEquals(1, first.backing.eventFlow.subscriptionCount.value)
                current.value = second
                repeat(2) { pumpFrame() }
                val replacement = views.last()
                replacement.dispatch(UiAction.Increment)
                assertEquals(emptyList(), first.backing.dispatchedActions)
                assertEquals(listOf<UiAction>(UiAction.Increment), second.backing.dispatchedActions)
                assertNotSame(original, replacement)
                assertEquals(UiState.Ready(10), replacement.state)
                assertEquals(1, first.backing.closeCount)
                assertEquals(0, second.backing.closeCount)
                assertEquals(0, first.backing.eventFlow.subscriptionCount.value)
                assertEquals(1, second.backing.eventFlow.subscriptionCount.value)
                first.backing.state.value = UiState.Ready(99)
                second.backing.state.value = UiState.Ready(12)
                second.backing.eventFlow.emit(UiEvent.ValueChanged(42))
                repeat(2) { pumpFrame() }
                assertEquals(UiState.Ready(12), replacement.state)
                assertEquals(listOf(42), handled)
            })
            assertEquals(1, first.backing.closeCount)
            assertEquals(1, second.backing.closeCount)
            assertEquals(0, second.backing.eventFlow.subscriptionCount.value)
        }

    @Test
    fun factoryKeyChange_adoptsEqualButDistinctStore() =
        runTest(UnconfinedTestDispatcher()) {
            val first = EqualStore(1)
            val second = EqualStore(1)
            val key = mutableStateOf(0)
            val views = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()
            withNodeComposition(TestNode(), content = {
                val generation = key.value
                views +=
                    rememberViewStore(key = generation, autoClose = true) {
                        if (generation == 0) first else second
                    }
            }, afterSetContent = { pumpFrame ->
                val original = views.last()
                key.value = 1
                repeat(2) { pumpFrame() }
                assertNotSame(original, views.last())
                views.last().dispatch(UiAction.Increment)
                assertEquals(emptyList(), first.backing.dispatchedActions)
                assertEquals(listOf<UiAction>(UiAction.Increment), second.backing.dispatchedActions)
                val replacement = views.last()
                second.backing.state.value = UiState.Ready(2)
                repeat(2) { pumpFrame() }
                assertSame(replacement, views.last())
            })
            assertEquals(1, first.backing.closeCount)
            assertEquals(1, second.backing.closeCount)
        }
}

// Value equality deliberately excludes the resource owned by this Store instance.
private data class EqualStore(
    val id: Int,
) : Store<UiState, UiAction, UiEvent> {
    val backing = TestStore(UiState.Ready(1))
    override val state get() = backing.state
    override val event get() = backing.event
    override val currentState get() = backing.currentState

    override fun start() = backing.start()

    override fun dispatch(action: UiAction) = backing.dispatch(action)

    override fun observeState(observer: (UiState) -> Unit): AutoCloseable = backing.observeState(observer = observer)

    override fun observeEvent(observer: (UiEvent) -> Unit): AutoCloseable = backing.observeEvent(observer = observer)

    override suspend fun closeAndJoin() = close()

    override fun close() = backing.close()
}
