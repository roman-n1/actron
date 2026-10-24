package actron.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoreConcurrentTest {
    private val testDispatcher = StandardTestDispatcher()

    sealed interface AppState : State {
        data class Initial(
            val count: Int,
        ) : AppState

        data class Processing(
            val count: Int,
        ) : AppState

        data class Result(
            val count: Int,
        ) : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction

        data object Decrement : AppAction

        data object DelayedIncrement : AppAction

        data object RequestResult : AppAction
    }

    sealed interface AppEvent : Event {
        data object Processing : AppEvent

        data class Completed(
            val finalCount: Int,
        ) : AppEvent
    }

    private fun createTestStore(
        initialState: AppState,
    ): Store<AppState, AppAction, AppEvent> =
        Store(initialState) {
            coroutineContext(testDispatcher)

            state<AppState.Initial> {
                action<AppAction.Increment> {
                    nextState { AppState.Initial(state.count + 1) }
                }
                action<AppAction.Decrement> {
                    nextState { AppState.Initial(state.count - 1) }
                }
                action<AppAction.DelayedIncrement> {
                    nextState { AppState.Processing(state.count) }
                    event(AppEvent.Processing)
                }
                action<AppAction.RequestResult> {
                    nextState { AppState.Result(state.count) }
                }
            }

            state<AppState.Processing> {
                enter {
                    launch {
                        // Simulate long processing
                        delay(100)
                        transaction {
                            val newCount = state.count + 1
                            nextState { AppState.Result(newCount) }
                            event(AppEvent.Completed(newCount))
                        }
                    }
                }

                action<AppAction.RequestResult> {
                    // The delayed launch has not entered its transaction and does not hold the lock.
                    // Leaving Processing cancels that launch before it can commit.
                    nextState { AppState.Result(state.count) }
                }
            }

            state<AppState.Result> {
                action<AppAction.Increment> {
                    nextState { AppState.Result(state.count + 1) }
                }
                action<AppAction.Decrement> {
                    nextState { AppState.Result(state.count - 1) }
                }
            }
        }

    @Test
    fun concurrentActions_shouldHandleRaceConditions() =
        runTest(testDispatcher) {
            val store = createTestStore(AppState.Initial(0))
            try {
                val jobs =
                    List(10) { index ->
                        launch { store.dispatch(if (index % 2 == 0) AppAction.Increment else AppAction.Decrement) }
                    }
                jobs.forEach { it.join() }
                store.awaitIdleForTest()
                assertEquals(AppState.Initial(0), store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun stateExitCancelsDelayedEnterWorkBeforeItsTransaction() =
        runTest(testDispatcher) {
            val store = createTestStore(AppState.Initial(0))
            val events = mutableListOf<AppEvent>()
            store.collectEvent { events += it }
            try {
                store.dispatchAndAwaitForTest(AppAction.DelayedIncrement)
                assertEquals(AppState.Processing(0), store.currentState)
                store.dispatchAndAwaitForTest(AppAction.RequestResult)
                store.awaitIdleForTest()
                advanceUntilIdle()
                assertEquals(AppState.Result(0), store.currentState)
                assertEquals(listOf<AppEvent>(AppEvent.Processing), events)
            } finally {
                store.close()
            }
        }
}
