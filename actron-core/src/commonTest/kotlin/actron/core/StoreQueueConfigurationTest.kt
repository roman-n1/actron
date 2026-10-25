@file:OptIn(ExperimentalCoroutinesApi::class, InternalActronApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ready --work/clear/patch--> Ready. Configuration validation precedes every patch effect. */
class StoreQueueConfigurationTest {
    private data class Ready(
        val entries: List<String> = emptyList(),
    ) : State

    private sealed interface Command : Action {
        data object Spawn : Command

        data object Clear : Command

        data object Fail : Command

        data class Append(
            val value: String,
        ) : Command
    }

    @Test
    fun successorDoesNotWaitForACompletedHandlersChildCoroutine() =
        runTest {
            val childFinish = CompletableDeferred<Unit>()
            val childStarted = CompletableDeferred<Unit>()
            val store =
                createRuntimeStore<Ready, Command, Nothing>(Ready()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    state<Ready> {
                        action<Command.Spawn> {
                            CoroutineScope(currentCoroutineContext()).launch {
                                childStarted.complete(Unit)
                                childFinish.await()
                            }
                        }
                        action<Command.Append> { nextState { Ready(state.entries + action.value) } }
                    }
                }
            try {
                store.dispatch(Command.Spawn)
                runCurrent()
                assertTrue(childStarted.isCompleted)
                assertFalse(childFinish.isCompleted)
                store.dispatch(Command.Append("successor"))
                runCurrent()
                assertEquals(Ready(listOf("successor")), store.currentState)
            } finally {
                childFinish.complete(Unit)
                store.close()
            }
        }

    @Test
    fun clearDiscardsQueuedActionsButKeepsTheHandlerAndDispatchesFromItsCommit() =
        runTest {
            val proceed = CompletableDeferred<Unit>()
            val traces = mutableListOf<StoreTrace<Ready, Command, Nothing>>()
            val store =
                createRuntimeStore<Ready, Command, Nothing>(Ready()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    probe(StoreProbe { traces += it })
                    plugin(
                        Plugin(onState = { _, next ->
                            if (next.entries == listOf("handler")) dispatch(Command.Append("from-plugin"))
                        }),
                    )
                    state<Ready> {
                        action<Command.Clear> {
                            proceed.await()
                            clearPendingActions()
                            nextState { Ready(listOf("handler")) }
                        }
                        action<Command.Append> { nextState { Ready(state.entries + action.value) } }
                    }
                }
            try {
                store.dispatch(Command.Clear)
                runCurrent()
                store.dispatch(Command.Append("discard-one"))
                store.dispatch(Command.Append("discard-two"))
                proceed.complete(Unit)
                runCurrent()
                store.dispatchAndAwaitForTest(Command.Append("outside"))
                assertEquals(Ready(listOf("handler", "from-plugin", "outside")), store.currentState)
                val discarded = traces.filterIsInstance<StoreTrace.InputDiscarded>()
                assertEquals(2, discarded.size)
                assertEquals(listOf(DiscardReason.ClearedExplicitly, DiscardReason.ClearedExplicitly), discarded.map { it.reason })
            } finally {
                store.close()
            }
        }

    @Test
    fun rejectedMixedPatchAppliesNeitherSettingsNorPlugins() =
        runTest {
            val originalFailures = mutableListOf<Throwable>()
            val replacementFailures = mutableListOf<Throwable>()
            val pluginCalls = mutableListOf<String>()
            val store =
                createRuntimeStore<Ready, Command, Nothing>(Ready()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    exceptionHandler(ExceptionHandler { originalFailures += it })
                    plugin(Plugin(onAction = { _, _ -> pluginCalls += "original" }))
                    state<Ready> { action<Command.Fail> { error("handler failure") } }
                }
            try {
                assertEquals(Ready(), store.currentState)
                assertFailsWith<IllegalStateException> {
                    store.patchForTest {
                        exceptionHandler(ExceptionHandler { replacementFailures += it })
                        plugin(Plugin(onAction = { _, _ -> pluginCalls += "replacement" }))
                        stateSaver(StateSaver.Noop())
                    }
                }
                store.dispatchAndAwaitForTest(Command.Fail)
                assertEquals(listOf("original"), pluginCalls)
                assertEquals(1, originalFailures.size)
                assertEquals("handler failure", originalFailures.single().message)
                assertTrue(replacementFailures.isEmpty())
            } finally {
                store.close()
            }
        }

    @Test
    fun collectingEventsConsumesTheContextButLeavesInitialStatePatchable() =
        runTest {
            val enters = mutableListOf<Ready>()
            val store =
                createRuntimeStore<Ready, Command, Nothing>(Ready()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    state<Ready> { enter { enters += state } }
                }
            try {
                store.collectEvent { }
                runCurrent()
                assertTrue(enters.isEmpty())
                assertFailsWith<IllegalStateException> {
                    store.patchForTest { coroutineContext(StandardTestDispatcher(testScheduler)) }
                }
                store.patchForTest { initialState(Ready(listOf("patched"))) }
                store.startAndAwaitForTest()
                assertEquals(listOf(Ready(listOf("patched"))), enters)
            } finally {
                store.close()
            }
        }
}
