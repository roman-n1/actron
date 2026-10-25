@file:OptIn(InternalActronApi::class)

package actron.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Recovery sees committed state; post-commit reporting preserves the rest of the enter chain. */
class StoreTransitionBoundaryTest {
    private sealed interface Screen : State {
        data object Ready : Screen

        data object Broken : Screen

        data class Recovered(
            val entered: Boolean = false,
        ) : Screen
    }

    private data object Go : Action

    @Test
    fun saverFailureDoesNotAbortEnterAndRecoveryUsesTheCommittedState() =
        runTest {
            val log = mutableListOf<String>()
            val traces = mutableListOf<StoreTrace<Screen, Go, Nothing>>()
            val enterFailure = IllegalStateException("enter")
            val saverFailure = IllegalArgumentException("save")
            val store =
                createRuntimeStore<Screen, Go, Nothing>(Screen.Ready) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    probe(StoreProbe { traces += it })
                    exceptionHandler(ExceptionHandler { log += "report:${it.message}" })
                    stateSaver(
                        StateSaver(
                            save = {
                                log += "save:$it"
                                if (it == Screen.Broken) throw saverFailure
                            },
                            restore = { it },
                        ),
                    )
                    plugin(Plugin(onState = { before, after -> log += "publish:$before->$after" }))
                    state<Screen.Ready> {
                        action<Go> {
                            log += "action"
                            nextState { Screen.Broken }
                        }
                        exit { log += "exit:Ready" }
                    }
                    state<Screen.Broken> {
                        enter {
                            log += "enter:Broken"
                            throw enterFailure
                        }
                        recover<IllegalStateException> {
                            assertSame(enterFailure, error)
                            log += "recover:$state"
                            nextState { Screen.Recovered() }
                        }
                        exit { log += "exit:Broken" }
                    }
                    state<Screen.Recovered> {
                        enter {
                            log += "enter:Recovered"
                            nextState { state.copy(entered = true) }
                        }
                    }
                }
            try {
                store.dispatchAndAwaitForTest(Go)
                assertEquals(Screen.Recovered(entered = true), store.currentState)
                assertEquals(
                    listOf(
                        "action",
                        "exit:Ready",
                        "save:Broken",
                        "report:save",
                        "publish:Ready->Broken",
                        "enter:Broken",
                        "recover:Broken",
                        "exit:Broken",
                        "save:Recovered(entered=false)",
                        "publish:Broken->Recovered(entered=false)",
                        "enter:Recovered",
                        "save:Recovered(entered=true)",
                        "publish:Recovered(entered=false)->Recovered(entered=true)",
                    ),
                    log,
                )
                assertEquals(
                    ProcessingOutcome.Recovered(enterFailure, commits = 3),
                    traces.filterIsInstance<StoreTrace.ProcessingFinished>().last().outcome,
                )
                assertEquals(listOf(saverFailure), traces.filterIsInstance<StoreTrace.FailureReported>().map { it.error })
            } finally {
                store.close()
            }
        }

    @Test
    fun rejectedRecoveryKeepsStateAndReportsValidatorFailureWithOriginalSuppressed() =
        runTest {
            val original = IllegalStateException("action")
            val rejected = IllegalArgumentException("validator")
            val reported = mutableListOf<Throwable>()
            val store =
                createRuntimeStore<Screen, Go, Nothing>(Screen.Ready) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    exceptionHandler(ExceptionHandler { reported += it })
                    validateRecovery { before, after ->
                        assertEquals(Screen.Ready, before)
                        assertEquals(Screen.Recovered(), after)
                        throw rejected
                    }
                    state<Screen.Ready> {
                        action<Go> { throw original }
                        recover<IllegalStateException> { nextState { Screen.Recovered() } }
                    }
                }
            try {
                assertSame(store, store.patchForTest {})
                store.dispatchAndAwaitForTest(Go)
                assertEquals(Screen.Ready, store.currentState)
                assertEquals(listOf<Throwable>(rejected), reported)
                assertEquals(listOf(original), rejected.suppressedExceptions)
            } finally {
                store.close()
            }
        }
}
