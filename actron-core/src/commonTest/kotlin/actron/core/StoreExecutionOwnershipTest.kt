@file:OptIn(InternalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Each Store owns its execution marker; inspecting or waiting on an idle Store does not start it. */
class StoreExecutionOwnershipTest {
    private data class Counter(
        val value: Int = 0,
    ) : State

    private sealed interface Command : Action {
        data object Increment : Command

        data object Observe : Command
    }

    @Test
    fun handlerCanAwaitAnotherStoreWithoutBeingMistakenForItsOwnHandler() =
        runTest {
            val peer =
                Store<Counter, Command, Nothing>(Counter()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    state<Counter> {
                        action<Command.Increment> { nextState { Counter(state.value + 1) } }
                    }
                }
            val store =
                Store<Counter, Command, Nothing>(Counter()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    state<Counter> {
                        action<Command.Increment> {
                            peer.dispatchAndAwaitForTest(Command.Increment)
                            assertEquals(StorePendingWork(0, 0), (peer as StoreInternalApi<Counter, Command, Nothing>).awaitIdle(1.seconds))
                            nextState { Counter(peer.currentState.value + 10) }
                        }
                    }
                }
            try {
                store.dispatchAndAwaitForTest(Command.Increment)
                assertEquals(Counter(1), peer.currentState)
                assertEquals(Counter(11), store.currentState)
            } finally {
                store.close()
                peer.close()
            }
        }

    @Test
    fun waitingOnAnIdleStoreDoesNotConsumeItsInitialStateOrExecutionContext() =
        runTest {
            val entered = mutableListOf<Counter>()
            val store =
                Store<Counter, Command, Nothing>(Counter()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    state<Counter> { enter { entered += state } }
                }
            try {
                assertEquals(StorePendingWork(0, 0), (store as StoreInternalApi<Counter, Command, Nothing>).awaitIdle(1.seconds))
                assertTrue(entered.isEmpty())
                store.patchForTest {
                    initialState(Counter(7))
                    coroutineContext(StandardTestDispatcher(testScheduler))
                }
                store.startAndAwaitForTest()
                assertEquals(listOf(Counter(7)), entered)
            } finally {
                store.close()
            }
        }

    @Test
    fun pluginLaunchReadsLiveStateButKeepsItsInitiatingInput() =
        runTest {
            val proceed = CompletableDeferred<Unit>()
            val expectedOrigin = CompletableDeferred<InputAttribution>()
            val observed = mutableListOf<Pair<Int, InputAttribution>>()
            val store =
                Store<Counter, Command, Nothing>(Counter()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    plugin(
                        Plugin(onAction = { _, action ->
                            if (action == Command.Observe) {
                                expectedOrigin.complete(currentInputId())
                                launch {
                                    proceed.await()
                                    observed += currentState.value to currentInputId()
                                }
                            }
                        }),
                    )
                    state<Counter> {
                        action<Command.Increment> { nextState { Counter(state.value + 1) } }
                    }
                }
            try {
                store.dispatchAndAwaitForTest(Command.Observe)
                store.dispatchAndAwaitForTest(Command.Increment)
                store.dispatchAndAwaitForTest(Command.Increment)
                proceed.complete(Unit)
                runCurrent()
                assertEquals(listOf(2 to expectedOrigin.await()), observed)
            } finally {
                store.close()
            }
        }
}
