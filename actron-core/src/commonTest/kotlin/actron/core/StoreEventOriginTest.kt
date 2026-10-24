@file:OptIn(InternalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StoreEventOriginTest {
    private data object Snapshot : State

    private sealed interface Command : Action {
        data object EmitLater : Command

        data object Hold : Command
    }

    private data object Message : Event

    @Test
    fun eventHookFailureKeepsOriginAfterTheHandlerHasFinished() = assertEventOrigin(holdOtherInput = false)

    @Test
    fun eventHookFailureDoesNotBorrowAnUnrelatedProcessingInput() = assertEventOrigin(holdOtherInput = true)

    private fun assertEventOrigin(holdOtherInput: Boolean) =
        runTest {
            val emitRelease = CompletableDeferred<Unit>()
            val handlerRelease = CompletableDeferred<Unit>()
            val failure = IllegalStateException("event hook")
            val traces = mutableListOf<StoreTrace<Snapshot, Command, Message>>()
            val reported = mutableListOf<Throwable>()
            val store =
                Store<Snapshot, Command, Message>(Snapshot) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    exceptionHandler(ExceptionHandler { reported += it })
                    probe(StoreProbe { traces += it })
                    plugin(Plugin(onEvent = { _, _ -> throw failure }))
                    state<Snapshot> {
                        action<Command.EmitLater> {
                            launch {
                                emitRelease.await()
                                event(Message)
                            }
                        }
                        action<Command.Hold> { handlerRelease.await() }
                    }
                }
            try {
                store.dispatchAndAwaitForTest(Command.EmitLater)
                if (holdOtherInput) {
                    store.dispatch(Command.Hold)
                    runCurrent()
                }
                emitRelease.complete(Unit)
                runCurrent()
                val emitted = traces.filterIsInstance<StoreTrace.EventEmitted<Message>>().single()
                val failed = traces.filterIsInstance<StoreTrace.FailureReported>().single()
                assertEquals(InputId(2), emitted.input)
                assertEquals(emitted.input, failed.input)
                assertSame(failure, failed.error)
                assertEquals(listOf<Throwable>(failure), reported)
                handlerRelease.complete(Unit)
                store.awaitIdleForTest()
            } finally {
                handlerRelease.complete(Unit)
                emitRelease.complete(Unit)
                store.close()
            }
        }
}
