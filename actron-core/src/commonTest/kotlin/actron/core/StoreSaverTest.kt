@file:OptIn(actron.core.InternalActronApi::class)

package actron.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoreSaverTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    data class AppState(
        val value: Int,
    ) : State

    sealed interface AppAction : Action {
        data class Update(
            val value: Int,
        ) : AppAction
    }

    private fun createTestStore(
        initialState: AppState,
        stateSaver: StateSaver<AppState>,
        exceptionHandler: ExceptionHandler = ExceptionHandler.Ignore,
        recovery: StoreBuilder.StateHandlerConfig<AppState, AppAction, Nothing, AppState>.() -> Unit = {},
    ): Store<AppState, AppAction, Nothing> =
        createRuntimeStore(initialState) {
            coroutineContext(Dispatchers.Unconfined)
            stateSaver(stateSaver)
            exceptionHandler(exceptionHandler)
            state<AppState> {
                action<AppAction.Update> {
                    nextState { state.copy(value = action.value) }
                }
                recovery()
            }
        }

    @Test
    fun store_shouldUseStateSaverToRestoreState() =
        runTest(testDispatcher) {
            var savedState = AppState(10)

            val stateSaver =
                StateSaver<AppState>(
                    save = { state -> savedState = state },
                    restore = { savedState },
                )

            val store = createTestStore(AppState(0), stateSaver)

            assertEquals(AppState(10), store.currentState)

            store.dispatch(AppAction.Update(20))

            assertEquals(AppState(20), savedState)
        }

    @Test
    fun store_saveException_isHandledWithoutUsingStoreErrorHandling() =
        runTest(testDispatcher) {
            val handledExceptionObservations = mutableListOf<Throwable>()
            val store =
                createTestStore(
                    initialState = AppState(0),
                    stateSaver =
                        StateSaver(
                            save = { throw IllegalArgumentException("save failed") },
                            restore = { it },
                        ),
                    exceptionHandler = ExceptionHandler { handledExceptionObservations.add(it) },
                    recovery = { recover<Exception> { nextState { AppState(-1) } } },
                )

            store.dispatch(AppAction.Update(20))
            testScheduler.runCurrent()

            assertEquals(AppState(20), store.currentState)
            val error = assertIs<IllegalArgumentException>(handledExceptionObservations.single())
            assertEquals("save failed", error.message)
        }
}
