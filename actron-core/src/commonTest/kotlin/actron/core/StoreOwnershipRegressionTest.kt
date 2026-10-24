@file:OptIn(ExperimentalCoroutinesApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** Counter --Increment--> Counter; tracked launches commit only while their activation is alive. */
class StoreOwnershipRegressionTest {
    private data class Counter(
        val value: Int = 0,
    ) : State

    private data object Increment : Action

    private data class Work(
        val value: Int,
    ) : Action

    @Test
    fun startupRetryKeepsSuccessfulRegistrationsAndEventuallyProcessesTheAction() =
        runTest {
            for (policy in PluginExecutionPolicy.entries) {
                val calls = mutableListOf<String>()
                val errors = mutableListOf<Throwable>()
                var attempts = 0
                val store =
                    Store<Counter, Increment, Nothing>(Counter()) {
                        coroutineContext(StandardTestDispatcher(testScheduler))
                        pluginExecutionPolicy(policy)
                        exceptionHandler(ExceptionHandler { errors += it })
                        plugin(
                            Plugin(onStart = {
                                calls += "retry"
                                if (++attempts == 1) error("first startup fails")
                            }),
                            Plugin(onStart = { calls += "once" }),
                        )
                        state<Counter> {
                            enter { calls += "enter" }
                            action<Increment> { nextState { state.copy(value = state.value + 1) } }
                        }
                    }

                store.dispatch(Increment)
                advanceUntilIdle()
                assertEquals(Counter(), store.currentState)
                assertEquals(listOf("retry", "once"), calls)
                assertEquals(1, errors.size)
                assertEquals("first startup fails", errors.single().message)

                store.dispatch(Increment)
                advanceUntilIdle()
                store.dispatch(Increment)
                advanceUntilIdle()
                assertEquals(listOf("retry", "once", "retry", "enter"), calls)
                assertEquals(Counter(2), store.currentState)
                store.close()
            }
        }

    @Test
    fun completionOfReplacedLaunchDoesNotRemoveTheRunningLane() =
        runTest {
            val firstCleanup = CompletableDeferred<Unit>()
            val secondFinish = CompletableDeferred<Unit>()
            val started = mutableListOf<Int>()
            val lane = LaunchLane()
            val store =
                Store<Counter, Work, Nothing>(Counter()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    state<Counter> {
                        action<Work> {
                            val control = if (action.value == 3) LaunchControl.DropIfRunning(lane) else LaunchControl.CancelPrevious(lane)
                            launch(control = control) {
                                started += action.value
                                if (action.value == 1) {
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        withContext(NonCancellable) { firstCleanup.await() }
                                    }
                                } else {
                                    secondFinish.await()
                                    transaction { nextState { state.copy(value = action.value) } }
                                }
                            }
                        }
                    }
                }

            store.dispatch(Work(1))
            runCurrent()
            store.dispatch(Work(2))
            runCurrent()
            firstCleanup.complete(Unit)
            runCurrent()
            store.dispatch(Work(3))
            runCurrent()
            assertEquals(listOf(1, 2), started)

            secondFinish.complete(Unit)
            advanceUntilIdle()
            assertEquals(Counter(2), store.currentState)
            store.close()
        }
}
