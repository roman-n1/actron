@file:OptIn(actron.core.InternalActronApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/*
 * This test case was generated with assistance from Anthropic's Claude AI.
 */

// State Transition Diagram:
//
// ┌───────────────────────────────────────────┐
// │                                           │
// │  Initial ─────────► Running ────► Final   │
// │                       │                   │
// │                       │                   │
// │                       ▼                   │
// │                  (Background              │
// │                   Operations)             │
// │                                           │
// └───────────────────────────────────────────┘
//
// Initial: Starting state, no background work
// Running: Active state with a background coroutine
// Final: End state, background work is cancelled

@OptIn(ExperimentalCoroutinesApi::class)
class StoreStateCoroutineScopeTest {
    private val testDispatcher = StandardTestDispatcher()

    // State definitions
    sealed interface AppState : State {
        data object Initial : AppState

        data class Running(
            val value: Int = 0,
        ) : AppState

        data class Failed(
            val message: String,
        ) : AppState

        data object Final : AppState
    }

    // Action definitions
    sealed interface AppAction : Action {
        data object Start : AppAction

        data object Stop : AppAction
    }

    // Event definitions
    sealed interface AppEvent : Event {
        data class ValueChanged(
            val value: Int,
        ) : AppEvent

        data object Completed : AppEvent
    }

    // Store factory for basic functionality test
    private fun createTestStore(
        withCancellation: Boolean = false,
        onBackgroundTaskStart: () -> Unit = {},
        onBackgroundTaskCancel: () -> Unit = {},
    ): Store<AppState, AppAction, AppEvent> =
        createRuntimeStore(AppState.Initial) {
            // Configure to use Unconfined dispatcher
            coroutineContext(testDispatcher)

            // Initial state handling
            state<AppState.Initial> {
                action<AppAction.Start> {
                    nextState { AppState.Running() }
                }
            }

            // Running state with background operations
            state<AppState.Running> {
                enter {
                    // Launch background task with launch function
                    launch {
                        if (withCancellation) {
                            onBackgroundTaskStart()
                            try {
                                awaitCancellation()
                            } finally {
                                onBackgroundTaskCancel()
                            }
                        } else {
                            transaction {
                                nextState { state.copy(value = 5) }
                            }

                            // Emit event
                            event(AppEvent.ValueChanged(5))
                        }
                    }
                }

                // Stop action transitions to Final
                action<AppAction.Stop> {
                    nextState { AppState.Final }
                }
            }

            // Final state
            state<AppState.Final> {
                enter {
                    if (!withCancellation) {
                        event(AppEvent.Completed)
                    }
                }
            }
        }

    @Test
    fun enterCoroutineScope_dispatchesActionsAndEmitsEvents() =
        runTest(testDispatcher) {
            val store = createTestStore()
            try {
                assertEquals(AppState.Initial, store.currentState)
                store.dispatchAndAwaitForTest(AppAction.Start)
                store.awaitIdleForTest()
                val runningState = assertIs<AppState.Running>(store.currentState)
                assertEquals(5, runningState.value)
                store.dispatchAndAwaitForTest(AppAction.Stop)
                store.awaitIdleForTest()
                assertEquals(AppState.Final, store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun enterCoroutineScope_cancelsBackgroundTaskOnStateTransition() =
        runTest(testDispatcher) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val store =
                createTestStore(
                    withCancellation = true,
                    onBackgroundTaskStart = { started.complete(Unit) },
                    onBackgroundTaskCancel = { cancelled.complete(Unit) },
                )
            try {
                assertEquals(AppState.Initial, store.currentState)
                store.dispatchAndAwaitForTest(AppAction.Start)
                started.await()
                assertEquals(AppState.Running::class, store.currentState::class)
                store.dispatchAndAwaitForTest(AppAction.Stop)
                cancelled.await()
                store.awaitIdleForTest()
                assertEquals(AppState.Final, store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun enterLaunch_transactionFatalError_isForwardedToExceptionHandler() =
        runTest(testDispatcher) {
            val handled = mutableListOf<Throwable>()
            val store: Store<AppState, AppAction, AppEvent> =
                createRuntimeStore(AppState.Initial) {
                    coroutineContext(testDispatcher)
                    exceptionHandler(ExceptionHandler { handled += it })
                    state<AppState.Initial> { action<AppAction.Start> { nextState { AppState.Running() } } }
                    state<AppState.Running> { enter { launch { transaction { throw AssertionError("fatal enter transaction") } } } }
                    state<AppState> { recover<Exception> { nextState { AppState.Failed(error.message ?: "unknown") } } }
                }
            try {
                store.dispatchAndAwaitForTest(AppAction.Start)
                store.awaitIdleForTest()
                val error = assertIs<AssertionError>(handled.single())
                assertEquals("fatal enter transaction", error.message)
                assertEquals(AppState.Running(), store.currentState)
            } finally {
                store.close()
            }
        }
}
