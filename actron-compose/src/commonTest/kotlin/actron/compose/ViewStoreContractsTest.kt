package actron.compose

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withRunningRecomposer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ViewStoreContractsTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun select_derivesFromTheState_andItsReadersRecomposeOnlyWhenTheDerivedValueChanges() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val buckets = mutableListOf<Int>()
            val parents = mutableListOf<Int>()
            withNodeComposition(TestNode(), content = {
                val viewStore = rememberViewStore(store)
                parents += 1
                BucketReader(viewStore, buckets)
            }, afterSetContent = { pumpFrame ->
                store.state.value = UiState.Ready(2)
                repeat(2) { pumpFrame() }
                store.state.value = UiState.Ready(12)
                repeat(2) { pumpFrame() }
                store.state.value = UiState.Ready(15)
                repeat(2) { pumpFrame() }
            })

            assertEquals(listOf(0, 1), buckets, "the reader recomposed once for 1 -> 12 (a new bucket) and not for 1 -> 2 or 12 -> 15 (the same bucket)")
            assertEquals(1, parents.size, "the parent did not read the state, so it never recomposed")
        }

    @Test
    fun select_usesTheLatestMapper() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(3))
            val factor = mutableStateOf(1)
            val seen = mutableListOf<Int>()
            withNodeComposition(TestNode(), content = {
                val viewStore = rememberViewStore(store)
                val multiplier = factor.value
                seen += viewStore.select { (it as UiState.Ready).value * multiplier }
            }, afterSetContent = { pumpFrame ->
                factor.value = 10
                repeat(2) { pumpFrame() }
            })

            assertEquals(listOf(3, 30), seen)
        }

    @Test
    fun select_followsTheReplacementStoreAndIgnoresThePreviousSource() =
        runTest(testDispatcher) {
            val first = TestStore(UiState.Ready(1))
            val second = TestStore(UiState.Ready(10))
            val current = mutableStateOf(first)
            val selected = mutableListOf<Int>()
            withNodeComposition(TestNode(), content = {
                selected += rememberViewStore(current.value).select { (it as UiState.Ready).value }
            }, afterSetContent = { pumpFrame ->
                assertEquals(1, selected.last())
                current.value = second
                repeat(2) { pumpFrame() }
                assertEquals(10, selected.last())
                val afterReplacement = selected.toList()
                first.state.value = UiState.Ready(99)
                repeat(2) { pumpFrame() }
                assertEquals(afterReplacement, selected)
                second.state.value = UiState.Ready(12)
                repeat(2) { pumpFrame() }
                assertEquals(12, selected.last())
            })
        }

    @Test
    fun stateContent_keepsItsViewWhileMatchingAndRecreatesItAfterLeaving() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val views = mutableListOf<ViewStore<UiState.Ready, UiAction, UiEvent>>()
            withNodeComposition(TestNode(), content = {
                rememberViewStore(store).stateContent<UiState.Ready> { views += this }
            }, afterSetContent = { pumpFrame ->
                val first = views.first()
                store.state.value = UiState.Ready(2)
                repeat(2) { pumpFrame() }
                assertSame(first, views.last())
                assertEquals(2, first.state.value)
                val beforeLeaving = views.size
                store.state.value = UiState.Loading
                repeat(2) { pumpFrame() }
                assertEquals(beforeLeaving, views.size)
                assertEquals(2, first.state.value, "a stale callback can still read the last matching state")
                store.state.value = UiState.Ready(3)
                repeat(2) { pumpFrame() }
                assertNotSame(first, views.last())
                assertEquals(3, views.last().state.value)
            })
        }

    @Test
    fun eventEffect_updatesReceiverAndHandlerWithoutRestartingTheSameFlow() =
        runTest(testDispatcher) {
            val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
            var starts = 0
            var completions = 0
            val observed =
                flow<UiEvent> {
                    starts++
                    try {
                        emitAll(events)
                    } finally {
                        completions++
                    }
                }
            val current = mutableStateOf(ViewStore<UiState, UiAction, UiEvent>(UiState.Ready(1), eventFlow = observed))
            val label = mutableStateOf("first")
            val handled = mutableListOf<String>()
            withNodeComposition(TestNode(), content = {
                val handlerLabel = label.value
                current.value.eventEffect<UiEvent.ValueChanged> {
                    handled += "${(state as UiState.Ready).value}:$handlerLabel:${it.value}"
                }
            }, afterSetContent = { pumpFrame ->
                assertEquals(1, starts)
                assertEquals(1, events.subscriptionCount.value)
                // Apply synchronously: Android's global snapshot manager can otherwise take
                // these notifications on its own thread, outside the test scheduler.
                Snapshot.withMutableSnapshot {
                    current.value = ViewStore(UiState.Ready(2), eventFlow = observed)
                    label.value = "updated"
                }
                repeat(2) { pumpFrame() }
                assertEquals(1, starts)
                assertEquals(0, completions)
                assertTrue(events.tryEmit(UiEvent.Reset))
                assertTrue(events.tryEmit(UiEvent.ValueChanged(42)))
                repeat(2) { pumpFrame() }
                assertEquals(listOf("2:updated:42"), handled)
            })
            assertEquals(1, completions)
            assertEquals(0, events.subscriptionCount.value)
        }

    @Test
    fun eventEffect_replacesItsFlowAndCancelsCollectionOnDispose() =
        runTest(testDispatcher) {
            val first = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
            val second = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
            val current = mutableStateOf(ViewStore<UiState, UiAction, UiEvent>(UiState.Ready(0), eventFlow = first))
            val handled = mutableListOf<Int>()
            withNodeComposition(TestNode(), content = {
                current.value.eventEffect<UiEvent.ValueChanged> { handled += it.value }
            }, afterSetContent = { pumpFrame ->
                assertEquals(1, first.subscriptionCount.value)
                current.value = ViewStore(UiState.Ready(0), eventFlow = second)
                repeat(2) { pumpFrame() }
                assertEquals(0, first.subscriptionCount.value)
                assertEquals(1, second.subscriptionCount.value)
                assertTrue(first.tryEmit(UiEvent.ValueChanged(1)))
                assertTrue(second.tryEmit(UiEvent.ValueChanged(2)))
                repeat(2) { pumpFrame() }
                assertEquals(listOf(2), handled)
            })
            assertEquals(0, second.subscriptionCount.value)
            assertTrue(second.tryEmit(UiEvent.ValueChanged(3)))
            testScheduler.runCurrent()
            assertEquals(listOf(2), handled)
        }

    @Test
    fun stateContent_callsBlockOnlyForMatchingState() =
        runTest(testDispatcher) {
            val renderedValues = mutableListOf<Int>()

            withComposition(
                content = {
                    ViewStore<UiState, Nothing, Nothing>(state = UiState.Ready(10))
                        .stateContent<UiState.Ready> {
                            renderedValues += state.value
                        }

                    ViewStore<UiState, Nothing, Nothing>(state = UiState.Loading)
                        .stateContent<UiState.Ready> {
                            renderedValues += state.value
                        }
                },
            )

            assertEquals(listOf(10), renderedValues)
        }

    @Test
    fun eventEffect_collectsOnlySpecifiedEventType() =
        runTest(testDispatcher) {
            val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
            val handled = mutableListOf<UiEvent.ValueChanged>()

            withComposition(
                content = {
                    ViewStore<UiState, UiAction, UiEvent>(
                        state = UiState.Ready(0),
                        eventFlow = events,
                    ).eventEffect<UiEvent.ValueChanged> { event ->
                        handled += event
                    }
                },
                afterSetContent = {
                    assertTrue(events.tryEmit(UiEvent.Reset))
                    assertTrue(events.tryEmit(UiEvent.ValueChanged(42)))
                },
            )

            assertEquals(listOf(UiEvent.ValueChanged(42)), handled)
        }

    @Test
    fun eventEffect_usesLatestViewStoreAndLambdaAfterRecomposition() =
        runTest(testDispatcher) {
            val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
            val handled = mutableListOf<String>()
            var label = "initial"
            var viewState: UiState = UiState.Ready(1)
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
                    val composition = Composition(TestApplier(TestNode()), recomposer)
                    try {
                        composition.setContent {
                            ViewStore<UiState, UiAction, UiEvent>(
                                state = viewState,
                                eventFlow = events,
                            ).eventEffect<UiEvent.ValueChanged> { event ->
                                handled += "${(state as UiState.Ready).value}:$label:${event.value}"
                            }
                        }
                        repeat(2) { pumpFrame() }

                        label = "updated"
                        viewState = UiState.Ready(2)
                        composition.setContent {
                            ViewStore<UiState, UiAction, UiEvent>(
                                state = viewState,
                                eventFlow = events,
                            ).eventEffect<UiEvent.ValueChanged> { event ->
                                handled += "${(state as UiState.Ready).value}:$label:${event.value}"
                            }
                        }
                        repeat(2) { pumpFrame() }

                        assertTrue(events.tryEmit(UiEvent.ValueChanged(42)))
                        repeat(2) { pumpFrame() }
                    } finally {
                        composition.dispose()
                        pumpFrame()
                    }
                }
            }

            assertEquals(listOf("2:updated:42"), handled)
        }

    @Suppress("DEPRECATION")
    @Test
    fun render_delegatesToStateContent() =
        runTest(testDispatcher) {
            val renderedValues = mutableListOf<Int>()

            withComposition(
                content = {
                    ViewStore<UiState, Nothing, Nothing>(state = UiState.Ready(7))
                        .stateContent<UiState.Ready> {
                            renderedValues += state.value
                        }
                },
            )

            assertEquals(listOf(7), renderedValues)
        }

    @Suppress("DEPRECATION")
    @Test
    fun handle_delegatesToEventEffect() =
        runTest(testDispatcher) {
            val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
            val handled = mutableListOf<UiEvent.ValueChanged>()

            withComposition(
                content = {
                    ViewStore<UiState, UiAction, UiEvent>(
                        state = UiState.Ready(0),
                        eventFlow = events,
                    ).eventEffect<UiEvent.ValueChanged> { event ->
                        handled += event
                    }
                },
                afterSetContent = {
                    assertTrue(events.tryEmit(UiEvent.ValueChanged(100)))
                },
            )

            assertEquals(listOf(UiEvent.ValueChanged(100)), handled)
        }

    @Test
    fun stateContent_callbackRunAfterStateTypeChanged_readsLastNarrowedState() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val readValueObservations = mutableListOf<() -> Int>()

            withComposition(
                content = {
                    rememberViewStore { store }.stateContent<UiState.Ready> {
                        readValueObservations.add({ state.value })
                    }
                },
                afterSetContent = {
                    assertEquals(1, readValueObservations.last()())

                    store.state.value = UiState.Ready(5)
                    testScheduler.runCurrent()
                    assertEquals(5, readValueObservations.last()())

                    // A click handler of the Ready content that runs before recomposition removes it.
                    store.state.value = UiState.Loading
                    testScheduler.runCurrent()
                    assertEquals(5, readValueObservations.last()())
                },
            )
        }

    @Test
    fun stateContent_callbackThatNeverReadStateDuringComposition_readsLastNarrowedState() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val readValueObservations = mutableListOf<() -> Int>()

            withComposition(
                content = {
                    rememberViewStore { store }.stateContent<UiState.Ready> {
                        // Reads state only inside the callback, as a click handler does.
                        readValueObservations.add({ state.value })
                    }
                },
                afterSetContent = {
                    // A click handler of the Ready content that runs before recomposition removes it.
                    store.state.value = UiState.Loading
                    testScheduler.runCurrent()
                    assertEquals(1, readValueObservations.last()())
                },
            )
        }
}
