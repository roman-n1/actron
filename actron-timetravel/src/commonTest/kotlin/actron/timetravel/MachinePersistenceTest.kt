@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.machine.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class MachinePersistenceTest {
    @Serializable private data object Tick : Action

    @Serializable private data object Empty : Event

    private val loading = StateId("Loading")
    private val done = StateId("Done")

    private fun machine(version: String = "1") =
        stateMachine<Int, Tick, String, Empty>(DefinitionId("persistent"), DefinitionVersion(version), loading) {
            state(loading) {
                onEnter {
                    context += 1
                    command("first", LaneId("queue"), ConcurrencyPolicy.Sequential)
                    command("second", LaneId("queue"), ConcurrencyPolicy.Sequential)
                }
                internal<Tick> { value, _ -> value + 1 }
                after(10.seconds, done)
            }
            state(done) { }
        }

    private fun checkpoint(): ExecutorCheckpoint<Int, String> {
        val machine = machine()
        val decision = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero))
        var lanes = Lanes<String>()
        decision.commands.forEach { lanes = lanes.admit(it).lanes }
        return ExecutorCheckpoint(decision.snapshot, MachineTime(4.seconds), lanes)
    }

    @Test
    fun restoresQueuedCommandsTimerAndContextWithoutReentering() =
        runTest {
            val persistence = machinePersistence(machine())
            val saved = persistence.save(checkpoint())
            assertEquals(checkpoint(), persistence.restore(saved))
            val completed = CompletableDeferred<Unit>()
            val executed = mutableListOf<String>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val store =
                persistence.resume(
                    saved,
                    CommandHandler { command, _ ->
                        executed += command.command
                        if (command.command == "first") completed.await()
                    },
                    scope,
                    clock = MachineClock.monotonic(testScheduler.timeSource),
                )
            try {
                assertTrue(executed.isEmpty(), "Resume must not start work until start")
                store.start()
                testScheduler.runCurrent()
                assertEquals(1, store.currentState.context, "Entry hook must not repeat")
                assertEquals(listOf("first"), executed)
                completed.complete(Unit)
                testScheduler.runCurrent()
                assertEquals(listOf("first", "second"), executed)
                store.dispatch(Tick)
                testScheduler.runCurrent()
                val again = persistence.restore(persistence.save(store))
                assertEquals(2, again.snapshot.context)
                assertTrue(again.registrations.isEmpty())
                testScheduler.advanceTimeBy(5999)
                testScheduler.runCurrent()
                assertTrue(store.currentState.isActive(loading))
                testScheduler.advanceTimeBy(1)
                testScheduler.runCurrent()
                assertTrue(store.currentState.isActive(done))
            } finally {
                store.close()
                scope.cancel()
            }
        }

    @Test
    fun completedCommandsAreNotRepeatedAfterSecondRestore() =
        runTest {
            val persistence = machinePersistence(machine())
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var calls = 0
            val handler = CommandHandler<String, Tick> { _, _ -> calls++ }
            val first = persistence.resume(persistence.save(checkpoint()), handler, scope)
            first.start()
            testScheduler.runCurrent()
            val saved = persistence.save(first)
            first.close()
            val second = persistence.resume(saved, handler, scope)
            try {
                second.start()
                testScheduler.runCurrent()
                assertEquals(2, calls)
                assertEquals(first.currentState.context, second.currentState.context)
            } finally {
                second.close()
                scope.cancel()
            }
        }

    @Test
    fun rejectsCorruptUnsupportedAndForeignDataAndUndeliveredOutcomes() {
        val persistence = machinePersistence(machine())
        val cp = checkpoint()
        val saved = persistence.save(cp)
        assertFailsWith<IllegalArgumentException> { persistence.restore("broken") }
        assertFailsWith<IllegalArgumentException> { machinePersistence(machine("2")).restore(saved) }
        assertFailsWith<IllegalArgumentException> { persistence.restore(saved.replace("\"persistent\"", "\"other\"")) }
        assertFailsWith<IllegalArgumentException> { persistence.restore(saved.replace(Regex("\"formatVersion\":\\d+"), "\"formatVersion\":999")) }
        assertFailsWith<IllegalArgumentException> { persistence.save(cp.copy(snapshot = cp.snapshot.copy(definition = DefinitionId("other")))) }
        assertFailsWith<IllegalArgumentException> { persistence.save(cp.copy(lanes = Lanes(), ending = cp.registrations)) }
        assertFailsWith<IllegalArgumentException> {
            persistence.save(cp.copy(effects = listOf(PendingEffect(EffectId(1), Empty, EffectPolicy.Retained(), 0, false))))
        }
        val codec = RecordingCodec(Int.serializer(), Tick.serializer(), String.serializer(), Empty.serializer())
        val recording =
            Recording(
                machine().id,
                machine().version,
                cp,
                listOf<RecordedStep<Int, Tick, String, Empty>>(
                    RecordedStep.Committed(MachineInput.Dispatch(Tick, cp.now), machine().decide(cp.snapshot, MachineInput.Dispatch(Tick, cp.now))),
                ),
            )
        assertFailsWith<IllegalArgumentException> { persistence.restore(codec.encode(recording)) }
    }

    private fun worker() =
        stateMachine<Int, Tick, String, Empty>(DefinitionId("worker"), DefinitionVersion("1"), loading) {
            state(loading) {
                onEnter { command("work", LaneId("worker"), ConcurrencyPolicy.Latest) }
                internal<Tick> { value, _ -> value + 1 }
            }
        }

    @Test
    fun unfinishedComputationRestartsRatherThanResumingCoroutineStack() =
        runTest {
            val persistence = machinePersistence(worker())
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var started = 0
            val handler =
                CommandHandler<String, Tick> { _, results ->
                    started++
                    delay(1000)
                    results.result(Tick)
                }
            val first = persistence.resume(persistence.save(ExecutorCheckpoint.initial(worker().initialSnapshot(0))), handler, scope)
            first.start()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(400)
            val saved = persistence.save(first)
            first.close()
            testScheduler.runCurrent()
            val second = persistence.resume(saved, handler, scope)
            try {
                second.start()
                testScheduler.runCurrent()
                assertEquals(2, started)
                testScheduler.advanceTimeBy(600)
                testScheduler.runCurrent()
                assertEquals(0, second.currentState.context, "The new computation needs its full duration")
                testScheduler.advanceTimeBy(400)
                testScheduler.runCurrent()
                assertEquals(1, second.currentState.context)
            } finally {
                second.close()
                scope.cancel()
            }
        }

    @Test
    fun subscriptionReopensAndReceivesCurrentValueAndFutureUpdates() =
        runTest {
            val source = MutableStateFlow(10)
            var active = 0
            var starts = 0
            val persistence = machinePersistence(worker())
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val handler =
                CommandHandler<String, Tick> { _, results ->
                    starts++
                    active++
                    try {
                        source.collect { results.result(Tick) }
                    } finally {
                        active--
                    }
                }
            val first = persistence.resume(persistence.save(ExecutorCheckpoint.initial(worker().initialSnapshot(0))), handler, scope)
            first.start()
            testScheduler.runCurrent()
            assertEquals(1, first.currentState.context)
            val saved = persistence.save(first)
            first.close()
            testScheduler.runCurrent()
            assertEquals(0, active)
            source.value = 20
            val second = persistence.resume(saved, handler, scope)
            try {
                second.start()
                testScheduler.runCurrent()
                assertEquals(2, starts)
                assertEquals(1, active)
                assertEquals(2, second.currentState.context, "Restored state plus the source's current value")
                source.value = 30
                testScheduler.runCurrent()
                assertEquals(3, second.currentState.context)
            } finally {
                second.close()
                scope.cancel()
                testScheduler.runCurrent()
            }
            assertEquals(0, active)
        }

    @Test
    fun freshCheckpointStartsNormally() =
        runTest {
            val machine = machine()
            val persistence = machinePersistence(machine)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val store = persistence.resume(persistence.save(ExecutorCheckpoint.initial(machine.initialSnapshot(8))), CommandHandler { _, _ -> }, scope)
            try {
                assertFalse(store.currentState.isStarted)
                store.start()
                testScheduler.runCurrent()
                assertEquals(9, store.currentState.context)
            } finally {
                store.close()
                scope.cancel()
            }
        }
}
