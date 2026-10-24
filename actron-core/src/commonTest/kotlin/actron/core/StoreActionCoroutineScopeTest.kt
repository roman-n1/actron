package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreActionCoroutineScopeTest {
    private val testDispatcher = StandardTestDispatcher()

    private class DirectThrowable(
        message: String,
    ) : Throwable(message)

    sealed interface AppState : State {
        data class Active(
            val value: Int = 0,
        ) : AppState

        data class Completed(
            val value: Int = 0,
        ) : AppState

        data class Failed(
            val message: String,
        ) : AppState
    }

    sealed interface AppAction : Action {
        data object LaunchIncrement : AppAction

        data class LaunchWithDelta(
            val delta: Int,
        ) : AppAction

        data object LaunchLongRunning : AppAction

        data object MoveToCompleted : AppAction

        data object MoveToCompletedThenLaunch : AppAction

        data object LaunchThrow : AppAction

        data object LaunchTransactionThrow : AppAction

        data object LaunchFatal : AppAction

        data object LaunchTransactionFatal : AppAction

        data object LaunchBareThrowable : AppAction
    }

    private fun createTestStore(
        onLongRunningStart: () -> Unit = {},
        onLongRunningCancelled: () -> Unit = {},
        exceptionHandler: ExceptionHandler = ExceptionHandler.Ignore,
    ): Store<AppState, AppAction, Nothing> =
        Store(AppState.Active()) {
            coroutineContext(testDispatcher)
            exceptionHandler(exceptionHandler)

            state<AppState.Active> {
                action<AppAction.LaunchIncrement> {
                    launch {
                        transaction {
                            nextState { state.copy(value = state.value + 1) }
                        }
                    }
                }

                action<AppAction.LaunchWithDelta> {
                    launch {
                        val delta = action.delta
                        transaction {
                            nextState { state.copy(value = state.value + delta + action.delta) }
                        }
                    }
                }

                action<AppAction.LaunchLongRunning> {
                    launch {
                        onLongRunningStart()
                        try {
                            awaitCancellation()
                        } finally {
                            onLongRunningCancelled()
                        }
                    }
                }

                action<AppAction.MoveToCompleted> {
                    nextState { AppState.Completed(value = state.value) }
                }

                action<AppAction.MoveToCompletedThenLaunch> {
                    nextState { AppState.Completed(value = state.value + 1) }
                    launch {
                        transaction {
                            nextState { AppState.Completed(value = 999) }
                        }
                    }
                }

                action<AppAction.LaunchThrow> {
                    launch {
                        throw IllegalStateException("launch failed")
                    }
                }

                action<AppAction.LaunchFatal> {
                    launch {
                        throw AssertionError("fatal launch")
                    }
                }

                action<AppAction.LaunchBareThrowable> {
                    launch {
                        throw DirectThrowable("bare throwable")
                    }
                }

                action<AppAction.LaunchTransactionThrow> {
                    launch {
                        transaction {
                            throw IllegalArgumentException("transaction failed")
                        }
                    }
                }

                action<AppAction.LaunchTransactionFatal> {
                    launch {
                        transaction {
                            throw AssertionError("fatal transaction")
                        }
                    }
                }
            }

            state<AppState> {
                recover<Exception> {
                    nextState { AppState.Failed(error.message ?: "unknown") }
                }
            }
        }

    @Test
    fun actionLaunch_updatesStateViaTransaction() =
        runTest(testDispatcher) {
            val store = createTestStore()

            try {
                store.dispatchAndAwaitForTest(AppAction.LaunchIncrement)
                store.awaitIdleForTest()

                assertEquals(AppState.Active(value = 1), store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun actionLaunch_canReferenceActionInLaunchAndTransaction() =
        runTest(testDispatcher) {
            val store = createTestStore()

            try {
                store.dispatchAndAwaitForTest(AppAction.LaunchWithDelta(delta = 2))
                store.awaitIdleForTest()

                assertEquals(AppState.Active(value = 4), store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun actionLaunch_isCancelledWhenStateChanges() =
        runTest(testDispatcher) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val store =
                createTestStore(
                    onLongRunningStart = { started.complete(Unit) },
                    onLongRunningCancelled = { cancelled.complete(Unit) },
                )

            try {
                store.dispatchAndAwaitForTest(AppAction.LaunchLongRunning)
                started.await()
                assertTrue(started.isCompleted, "Long-running action launch should start")

                store.dispatchAndAwaitForTest(AppAction.MoveToCompleted)
                store.awaitIdleForTest()

                assertEquals(AppState.Completed(value = 0), store.currentState)
                assertTrue(cancelled.isCompleted, "Long-running action launch should be cancelled on state transition")
            } finally {
                store.close()
            }
        }

    @Test
    fun actionLaunch_afterNextState_bindsToOriginalState() =
        runTest(testDispatcher) {
            val store = createTestStore()

            try {
                store.dispatchAndAwaitForTest(AppAction.MoveToCompletedThenLaunch)
                store.awaitIdleForTest()

                assertEquals(AppState.Completed(value = 1), store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun actionLaunch_exception_isHandledByErrorBlock() =
        runTest(testDispatcher) {
            val store = createTestStore()

            try {
                store.dispatchAndAwaitForTest(AppAction.LaunchThrow)
                store.awaitIdleForTest()

                assertEquals(AppState.Failed("launch failed"), store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun actionLaunch_transactionException_isHandledByErrorBlock() =
        runTest(testDispatcher) {
            val store = createTestStore()

            try {
                store.dispatchAndAwaitForTest(AppAction.LaunchTransactionThrow)
                store.awaitIdleForTest()

                assertEquals(AppState.Failed("transaction failed"), store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun actionLaunch_fatalError_isForwardedToExceptionHandler() =
        runTest(testDispatcher) {
            val handledThrowableObservations = mutableListOf<Throwable>()
            val store =
                createTestStore(
                    exceptionHandler = ExceptionHandler { handledThrowableObservations.add(it) },
                )

            try {
                store.dispatchAndAwaitForTest(AppAction.LaunchFatal)
                store.awaitIdleForTest()

                assertNotNull(handledThrowableObservations.single())
                val error = handledThrowableObservations.single()
                assertIs<AssertionError>(error)
                assertEquals("fatal launch", error.message)
                assertEquals(AppState.Active(value = 0), store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun actionLaunch_nonExceptionThrowable_isForwardedToExceptionHandler() =
        runTest(testDispatcher) {
            val handledThrowableObservations = mutableListOf<Throwable>()
            val store =
                createTestStore(
                    exceptionHandler = ExceptionHandler { handledThrowableObservations.add(it) },
                )

            try {
                store.dispatchAndAwaitForTest(AppAction.LaunchBareThrowable)
                store.awaitIdleForTest()

                assertNotNull(handledThrowableObservations.single())
                val error = handledThrowableObservations.single()
                assertIs<DirectThrowable>(error)
                assertEquals("bare throwable", error.message)
                assertEquals(AppState.Active(value = 0), store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun actionLaunch_transactionFatalError_isForwardedToExceptionHandler() =
        runTest(testDispatcher) {
            val handledThrowableObservations = mutableListOf<Throwable>()
            val store =
                createTestStore(
                    exceptionHandler = ExceptionHandler { handledThrowableObservations.add(it) },
                )

            try {
                store.dispatchAndAwaitForTest(AppAction.LaunchTransactionFatal)
                store.awaitIdleForTest()

                assertNotNull(handledThrowableObservations.single())
                val error = handledThrowableObservations.single()
                assertIs<AssertionError>(error)
                assertEquals("fatal transaction", error.message)
                assertEquals(AppState.Active(value = 0), store.currentState)
            } finally {
                store.close()
            }
        }
}
