@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, actron.core.InternalActronApi::class)

package actron.logging

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.InputKind
import actron.core.State
import actron.core.Store
import actron.core.StoreProbe
import actron.core.StoreTrace
import actron.core.createRuntimeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoggingDeliveryContractTest {
    @Test
    fun replacementJobInLoggingContextKeepsItsExistingIndependentLifetime() =
        runTest {
            val loggingParent = Job()
            val output = mutableListOf<String>()
            val store = loggingStore(Logger { _, _, message -> output += message() }, dispatcher = loggingParent + StandardTestDispatcher(testScheduler))
            try {
                store.dispatch(LogAction())
                store.closeAndJoin()
                assertTrue(output.isEmpty())
                runCurrent()
                assertEquals(expectedLines(0), output)
            } finally {
                loggingParent.cancel()
                runCurrent()
                store.closeAndJoin()
            }
        }

    @Test
    fun loggerCancellationKeepsCoroutineSemanticsWithoutAbortingProcessing() =
        runTest {
            for (context in listOf(EmptyCoroutineContext, StandardTestDispatcher(testScheduler))) {
                val handled = mutableListOf<Throwable>()
                val store = loggingStore(Logger { _, _, _ -> throw CancellationException("logger cancelled") }, handled, dispatcher = context)
                try {
                    store.dispatch(LogAction())
                    runCurrent()
                    assertEquals(LogState(1), store.currentState)
                    assertTrue(handled.isEmpty())
                } finally {
                    store.closeAndJoin()
                }
            }
        }

    @Test
    fun delayedFailureReportingRetainsTheOriginOfEachInput() =
        runTest {
            val failure = IllegalStateException("logger failed")
            val traces = mutableListOf<StoreTrace<LogState, LogAction, LogEvent>>()
            val store = loggingStore(Logger { _, _, _ -> throw failure }, dispatcher = StandardTestDispatcher(testScheduler), trace = { traces += it })
            try {
                store.dispatch(LogAction())
                store.dispatch(LogAction())
                val inputs = traces.filterIsInstance<StoreTrace.InputAccepted<LogAction>>().filter { it.kind is InputKind.Dispatch }.map { it.input }
                assertEquals(2, inputs.size)
                assertTrue(traces.filterIsInstance<StoreTrace.FailureReported>().isEmpty())
                runCurrent()
                val reported = traces.filterIsInstance<StoreTrace.FailureReported>()
                assertEquals(listOf(inputs[0], inputs[0], inputs[0], inputs[1], inputs[1], inputs[1]), reported.map { it.input })
                reported.forEach { assertSame(failure, it.error) }
            } finally {
                store.closeAndJoin()
            }
        }

    @Test
    fun fatalInlineLoggerFailureRetainsTheStoreFailureBoundary() =
        runTest {
            val failure = object : Error("fatal logger failure") {}
            val handled = mutableListOf<Throwable>()
            val store = loggingStore(Logger { _, _, _ -> throw failure }, handled)
            try {
                store.dispatch(LogAction())
                assertEquals(LogState(0), store.currentState)
                assertSame(failure, handled.single())
            } finally {
                store.closeAndJoin()
            }
        }

    @Test
    fun inlineFailuresPreserveIdentityAndDoNotAbortActionEventOrStateCommit() =
        runTest {
            val failure = IllegalStateException("logger failed")
            val attempted = mutableListOf<String>()
            val handled = mutableListOf<Throwable>()
            val entered = mutableListOf<Int>()
            val store =
                loggingStore(
                    Logger { _, _, message ->
                        attempted += message()
                        throw failure
                    },
                    handled,
                    entered,
                )
            try {
                store.dispatch(LogAction())
                assertEquals(LogState(1), store.currentState)
                assertEquals(listOf(0), entered)
                assertEquals(expectedLines(0), attempted)
                assertEquals(3, handled.size)
                handled.forEach { assertSame(failure, it) }
            } finally {
                store.closeAndJoin()
            }
        }

    @Test
    fun actionRenderingFailureIsReportedWithoutAbortingTheHandler() =
        runTest {
            val failure = IllegalArgumentException("toString failed")
            val handled = mutableListOf<Throwable>()
            val output = mutableListOf<String>()
            val store = loggingStore(Logger { _, _, message -> output += message() }, handled)
            try {
                store.dispatch(LogAction { throw failure })
                assertEquals(LogState(1), store.currentState)
                assertSame(failure, handled.single())
                assertEquals(expectedLines(0).drop(1), output)
            } finally {
                store.closeAndJoin()
            }
        }

    @Test
    fun deferredFailuresAreReportedAfterProcessingAndDoNotStopLaterEntries() =
        runTest {
            val failure = IllegalStateException("deferred logger failed")
            val handled = mutableListOf<Throwable>()
            var attempted = 0
            val store =
                loggingStore(
                    Logger { _, _, _ ->
                        attempted++
                        throw failure
                    },
                    handled,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            try {
                store.dispatch(LogAction())
                assertEquals(LogState(1), store.currentState)
                assertEquals(0, attempted)
                assertTrue(handled.isEmpty())
                runCurrent()
                assertEquals(3, attempted)
                assertEquals(3, handled.size)
                handled.forEach { assertSame(failure, it) }
                store.dispatch(LogAction())
                runCurrent()
                assertEquals(LogState(2), store.currentState)
                assertEquals(6, attempted)
                assertEquals(6, handled.size)
            } finally {
                store.closeAndJoin()
            }
        }

    @Test
    fun deferredEntriesSurviveStateChangesAndKeepTheirCapturedStateSnapshots() =
        runTest {
            val output = mutableListOf<String>()
            val store = loggingStore(Logger { _, _, message -> output += message() }, dispatcher = StandardTestDispatcher(testScheduler))
            try {
                store.dispatch(LogAction())
                store.dispatch(LogAction())
                assertEquals(LogState(2), store.currentState)
                assertTrue(output.isEmpty())
                runCurrent()
                assertEquals(expectedLines(0) + expectedLines(1), output)
            } finally {
                store.closeAndJoin()
            }
        }

    @Test
    fun deferredEntriesSurviveExitOfTheirOriginatingStateVariant() =
        runTest {
            val output = mutableListOf<String>()
            val store: Store<ChangingState, LogAction, Nothing> =
                createRuntimeStore(ChangingState.Before) {
                    coroutineContext(UnconfinedTestDispatcher(testScheduler))
                    plugin(simpleLogging(logger = { _, _, message -> output += message() }, dispatcher = StandardTestDispatcher(testScheduler)))
                    state<ChangingState.Before> {
                        action<LogAction> { nextState { ChangingState.After } }
                    }
                    state<ChangingState.After> {}
                }
            try {
                store.dispatch(LogAction())
                assertEquals(ChangingState.After, store.currentState)
                assertTrue(output.isEmpty())
                runCurrent()
                assertEquals(listOf("Action: step", "State: After <- Before"), output)
            } finally {
                store.closeAndJoin()
            }
        }

    @Test
    fun deferredRenderingHappensWhenTheLoggerEvaluatesTheMessage() =
        runTest {
            var text = "before"
            val output = mutableListOf<String>()
            val store = loggingStore(Logger { _, _, message -> output += message() }, dispatcher = StandardTestDispatcher(testScheduler))
            try {
                store.dispatch(LogAction { text })
                text = "after"
                runCurrent()
                assertEquals("Action: after", output.first())
            } finally {
                store.closeAndJoin()
            }
        }

    @Test
    fun skippedMessagesAreNotRenderedInEitherDeliveryMode() =
        runTest {
            val handled = mutableListOf<Throwable>()
            for (context in listOf(EmptyCoroutineContext, StandardTestDispatcher(testScheduler))) {
                val store = loggingStore(Logger { _, _, _ -> }, handled, dispatcher = context)
                try {
                    store.dispatch(LogAction { error("skipped message must remain lazy") })
                    runCurrent()
                    assertEquals(LogState(1), store.currentState)
                    assertTrue(handled.isEmpty())
                } finally {
                    store.closeAndJoin()
                }
            }
        }

    @Test
    fun closeDropsPendingEntriesBeforeCallingTheLoggerOrRendering() =
        runTest {
            var attempted = 0
            val handled = mutableListOf<Throwable>()
            val store =
                loggingStore(
                    Logger { _, _, message ->
                        attempted++
                        message()
                    },
                    handled,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            store.dispatch(LogAction { error("cancelled message must not be rendered") })
            store.closeAndJoin()
            runCurrent()
            assertEquals(0, attempted)
            assertTrue(handled.isEmpty())
        }

    @Test
    fun parentCancellationAlsoDropsPendingEntries() =
        runTest {
            val parent = Job()
            var attempted = 0
            val store = loggingStore(Logger { _, _, _ -> attempted++ }, dispatcher = StandardTestDispatcher(testScheduler), parent = parent)
            try {
                store.dispatch(LogAction())
                parent.cancel()
                runCurrent()
                store.closeAndJoin()
                assertEquals(0, attempted)
            } finally {
                parent.cancel()
                store.closeAndJoin()
            }
        }

    @Test
    fun deferredEntriesCanRunInDispatcherOrderInsteadOfHookOrder() =
        runTest {
            val dispatcher = ReverseDispatcher()
            val output = mutableListOf<String>()
            val store = loggingStore(Logger { _, _, message -> output += message() }, dispatcher = dispatcher)
            try {
                store.dispatch(LogAction())
                assertEquals(3, dispatcher.pendingCount)
                dispatcher.runPendingInReverse()
                assertEquals(expectedLines(0).reversed(), output)
            } finally {
                store.close()
                dispatcher.runPendingInReverse()
                store.closeAndJoin()
            }
        }
}

private data class LogState(
    val count: Int,
) : State

private class LogAction(
    private val text: () -> String = { "step" },
) : Action {
    override fun toString(): String = text()
}

private data object LogEvent : Event {
    override fun toString(): String = "done"
}

private fun TestScope.loggingStore(
    logger: Logger,
    handled: MutableList<Throwable> = mutableListOf(),
    entered: MutableList<Int> = mutableListOf(),
    dispatcher: CoroutineContext = EmptyCoroutineContext,
    parent: CoroutineContext = EmptyCoroutineContext,
    trace: (StoreTrace<LogState, LogAction, LogEvent>) -> Unit = {},
): Store<LogState, LogAction, LogEvent> =
    createRuntimeStore(LogState(0)) {
        coroutineContext(parent + UnconfinedTestDispatcher(testScheduler))
        exceptionHandler(ExceptionHandler { handled += it })
        probe(StoreProbe { trace(it) })
        plugin(simpleLogging(logger = logger, dispatcher = dispatcher))
        state<LogState> {
            enter { entered += state.count }
            action<LogAction> {
                event(LogEvent)
                nextState { LogState(state.count + 1) }
            }
        }
    }

private fun expectedLines(previous: Int): List<String> =
    listOf(
        "Action: step",
        "Event: done",
        "State: LogState(count=${previous + 1}) <- LogState(count=$previous)",
    )

private class ReverseDispatcher : CoroutineDispatcher() {
    private val pending = mutableListOf<Runnable>()
    val pendingCount: Int get() = pending.size

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        pending += block
    }

    fun runPendingInReverse() {
        val ready = pending.toList()
        pending.clear()
        ready.asReversed().forEach { it.run() }
    }
}

private sealed interface ChangingState : State {
    data object Before : ChangingState

    data object After : ChangingState
}
