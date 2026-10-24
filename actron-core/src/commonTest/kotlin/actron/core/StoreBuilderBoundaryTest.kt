@file:OptIn(InternalActronApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Building freezes the definition; runtime patches apply only to the Store's own collections. */
class StoreBuilderBoundaryTest {
    private data class Counter(
        val value: Int = 0,
    ) : State

    private sealed interface Command : Action {
        data object Increment : Command

        data object Late : Command

        data object Fail : Command
    }

    @Test
    fun escapedBuilderCannotAddHandlersAfterBuildOrChangeRoutingDiagnostics() =
        runTest {
            val escaped = CompletableDeferred<StoreBuilder<Counter, Command, Nothing>>()
            val traces = mutableListOf<StoreTrace<Counter, Command, Nothing>>()
            val store =
                Store<Counter, Command, Nothing>(Counter()) {
                    escaped.complete(this)
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    probe(StoreProbe { traces += it })
                    state<Counter> { action<Command.Increment> { nextState { Counter(state.value + 1) } } }
                }
            try {
                escaped.await().state<Counter> { action<Command.Late> { nextState { Counter(999) } } }
                store.dispatchAndAwaitForTest(Command.Late)
                assertEquals(Counter(), store.currentState)
                val api = store as StoreInternalApi<Counter, Command, Nothing>
                assertEquals(1, api.handlerMetadata().action.size)
                assertTrue(api.matchActionHandlers(Counter(), Command.Late).none { it.matches })
                assertEquals(ProcessingOutcome.Ignored, traces.filterIsInstance<StoreTrace.ProcessingFinished>().last().outcome)
                store.dispatchAndAwaitForTest(Command.Increment)
                assertEquals(Counter(1), store.currentState)
            } finally {
                store.close()
            }
        }

    @Test
    fun builderCollectionsAreDetachedButRuntimePatchesStillReachPluginsAndProbes() =
        runTest {
            val escaped = CompletableDeferred<StoreBuilder<Counter, Command, Nothing>>()
            val hooks = mutableListOf<String>()
            val lateTraces = mutableListOf<StoreTrace<Counter, Command, Nothing>>()
            val patchedTraces = mutableListOf<StoreTrace<Counter, Command, Nothing>>()
            val reported = mutableListOf<Throwable>()
            val store =
                Store<Counter, Command, Nothing>(Counter()) {
                    escaped.complete(this)
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    exceptionHandler(ExceptionHandler { reported += it })
                    plugin(Plugin(onAction = { _, _ -> hooks += "initial" }))
                    validateRecovery { _, _ -> hooks += "initial validator" }
                    state<Counter> {
                        action<Command.Fail> { error("original") }
                        recover<IllegalStateException> { nextState { Counter(5) } }
                    }
                }
            try {
                escaped.await().apply {
                    plugin(Plugin(onAction = { _, _ -> hooks += "late" }))
                    probe(StoreProbe { lateTraces += it })
                    validateRecovery { _, _ -> error("late validator") }
                }
                store.patchForTest {
                    plugin(Plugin(onAction = { _, _ -> hooks += "patched" }))
                    probe(StoreProbe { patchedTraces += it })
                }
                store.dispatchAndAwaitForTest(Command.Fail)
                assertEquals(Counter(5), store.currentState)
                assertEquals(listOf("initial", "patched", "initial validator"), hooks)
                assertTrue(lateTraces.isEmpty())
                assertTrue(patchedTraces.any { it is StoreTrace.StateCommitted })
                assertTrue(reported.isEmpty())
            } finally {
                store.close()
            }
        }
}
