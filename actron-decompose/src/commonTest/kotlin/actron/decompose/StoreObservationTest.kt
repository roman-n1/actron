@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.decompose

import actron.core.Action
import actron.core.AutoStartPolicy
import actron.core.Event
import actron.core.State
import actron.core.Store
import actron.core.closeAndJoin
import actron.test.patch
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.instancekeeper.getOrCreateCloseable
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.destroy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StoreObservationTest {
    private data class Counter(
        val value: Int = 0,
    ) : State

    private data object Increment : Action

    private data object Changed : Event

    @Test
    fun eventObserverIsReadyBeforeStartupAndDoesNotStartTheStore() =
        runTest {
            val lifecycle = TrackingLifecycle().apply { create() }
            var entered = false
            val values = mutableListOf<Changed>()
            val store =
                Store<Counter, Nothing, Changed>(Counter(), StandardTestDispatcher(testScheduler)) {
                    state<Counter> {
                        enter {
                            entered = true
                            event(Changed)
                        }
                    }
                }
            try {
                store.observeEvent(lifecycle) { values += it }
                runCurrent()
                assertTrue(!entered)
                assertTrue(values.isEmpty())
                assertEquals(1, lifecycle.callbacks.size)
                store.start()
                runCurrent()
                assertTrue(entered)
                assertEquals(listOf(Changed), values)
                lifecycle.destroy()
                assertTrue(lifecycle.callbacks.isEmpty())
            } finally {
                lifecycle.destroy()
                store.closeAndJoin()
            }
        }

    @Test
    fun closingOneEventHandleKeepsSiblingEventAndStateObserversAlive() =
        runTest {
            val lifecycle = TrackingLifecycle().apply { create() }
            val store =
                Store<Counter, Increment, Changed>(Counter(), StandardTestDispatcher(testScheduler)) {
                    autoStartPolicy(AutoStartPolicy.OnDispatch)
                    state<Counter> {
                        action<Increment> {
                            event(Changed)
                            nextState { Counter(state.value + 1) }
                        }
                    }
                }
            val states = mutableListOf<Counter>()
            val closedEvents = mutableListOf<Changed>()
            val liveEvents = mutableListOf<Changed>()
            try {
                val stateHandle = store.observeState(lifecycle) { states += it }
                val closed = store.observeEvent(lifecycle) { closedEvents += it }
                val live = store.observeEvent(lifecycle) { liveEvents += it }
                assertEquals(3, lifecycle.callbacks.size)
                closed.close()
                closed.close()
                assertEquals(2, lifecycle.callbacks.size)
                runCurrent()
                store.dispatch(Increment)
                runCurrent()
                assertEquals(listOf(Counter(), Counter(1)), states)
                assertTrue(closedEvents.isEmpty())
                assertEquals(listOf(Changed), liveEvents)
                lifecycle.destroy()
                assertTrue(lifecycle.callbacks.isEmpty())
                live.close()
                stateHandle.close()
                store.dispatch(Increment)
                runCurrent()
                assertEquals(Counter(2), store.currentState)
                assertEquals(listOf(Counter(), Counter(1)), states)
                assertEquals(listOf(Changed), liveEvents)
            } finally {
                lifecycle.destroy()
                store.closeAndJoin()
            }
        }

    @Test
    fun componentRecreationReusesStoreButDropsOldObservers() =
        runTest {
            val keeper = InstanceKeeperDispatcher()
            val dispatcher = StandardTestDispatcher(testScheduler)
            var created = 0

            fun retained(context: ComponentContext) =
                context.instanceKeeper.getOrCreateCloseable("counter") {
                    created++
                    Store<Counter, Increment, Changed>(Counter(), dispatcher) {
                        autoStartPolicy(AutoStartPolicy.OnDispatch)
                        state<Counter> {
                            action<Increment> {
                                event(Changed)
                                nextState { Counter(state.value + 1) }
                            }
                        }
                    }
                }
            val firstLifecycle = LifecycleRegistry().apply { create() }
            val secondLifecycle = LifecycleRegistry().apply { create() }
            val oldStates = mutableListOf<Counter>()
            val newStates = mutableListOf<Counter>()
            val oldEvents = mutableListOf<Changed>()
            val newEvents = mutableListOf<Changed>()
            val firstContext = DefaultComponentContext(firstLifecycle, instanceKeeper = keeper)
            val secondContext = DefaultComponentContext(secondLifecycle, instanceKeeper = keeper)
            val store = retained(firstContext)
            try {
                store.observeState(firstLifecycle) { oldStates += it }
                store.observeEvent(firstLifecycle) { oldEvents += it }
                runCurrent()
                store.dispatch(Increment)
                runCurrent()
                firstLifecycle.destroy()
                assertSame(store, retained(secondContext))
                store.observeState(secondLifecycle) { newStates += it }
                store.observeEvent(secondLifecycle) { newEvents += it }
                runCurrent()
                store.dispatch(Increment)
                runCurrent()
                assertEquals(1, created)
                assertEquals(listOf(Counter(), Counter(1)), oldStates)
                assertEquals(listOf(Counter(1), Counter(2)), newStates)
                assertEquals(listOf(Changed), oldEvents)
                assertEquals(listOf(Changed), newEvents)
                keeper.destroy()
                store.dispatch(Increment)
                runCurrent()
                assertEquals(Counter(2), store.currentState)
            } finally {
                firstLifecycle.destroy()
                secondLifecycle.destroy()
                keeper.destroy()
            }
        }

    @Test
    fun manualCloseAndDestroyOnlyCancelTheirOwnObservers() =
        runTest {
            val lifecycle = TrackingLifecycle().apply { create() }
            val store =
                Store<Counter, Increment, Nothing>(Counter(), StandardTestDispatcher(testScheduler)) {
                    state<Counter> { action<Increment> { nextState { Counter(state.value + 1) } } }
                }
            val values = mutableListOf<Counter>()
            try {
                val handle = store.observeState(lifecycle) { values += it }
                assertEquals(1, lifecycle.callbacks.size)
                handle.close()
                assertTrue(lifecycle.callbacks.isEmpty())
                handle.close()
                runCurrent()
                assertTrue(values.isEmpty())
                lifecycle.destroy()
                store.dispatch(Increment)
                runCurrent()
                assertEquals(Counter(1), store.currentState)
            } finally {
                lifecycle.destroy()
                store.close()
            }
        }

    @Test
    fun immediateCallbackDestroyingLifecycleDoesNotLeaveObserverAlive() =
        runTest {
            val lifecycle = LifecycleRegistry().apply { create() }
            val values = mutableListOf<Counter>()
            val store =
                Store<Counter, Increment, Nothing>(Counter(), UnconfinedTestDispatcher(testScheduler)) {
                    autoStartPolicy(AutoStartPolicy.OnDispatch)
                    state<Counter> { action<Increment> { nextState { Counter(state.value + 1) } } }
                }
            try {
                store.observeState(lifecycle) {
                    values += it
                    lifecycle.destroy()
                }
                store.dispatch(Increment)
                runCurrent()
                assertEquals(listOf(Counter()), values)
                assertEquals(Counter(1), store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun destroyedLifecycleNeverRegistersOrStartsStore() =
        runTest {
            val lifecycle =
                LifecycleRegistry().apply {
                    create()
                    destroy()
                }
            var entered = false
            val store =
                Store<Counter, Nothing, Changed>(Counter(), StandardTestDispatcher(testScheduler)) {
                    state<Counter> {
                        enter {
                            entered = true
                            event(Changed)
                        }
                    }
                }
            val values = mutableListOf<Any>()
            try {
                store.observeState(lifecycle) { values += it }.close()
                store.observeEvent(lifecycle) { values += it }.close()
                runCurrent()
                assertTrue(values.isEmpty())
                assertTrue(!entered)
                // No observer should have consumed the Store's execution context, even briefly.
                store.patch { coroutineContext(StandardTestDispatcher(testScheduler)) }
            } finally {
                store.close()
            }
        }

    private class TrackingLifecycle(
        private val delegate: LifecycleRegistry = LifecycleRegistry(),
    ) : LifecycleRegistry by delegate {
        val callbacks = mutableSetOf<Lifecycle.Callbacks>()

        override fun subscribe(callbacks: Lifecycle.Callbacks) {
            this.callbacks += callbacks
            delegate.subscribe(callbacks)
        }

        override fun unsubscribe(callbacks: Lifecycle.Callbacks) {
            this.callbacks -= callbacks
            delegate.unsubscribe(callbacks)
        }
    }
}
