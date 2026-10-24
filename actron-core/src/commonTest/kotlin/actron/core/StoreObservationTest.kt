@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoreObservationTest {
    private data class Counter(
        val value: Int = 0,
    ) : State

    private data object Increment : Action

    private data object Changed : Event

    @Test
    fun cancellingOneObserverLeavesOtherObserversAndTheStoreRunning() =
        runTest {
            val store =
                Store<Counter, Increment, Changed>(Counter(), StandardTestDispatcher(testScheduler)) {
                    state<Counter> {
                        action<Increment> {
                            event(Changed)
                            nextState { Counter(state.value + 1) }
                        }
                    }
                }
            val states = mutableListOf<Counter>()
            val events = mutableListOf<Changed>()
            val peerStates = mutableListOf<Counter>()
            try {
                val stateHandle = store.observeState { states += it }
                val eventHandle = store.observeEvent { events += it }
                store.observeState { peerStates += it }
                runCurrent()
                store.dispatchAndAwaitForTest(Increment)
                runCurrent()
                stateHandle.close()
                stateHandle.close()
                eventHandle.close()
                eventHandle.close()
                runCurrent()
                store.dispatchAndAwaitForTest(Increment)
                runCurrent()
                assertEquals(listOf(Counter(), Counter(1)), states)
                assertEquals(listOf(Changed), events)
                assertEquals(listOf(Counter(), Counter(1), Counter(2)), peerStates)
            } finally {
                store.close()
            }
        }

    @Test
    fun cancellationBeforeSchedulingPreventsCallbacksAndStateStartup() =
        runTest {
            var entered = false
            val observed = mutableListOf<Counter>()
            val store =
                Store<Counter, Nothing, Nothing>(Counter(), StandardTestDispatcher(testScheduler)) {
                    state<Counter> { enter { entered = true } }
                }
            try {
                store.observeState { observed += it }.close()
                runCurrent()
                assertTrue(observed.isEmpty())
                assertTrue(!entered)
                store.close()
                store.observeState { observed += it }.close()
                runCurrent()
                assertTrue(observed.isEmpty())
            } finally {
                store.close()
            }
        }

    @Test
    fun eventObservationDoesNotStartStoreAndStateObservationHonoursExplicitPolicy() =
        runTest {
            var entered = false
            val values = mutableListOf<Counter>()
            val store =
                Store<Counter, Nothing, Changed>(Counter(), StandardTestDispatcher(testScheduler)) {
                    autoStartPolicy(AutoStartPolicy.OnDispatch)
                    state<Counter> {
                        enter {
                            entered = true
                            event(Changed)
                        }
                    }
                }
            val events = mutableListOf<Changed>()
            try {
                store.observeEvent { events += it }
                store.observeState { values += it }
                runCurrent()
                assertTrue(!entered)
                assertEquals(listOf(Counter()), values)
                store.startAndAwaitForTest()
                assertEquals(listOf(Changed), events)
            } finally {
                store.close()
            }
        }

    @Test
    fun callbackRunsOnStoreDispatcherAndCanCancelItsOwnObservation() =
        runTest {
            val storeScheduler = TestCoroutineScheduler()
            val storeDispatcher = StandardTestDispatcher(storeScheduler, "store")
            val handles = mutableListOf<AutoCloseable>()
            val store = Store<Counter, Increment, Nothing>(Counter(), storeDispatcher) {}
            var calls = 0
            try {
                handles +=
                    store.observeState {
                        calls++
                        handles.single().close()
                    }
                runCurrent()
                assertEquals(0, calls)
                storeScheduler.runCurrent()
                assertEquals(1, calls)
            } finally {
                store.close()
                storeScheduler.runCurrent()
            }
        }

    @Test
    fun failedCallbackIsReportedWithoutCancellingPeerObserversOrTheStore() =
        runTest {
            val stateFailure = IllegalStateException("state observer")
            val eventFailure = IllegalStateException("event observer")
            val reported = mutableListOf<Throwable>()
            val states = mutableListOf<Counter>()
            val events = mutableListOf<Changed>()
            val store =
                Store<Counter, Increment, Changed>(Counter(), StandardTestDispatcher(testScheduler)) {
                    exceptionHandler(ExceptionHandler { reported += it })
                    state<Counter> {
                        action<Increment> {
                            event(Changed)
                            nextState { Counter(state.value + 1) }
                        }
                    }
                }
            try {
                store.observeState { throw stateFailure }
                store.observeState { states += it }
                store.observeEvent { throw eventFailure }
                store.observeEvent { events += it }
                runCurrent()
                repeat(2) {
                    store.dispatchAndAwaitForTest(Increment)
                    runCurrent()
                }
                assertEquals(listOf<Throwable>(stateFailure, eventFailure), reported)
                assertEquals(listOf(Counter(), Counter(1), Counter(2)), states)
                assertEquals(listOf(Changed, Changed), events)
            } finally {
                store.close()
            }
        }

    @Test
    fun eventObserverIsReadyBeforeReturningEvenWhenAnEmitterIsAlreadyQueued() =
        runTest {
            val emit = CompletableDeferred<Unit>()
            val received = mutableListOf<Changed>()
            val store =
                Store<Counter, Nothing, Changed>(Counter(), StandardTestDispatcher(testScheduler)) {
                    state<Counter> {
                        enter {
                            launch {
                                emit.await()
                                event(Changed)
                            }
                        }
                    }
                }
            try {
                store.startAndAwaitForTest()
                emit.complete(Unit)
                store.observeEvent { received += it }
                runCurrent()
                assertEquals(listOf(Changed), received)
            } finally {
                store.close()
            }
        }

    @Test
    fun oldCustomImplementationsRemainValidAndUnsupportedCapabilityFailsBeforeClose() =
        runTest {
            val store = LegacyStore()
            assertFailsWith<IllegalStateException> { store.observeState {} }
            assertFailsWith<IllegalStateException> { store.observeEvent {} }
            assertFailsWith<IllegalStateException> { store.closeAndJoin() }
            assertEquals(0, store.closes)
        }

    private class LegacyStore : Store<Counter, Increment, Changed> {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(Counter())
        override val event = kotlinx.coroutines.flow.MutableSharedFlow<Changed>()
        override val currentState: Counter get() = state.value
        var closes = 0

        override fun start() = Unit

        override fun dispatch(action: Increment) = Unit

        override fun collectState(state: (Counter) -> Unit) = Unit

        override fun collectEvent(event: (Changed) -> Unit) = Unit

        override fun close() {
            closes++
        }
    }
}
