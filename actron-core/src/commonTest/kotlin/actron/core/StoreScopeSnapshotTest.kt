@file:OptIn(ExperimentalCoroutinesApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every callback keeps its entry snapshot; only its final proposal is committed.
 * Counter(0) --enter--> Counter(2) --Next--> Counter(4) --Fail/recover--> Counter(6).
 */
class StoreScopeSnapshotTest {
    private data class Counter(
        val value: Int = 0,
    ) : State

    private sealed interface Command : Action {
        data object Next : Command

        data object Fail : Command

        data class Add(
            val amount: Int,
        ) : Command
    }

    private data class Snapshot(
        val value: Int,
    ) : Event

    @Test
    fun handlersKeepTheirSnapshotAndEventsPrecedeTheFinalProposal() =
        runTest {
            val observed = mutableListOf<Pair<Int, Int>>()
            val store =
                Store<Counter, Command, Snapshot>(Counter()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    plugin(Plugin(onEvent = { state, event -> observed += state.value to event.value }))
                    state<Counter> {
                        enter {
                            nextState { Counter(1) }
                            nextState { Counter(2) }
                            event(Snapshot(state.value))
                        }
                        action<Command.Next> {
                            nextState { Counter(3) }
                            nextState { Counter(4) }
                            event(Snapshot(state.value))
                        }
                        action<Command.Fail> {
                            nextState { Counter(999) }
                            error("discard this proposal")
                        }
                        recover<IllegalStateException> {
                            nextState { Counter(5) }
                            nextState { Counter(6) }
                            event(Snapshot(state.value))
                        }
                    }
                }

            store.startAndAwaitForTest()
            assertEquals(Counter(2), store.currentState)
            store.dispatchAndAwaitForTest(Command.Next)
            assertEquals(Counter(4), store.currentState)
            store.dispatchAndAwaitForTest(Command.Fail)
            assertEquals(Counter(6), store.currentState)
            assertEquals(listOf(0 to 0, 2 to 2, 4 to 4), observed)
            store.close()
        }

    @Test
    fun actionTransactionReadsFreshStateAndRetainsItsInitiatingAction() =
        runTest {
            val proceed = CompletableDeferred<Unit>()
            val observed = mutableListOf<Pair<Int, Int>>()
            val store =
                Store<Counter, Command, Snapshot>(Counter()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    plugin(Plugin(onEvent = { state, event -> observed += state.value to event.value }))
                    state<Counter> {
                        action<Command.Add> {
                            launch {
                                proceed.await()
                                transaction {
                                    nextState { Counter(999) }
                                    nextState { Counter(state.value + action.amount) }
                                    event(Snapshot(state.value))
                                }
                            }
                        }
                        action<Command.Next> { nextState { Counter(100) } }
                    }
                }
            store.dispatch(Command.Add(7))
            runCurrent()
            store.dispatchAndAwaitForTest(Command.Next)
            proceed.complete(Unit)
            advanceUntilIdle()

            assertEquals(Counter(107), store.currentState)
            assertEquals(listOf(100 to 100), observed)
            store.close()
        }

    @Test
    fun enterTransactionReadsFreshStateAndKeepsItsSnapshotUntilCommit() =
        runTest {
            val proceed = CompletableDeferred<Unit>()
            val observed = mutableListOf<Pair<Int, Int>>()
            val store =
                Store<Counter, Command, Snapshot>(Counter()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    plugin(Plugin(onEvent = { state, event -> observed += state.value to event.value }))
                    state<Counter> {
                        enter {
                            launch {
                                proceed.await()
                                transaction {
                                    nextState { Counter(999) }
                                    nextState { Counter(state.value + 3) }
                                    event(Snapshot(state.value))
                                }
                            }
                        }
                        action<Command.Next> { nextState { Counter(100) } }
                    }
                }
            store.startAndAwaitForTest()
            runCurrent()
            store.dispatchAndAwaitForTest(Command.Next)
            proceed.complete(Unit)
            advanceUntilIdle()

            assertEquals(Counter(103), store.currentState)
            assertEquals(listOf(100 to 100), observed)
            store.close()
        }
}
