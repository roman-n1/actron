@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.Plugin
import actron.core.State
import actron.core.Store
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.test.patch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreLifecycleAdapterTest {
    private data object Ping : Action

    private data object Note : Event

    private val node = StateId("active")

    private fun machine(
        name: String,
        work: Boolean,
    ) = Machine<Int, Ping, String, Note>(
        DefinitionId(name),
        DefinitionVersion("1"),
        StateChartDefinition(node, listOf(AtomicState(node)), emptyList()),
    ) {
        onEnter(node) { if (work) command("work") }
        onAction(node, ActionMatcher.of<Ping>("ping")) {
            context++
            event(Note)
        }
    }

    @Test
    fun machineSupportsLifecycleWithConfiguredProcessingContext() = checkAdapter(invoked = false, patchContext = false)

    @Test
    fun machineKeepsOwnershipAfterContextPatch() = checkAdapter(invoked = false, patchContext = true)

    @Test
    fun invocationSupportsLifecycle() = checkAdapter(invoked = true, patchContext = false)

    private fun checkAdapter(
        invoked: Boolean,
        patchContext: Boolean,
    ) = runTest {
        val closeAttempt = CompletableDeferred<suspend () -> Unit>()
        val started = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handler =
            CommandHandler<String, Ping> { _, _ ->
                assertFailsWith<IllegalStateException> { closeAttempt.await().invoke() }
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanup.complete(Unit)
                        release.await()
                    }
                }
            }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parent = machine("parent", work = true)
        val regular =
            MachineStore(parent, 0, handler, backgroundScope, coroutineContext = dispatcher) {
                coroutineContext(dispatcher)
                plugin(Plugin(onStart = { assertFailsWith<IllegalStateException> { closeAttempt.await().invoke() } }))
            }
        val store =
            if (invoked) {
                regular.close()
                InvokedMachineStore(InvokedMachine(parent, node, machine("child", work = false), { it.context }), 0, handler, handler, backgroundScope, coroutineContext = dispatcher)
            } else {
                regular
            }
        closeAttempt.complete { store.closeAndJoin() }
        try {
            if (patchContext) regular.patch { coroutineContext(dispatcher) }
            checkObservation(store)
            assertTrue(started.isCompleted)
            val closing = async { store.closeAndJoin() }
            cleanup.await()
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            closing.await()
            store.closeAndJoin()
        } finally {
            release.complete(Unit)
            store.close()
        }
    }

    private suspend fun <S : State> kotlinx.coroutines.test.TestScope.checkObservation(store: Store<S, Ping, Note>) {
        val states = mutableListOf<S>()
        val events = mutableListOf<Note>()
        val stateHandle = store.observeState { states += it }
        val eventHandle = store.observeEvent { events += it }
        runCurrent()
        store.start()
        runCurrent()
        store.dispatch(Ping)
        runCurrent()
        assertEquals(store.currentState, states.last())
        assertEquals(listOf(Note), events)
        val count = states.size
        stateHandle.close()
        eventHandle.close()
        runCurrent()
        store.dispatch(Ping)
        runCurrent()
        assertEquals(count, states.size)
        assertEquals(listOf(Note), events)
    }
}
