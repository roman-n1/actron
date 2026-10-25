@file:OptIn(actron.core.InternalActronApi::class)

package actron.compose

import actron.core.ExperimentalActronApi
import actron.core.State
import actron.core.StateSaver
import actron.core.Store
import actron.core.createRuntimeStore
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withRunningRecomposer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ViewStoreJvmTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @OptIn(ExperimentalActronApi::class)
    @Test
    fun keyedTabsForTheSameChatKeepIndependentStoresAndSavers() =
        runTest(testDispatcher) {
            val tabs = mutableStateOf(listOf("left", "right"))
            val stores = mutableMapOf<String, TestStore>()
            val views = mutableMapOf<String, ViewStore<UiState, UiAction, UiEvent>>()
            val savers = mutableMapOf<String, StateSaver<UiState>>()
            val owner =
                object : ViewModelStoreOwner, LifecycleOwner {
                    override val viewModelStore = ViewModelStore()
                    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
                }
            Dispatchers.setMain(testDispatcher)
            try {
                withNodeComposition(TestNode(), content = {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalLifecycleOwner provides owner) {
                        for (tab in tabs.value) {
                            key(tab) {
                                savers[tab] = rememberStateSaver()
                                views[tab] =
                                    rememberViewStore(key = "same-chat", autoClose = true) {
                                        TestStore(UiState.Ready(0)).also { stores[tab] = it }
                                    }
                            }
                        }
                    }
                }, afterSetContent = { pumpFrame ->
                    val left = views.getValue("left")
                    val right = views.getValue("right")
                    val leftSaver = savers.getValue("left")
                    val rightSaver = savers.getValue("right")
                    assertNotSame(left, right)
                    assertNotSame(leftSaver, rightSaver)
                    leftSaver.save(UiState.Ready(10))
                    rightSaver.save(UiState.Ready(20))
                    stores.getValue("left").state.value = UiState.Ready(1)
                    stores.getValue("right").state.value = UiState.Ready(2)
                    tabs.value = listOf("right", "left")
                    repeat(2) { pumpFrame() }
                    assertSame(left, views.getValue("left"))
                    assertSame(right, views.getValue("right"))
                    assertEquals(UiState.Ready(1), left.state)
                    assertEquals(UiState.Ready(2), right.state)
                    assertSame(leftSaver, savers.getValue("left"))
                    assertSame(rightSaver, savers.getValue("right"))
                    assertEquals(UiState.Ready(10), leftSaver.restore(UiState.Ready(0)))
                    assertEquals(UiState.Ready(20), rightSaver.restore(UiState.Ready(0)))
                    tabs.value = listOf("right")
                    repeat(2) { pumpFrame() }
                    assertEquals(1, stores.getValue("left").closeCount)
                    assertEquals(0, stores.getValue("right").closeCount)
                    right.dispatch(UiAction.Increment)
                    assertEquals(emptyList(), stores.getValue("left").dispatchedActions)
                    assertEquals(listOf<UiAction>(UiAction.Increment), stores.getValue("right").dispatchedActions)
                })
                assertEquals(1, stores.getValue("right").closeCount)
            } finally {
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                owner.viewModelStore.clear()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rememberViewStore_providesCurrentStateAndDispatchesAction() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val viewStoreObservations = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()

            withComposition(
                content = {
                    viewStoreObservations.add(rememberViewStore { store })
                },
                afterSetContent = {
                    assertEquals(UiState.Ready(1), viewStoreObservations.last().state)

                    viewStoreObservations.last().dispatch(UiAction.Increment)
                    assertEquals(listOf<UiAction>(UiAction.Increment), store.dispatchedActions)
                },
            )
        }

    @Test
    fun rememberViewStore_withStoreInstanceProvidesCurrentStateAndDispatchesAction() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val viewStoreObservations = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()

            withComposition(
                content = {
                    viewStoreObservations.add(rememberViewStore(store))
                },
                afterSetContent = {
                    assertEquals(UiState.Ready(1), viewStoreObservations.last().state)

                    viewStoreObservations.last().dispatch(UiAction.Increment)
                    assertEquals(listOf<UiAction>(UiAction.Increment), store.dispatchedActions)
                },
            )
        }

    @Test
    fun rememberViewStore_keepsSameInstanceWhileStateUpdates() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val viewStoreObservations = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()

            withComposition(
                content = {
                    viewStoreObservations.add(rememberViewStore { store })
                },
                afterSetContent = {
                    val initialViewStore = viewStoreObservations.last()
                    store.state.value = UiState.Ready(2)
                    testScheduler.runCurrent()

                    assertSame(initialViewStore, viewStoreObservations.last())
                    assertEquals(UiState.Ready(2), viewStoreObservations.last().state)
                },
            )
        }

    @Test
    fun rememberViewStore_invokesStoreLambdaOnlyOnceForSameKey() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val recompositionTrigger = mutableStateOf(0)
            var storeCalls = 0
            var latestTriggerValue = -1
            val initialViewStoreObservations = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()
            val latestViewStoreObservations = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()

            withComposition(
                content = {
                    latestTriggerValue = recompositionTrigger.value
                    latestViewStoreObservations.add(
                        rememberViewStore(key = "same") {
                            storeCalls++
                            store
                        },
                    )
                },
                afterSetContent = {
                    initialViewStoreObservations.add(latestViewStoreObservations.last())
                    assertEquals(1, storeCalls)
                    assertEquals(0, latestTriggerValue)

                    recompositionTrigger.value = 1
                    Snapshot.sendApplyNotifications()
                },
            )

            assertEquals(1, latestTriggerValue)
            assertEquals(1, storeCalls)
            assertSame(initialViewStoreObservations.last(), latestViewStoreObservations.last())
        }

    @Test
    fun rememberViewStore_recreatesWhenKeyChangesEvenIfStateIsEqual() =
        runTest(testDispatcher) {
            val firstStore = TestStore(UiState.Loading)
            val secondStore = TestStore(UiState.Loading)
            val frameClock = BroadcastFrameClock()
            var frameTimeNanos = 0L
            var key = "first"
            val latestViewStoreObservations = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()

            suspend fun pumpFrame() {
                testScheduler.runCurrent()
                frameTimeNanos += 16_000_000L
                frameClock.sendFrame(frameTimeNanos)
                testScheduler.runCurrent()
            }

            withContext(frameClock) {
                withRunningRecomposer { recomposer ->
                    val composition = Composition(NoOpApplier(), recomposer)
                    try {
                        composition.setContent {
                            latestViewStoreObservations.add(
                                rememberViewStore(key = key) {
                                    if (key == "first") firstStore else secondStore
                                },
                            )
                        }
                        repeat(2) { pumpFrame() }
                        val initialViewStore = latestViewStoreObservations.last()

                        key = "second"
                        composition.setContent {
                            latestViewStoreObservations.add(
                                rememberViewStore(key = key) {
                                    if (key == "first") firstStore else secondStore
                                },
                            )
                        }
                        repeat(2) { pumpFrame() }

                        assertNotSame(initialViewStore, latestViewStoreObservations.last())

                        latestViewStoreObservations.last().dispatch(UiAction.Increment)

                        assertTrue(firstStore.dispatchedActions.isEmpty())
                        assertEquals(listOf<UiAction>(UiAction.Increment), secondStore.dispatchedActions)
                    } finally {
                        composition.dispose()
                        pumpFrame()
                    }
                }
            }
        }

    @Test
    fun rememberViewStore_keyChangeUsesNewStoreStateOnFirstComposition() =
        runTest(testDispatcher) {
            val firstStore = TestStore(UiState.Ready(1))
            val secondStore = TestStore(UiState.Ready(2))
            val renderedStates = mutableListOf<UiState>()
            val frameClock = BroadcastFrameClock()
            var frameTimeNanos = 0L
            var key = "first"

            suspend fun pumpFrame() {
                testScheduler.runCurrent()
                frameTimeNanos += 16_000_000L
                frameClock.sendFrame(frameTimeNanos)
                testScheduler.runCurrent()
            }

            withContext(frameClock) {
                withRunningRecomposer { recomposer ->
                    val composition = Composition(NoOpApplier(), recomposer)
                    try {
                        composition.setContent {
                            renderedStates +=
                                rememberViewStore(key = key) {
                                    if (key == "first") firstStore else secondStore
                                }.state
                        }
                        repeat(2) { pumpFrame() }

                        renderedStates.clear()
                        key = "second"
                        composition.setContent {
                            renderedStates +=
                                rememberViewStore(key = key) {
                                    if (key == "first") firstStore else secondStore
                                }.state
                        }
                        repeat(2) { pumpFrame() }

                        assertEquals(UiState.Ready(2), renderedStates.first())
                    } finally {
                        composition.dispose()
                        pumpFrame()
                    }
                }
            }
        }

    @Test
    fun rememberViewStore_capturedCallbackReadsLatestState() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Loading)
            val canHideObservations = mutableListOf<() -> Boolean>()

            withComposition(
                content = {
                    val viewStore = rememberViewStore { store }
                    canHideObservations.add(
                        remember {
                            {
                                viewStore.state !is UiState.Busy
                            }
                        },
                    )
                },
                afterSetContent = {
                    assertTrue(canHideObservations.last()())
                    store.state.value = UiState.Busy
                },
            )

            assertFalse(canHideObservations.last()())
        }

    @Test
    fun childThatDoesNotReadViewStoreState_doesNotUpdateRenderedNodeOnStateUpdate() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val root = TestNode()
            var childCompositions = 0

            @Suppress("UNUSED_PARAMETER")
            @Composable
            fun Child(viewStore: ViewStore<UiState, UiAction, UiEvent>) {
                childCompositions++
                StateNode(UiState.Ready(100))
            }

            withNodeComposition(
                root = root,
                content = {
                    val viewStore = rememberViewStore { store }
                    Child(viewStore)
                },
                afterSetContent = { pumpFrame ->
                    assertEquals(UiState.Ready(100), root.singleChild.state)
                    assertEquals(1, childCompositions)

                    store.state.value = UiState.Ready(2)
                    testScheduler.runCurrent()
                    pumpFrame()

                    assertEquals(UiState.Ready(100), root.singleChild.state)
                    assertEquals(1, childCompositions)
                },
            )
        }

    @Test
    fun childThatReadsViewStoreState_doesNotRecomposeWhenStateIsEqual() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val root = TestNode()
            var childCompositions = 0

            @Composable
            fun Child(viewStore: ViewStore<UiState, UiAction, UiEvent>) {
                childCompositions++
                StateNode(viewStore.state)
            }

            withNodeComposition(
                root = root,
                content = {
                    val viewStore = rememberViewStore { store }
                    Child(viewStore)
                },
                afterSetContent = { pumpFrame ->
                    assertEquals(UiState.Ready(1), root.singleChild.state)
                    assertEquals(1, childCompositions)

                    store.state.value = UiState.Ready(1)
                    testScheduler.runCurrent()
                    pumpFrame()

                    assertEquals(UiState.Ready(1), root.singleChild.state)
                    assertEquals(1, childCompositions)
                },
            )
        }

    @Test
    fun childThatReadsViewStoreState_updatesRenderedNodeOnStateUpdate() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(1))
            val root = TestNode()
            var childCompositions = 0

            @Composable
            fun Child(viewStore: ViewStore<UiState, UiAction, UiEvent>) {
                childCompositions++
                StateNode(viewStore.state)
            }

            withNodeComposition(
                root = root,
                content = {
                    val viewStore = rememberViewStore { store }
                    Child(viewStore)
                },
                afterSetContent = { pumpFrame ->
                    assertEquals(UiState.Ready(1), root.singleChild.state)
                    assertEquals(1, childCompositions)

                    store.state.value = UiState.Ready(2)
                    testScheduler.runCurrent()
                    pumpFrame()

                    assertEquals(UiState.Ready(2), root.singleChild.state)
                    assertEquals(2, childCompositions)
                },
            )
        }

    @Test
    fun rememberViewStore_closeBehaviorIsFixedPerRememberedStore() =
        runTest(testDispatcher) {
            val neverCloseStore = TestStore(UiState.Ready(0))
            var autoClose = mutableStateOf(false)

            withComposition(
                content = {
                    rememberViewStore(autoClose = autoClose.value) { neverCloseStore }
                },
                afterSetContent = {
                    autoClose.value = true
                },
            )

            assertEquals(0, neverCloseStore.closeCount)

            val alwaysCloseStore = TestStore(UiState.Ready(0))
            autoClose.value = true
            withComposition(
                content = {
                    rememberViewStore(autoClose = autoClose.value) { alwaysCloseStore }
                },
                afterSetContent = {
                    autoClose.value = false
                },
            )

            assertEquals(1, alwaysCloseStore.closeCount)
        }

    @Test
    fun rememberViewStore_keyChangeReturningSameStoreKeepsItsViewAndCloseOwnership() =
        runTest(testDispatcher) {
            val store = TestStore(UiState.Ready(0))
            val storeKey = mutableStateOf(0)
            val autoClose = mutableStateOf(false)
            val createdForKeys = mutableListOf<Int>()
            val views = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()

            withNodeComposition(
                TestNode(),
                content = {
                    views +=
                        rememberViewStore(key = storeKey.value, autoClose = autoClose.value) {
                            createdForKeys += storeKey.value
                            store
                        }
                },
                afterSetContent = { pumpFrame ->
                    storeKey.value = 1
                    autoClose.value = true
                    repeat(2) { pumpFrame() }
                },
            )

            assertEquals(listOf(0, 1), createdForKeys)
            assertTrue(views.size >= 2)
            views.forEach { assertSame(views.first(), it) }
            assertEquals(0, store.closeCount, "the same Store keeps its original close ownership")
        }

    @Test
    fun rememberViewStore_keyChangeAppliesAutoCloseToNewRememberedStore() =
        runTest(testDispatcher) {
            val firstStore = TestStore(UiState.Ready(0))
            val secondStore = TestStore(UiState.Ready(0))
            val frameClock = BroadcastFrameClock()
            var frameTimeNanos = 0L
            var key = "first"
            var autoClose = false

            suspend fun pumpFrame() {
                testScheduler.runCurrent()
                frameTimeNanos += 16_000_000L
                frameClock.sendFrame(frameTimeNanos)
                testScheduler.runCurrent()
            }

            withContext(frameClock) {
                withRunningRecomposer { recomposer ->
                    val composition = Composition(NoOpApplier(), recomposer)
                    try {
                        composition.setContent {
                            rememberViewStore(key = key, autoClose = autoClose) {
                                if (key == "first") firstStore else secondStore
                            }
                        }
                        repeat(2) { pumpFrame() }

                        autoClose = true
                        key = "second"
                        composition.setContent {
                            rememberViewStore(key = key, autoClose = autoClose) {
                                if (key == "first") firstStore else secondStore
                            }
                        }
                        repeat(2) { pumpFrame() }

                        assertEquals(0, firstStore.closeCount)
                        assertEquals(0, secondStore.closeCount)
                    } finally {
                        composition.dispose()
                        pumpFrame()
                    }
                }
            }

            assertEquals(0, firstStore.closeCount)
            assertEquals(1, secondStore.closeCount)
        }

    @Test
    fun rememberViewStore_withRealStore_reflectsStateTransitionAndDispatch() =
        runTest(testDispatcher) {
            val store =
                createRuntimeStore<UiState, UiAction, UiEvent>(initialState = UiState.Loading) {
                    coroutineContext(coroutineContext)
                    state<UiState.Loading> {
                        enter {
                            nextState(UiState.Ready(0))
                        }
                    }
                    state<UiState.Ready> {
                        action<UiAction.Increment> {
                            nextState(state.copy(value = state.value + 1))
                        }
                    }
                }

            val viewStoreObservations = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()
            withComposition(
                content = {
                    viewStoreObservations.add(rememberViewStore { store })
                },
                afterSetContent = {
                    assertEquals(UiState.Ready(0), viewStoreObservations.last().state)
                    viewStoreObservations.last().dispatch(UiAction.Increment)
                },
            )

            assertEquals(UiState.Ready(1), store.currentState)
            store.close()
        }

    @Test
    fun rememberViewStore_withRealStore_autoCloseStopsFurtherDispatch() =
        runTest(testDispatcher) {
            val store =
                createRuntimeStore<UiState, UiAction, UiEvent>(initialState = UiState.Ready(0)) {
                    coroutineContext(coroutineContext)
                    state<UiState.Ready> {
                        action<UiAction.Increment> {
                            nextState(state.copy(value = state.value + 1))
                        }
                    }
                }

            withComposition(
                content = {
                    rememberViewStore(autoClose = true) { store }
                },
                afterSetContent = {
                    store.dispatch(UiAction.Increment)
                },
            )

            assertEquals(UiState.Ready(1), store.currentState)

            store.dispatch(UiAction.Increment)
            testScheduler.runCurrent()

            assertEquals(UiState.Ready(1), store.currentState)
        }

    @Test
    fun rememberViewStore_usesStateFlowValueForInitialRenderingBeforeFirstCollectEmission() =
        runTest(testDispatcher) {
            val enterGate = CompletableDeferred<Unit>()
            val store =
                createRuntimeStore<UiState, UiAction, UiEvent>(initialState = UiState.Loading) {
                    coroutineContext(coroutineContext)
                    state<UiState.Loading> {
                        enter(dispatcher = testDispatcher) {
                            enterGate.await()
                            nextState(UiState.Ready(0))
                        }
                    }
                }

            val viewStoreObservations = mutableListOf<ViewStore<UiState, UiAction, UiEvent>>()

            withComposition(
                content = {
                    viewStoreObservations.add(rememberViewStore(autoClose = true) { store })
                },
                afterSetContent = {
                    assertEquals(UiState.Loading, viewStoreObservations.last().state)
                    assertEquals(UiState.Loading, store.currentState)
                    assertTrue(enterGate.complete(Unit))
                    testScheduler.runCurrent()
                    assertEquals(UiState.Ready(0), store.currentState)
                },
            )
        }
}

private class NoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(
        index: Int,
        instance: Unit,
    ) = Unit

    override fun insertBottomUp(
        index: Int,
        instance: Unit,
    ) = Unit

    override fun move(
        from: Int,
        to: Int,
        count: Int,
    ) = Unit

    override fun remove(
        index: Int,
        count: Int,
    ) = Unit

    override fun onClear() = Unit
}
