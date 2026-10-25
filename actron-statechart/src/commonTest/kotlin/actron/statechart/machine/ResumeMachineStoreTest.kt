@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ResumeMachineStoreTest {
    private data object Tick : Action

    private data object Empty : Event

    private val loading = StateId("Loading")
    private val done = StateId("Done")

    private fun machine() =
        stateMachine<Int, Tick, String, Empty>(DefinitionId("resume"), DefinitionVersion("1"), loading) {
            state(loading) {
                onEnter {
                    context += 1
                    command("first", LaneId("queue"), ConcurrencyPolicy.Sequential)
                    command("second", LaneId("queue"), ConcurrencyPolicy.Sequential)
                }
                internal<Tick> { context, _ -> context + 1 }
                after(10.seconds, done)
            }
            state(done) { }
        }

    @Test
    fun restoresConfigurationWithoutEntryEffectsAndKeepsQueueAndRemainingTimer() =
        runTest {
            val machine = machine()
            val decision = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero))
            var lanes = Lanes<String>()
            decision.commands.forEach { lanes = lanes.admit(it).lanes }
            val checkpoint = ExecutorCheckpoint(decision.snapshot, MachineTime(4.seconds), lanes)
            val completed = CompletableDeferred<Unit>()
            val executed = mutableListOf<String>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val store =
                resumeMachineStore(
                    machine,
                    checkpoint,
                    CommandHandler { command, _ ->
                        executed += command.command
                        if (command.command == "first") completed.await()
                    },
                    scope,
                    clock = MachineClock.monotonic(testScheduler.timeSource),
                )
            try {
                store.start()
                testScheduler.runCurrent()
                assertEquals(checkpoint.snapshot, store.currentState)
                assertEquals(listOf("first"), executed)
                completed.complete(Unit)
                testScheduler.runCurrent()
                assertEquals(listOf("first", "second"), executed)
                store.dispatch(Tick)
                testScheduler.runCurrent()
                assertEquals(2, store.currentState.context)
                testScheduler.advanceTimeBy(5999)
                testScheduler.runCurrent()
                assertTrue(store.currentState.isActive(loading))
                testScheduler.advanceTimeBy(1)
                testScheduler.runCurrent()
                assertTrue(store.currentState.isActive(done))
                assertEquals(1, checkpoint.snapshot.context, "Original checkpoint stays immutable")
            } finally {
                store.close()
                scope.cancel()
            }
        }

    @Test
    fun refusesUnknownTerminalOutcomesAndForeignDefinitions() =
        runTest {
            val machine = machine()
            val decision = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero))
            val ending = decision.commands.associateBy { it.id }
            val checkpoint = ExecutorCheckpoint(decision.snapshot, MachineTime.Zero, ending = ending)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                assertFailsWith<IllegalArgumentException> { resumeMachineStore(machine, checkpoint, CommandHandler { _, _ -> }, scope) }
                assertFailsWith<IllegalArgumentException> {
                    resumeMachineStore(machine, checkpoint.copy(snapshot = checkpoint.snapshot.copy(definition = DefinitionId("foreign"))), CommandHandler { _, _ -> }, scope)
                }
            } finally {
                scope.cancel()
            }
        }
}
