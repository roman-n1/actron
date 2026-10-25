@file:OptIn(InternalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StorePublicationBoundaryTest {
    private data class Counter(
        val value: Int = 0,
    ) : State

    private sealed interface Command : Action {
        data object Increment : Command

        data object Emit : Command
    }

    private data class Message(
        val value: Int,
    ) : Event

    @Test
    fun committedStateIsVisibleWhileHooksSuspendAndTheNextInputStillWaits() =
        runTest {
            val hookRelease = CompletableDeferred<Unit>()
            val log = mutableListOf<String>()
            val store =
                createRuntimeStore<Counter, Command, Message>(Counter()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    probe(StoreProbe { if (it is StoreTrace.StateCommitted) log += "commit:${it.state.value}" })
                    stateSaver(StateSaver(save = { log += "save:${it.value}" }, restore = { it }))
                    plugin(
                        Plugin(onState = { _, state ->
                            log += "hook:${state.value}"
                            if (state.value == 1) hookRelease.await()
                        }),
                    )
                    state<Counter> {
                        action<Command.Increment> {
                            log += "action:${state.value}"
                            nextState { Counter(state.value + 1) }
                        }
                    }
                }
            try {
                store.dispatch(Command.Increment)
                runCurrent()
                assertEquals(Counter(1), store.currentState)
                assertEquals(Counter(1), store.state.value)
                assertEquals(listOf("action:0", "commit:1", "save:1", "hook:1"), log)
                store.dispatch(Command.Increment)
                runCurrent()
                assertEquals(Counter(1), store.currentState)
                assertEquals(4, log.size)
                hookRelease.complete(Unit)
                runCurrent()
                assertEquals(Counter(2), store.currentState)
                assertEquals(listOf("action:0", "commit:1", "save:1", "hook:1", "action:1", "commit:2", "save:2", "hook:2"), log)
            } finally {
                store.close()
            }
        }

    @Test
    fun eventBackpressurePrecedesTraceAndHooksAndHookFailureDoesNotAbortTheHandler() =
        runTest {
            val collectorRelease = CompletableDeferred<Unit>()
            val received = mutableListOf<Message>()
            val hooks = mutableListOf<Message>()
            val traces = mutableListOf<StoreTrace<Counter, Command, Message>>()
            val reported = mutableListOf<Throwable>()
            val hookFailure = IllegalStateException("event hook")
            val store =
                createRuntimeStore<Counter, Command, Message>(Counter()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    probe(StoreProbe { traces += it })
                    exceptionHandler(ExceptionHandler { reported += it })
                    plugin(
                        Plugin(onEvent = { _, event ->
                            hooks += event
                            if (event.value == 1) throw hookFailure
                        }),
                    )
                    state<Counter> {
                        action<Command.Emit> {
                            event(Message(1))
                            event(Message(2))
                            nextState { Counter(2) }
                        }
                    }
                }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                store.event.collect {
                    received += it
                    if (it.value == 1) collectorRelease.await()
                }
            }
            try {
                val dispatch = launch { store.dispatchAndAwaitForTest(Command.Emit) }
                runCurrent()
                assertFalse(dispatch.isCompleted)
                assertEquals(Counter(), store.currentState)
                assertEquals(listOf(Message(1)), received)
                assertEquals(listOf(Message(1)), hooks)
                assertEquals(listOf(Message(1)), traces.filterIsInstance<StoreTrace.EventEmitted<Message>>().map { it.event })
                assertEquals(listOf<Throwable>(hookFailure), reported)
                collectorRelease.complete(Unit)
                dispatch.join()
                assertEquals(listOf(Message(1), Message(2)), received)
                assertEquals(received, hooks)
                assertEquals(Counter(2), store.currentState)
                assertEquals(listOf(InputId(2), InputId(2)), traces.filterIsInstance<StoreTrace.EventEmitted<Message>>().map { it.input })
                assertEquals(listOf(StoreTrace.FailureReported(InputId(2), hookFailure)), traces.filterIsInstance<StoreTrace.FailureReported>())
                assertEquals(ProcessingOutcome.Handled(1), traces.filterIsInstance<StoreTrace.ProcessingFinished>().last().outcome)
            } finally {
                store.close()
            }
        }
}
