@file:OptIn(actron.core.InternalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.core.InputKind
import actron.core.InternalActronApi
import actron.core.ProcessingOutcome
import actron.core.StoreTrace
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MachineProfilerTest {
    sealed interface Act : Action {
        data object Go : Act

        data object Stop : Act
    }

    sealed interface Ev : Event

    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val machine =
        Machine<Unit, Act, Int, Ev>(
            DefinitionId("profile"),
            DefinitionVersion("1"),
            StateChartDefinition(
                idle,
                listOf(AtomicState(idle), AtomicState(loading)),
                listOf(Transition(idle, loading, ActionMatcher.of<Act.Go>("Go")), Transition(loading, idle, ActionMatcher.of<Act.Stop>("Stop"))),
            ),
        ) { onEnter(loading) { command(1) } }

    @Test fun separatesQueueProcessingResidenceAndCommandLifetime() =
        runTest {
            var time = 0L
            val profile = MachineProfiler(machine, now = { time.milliseconds })
            val initial = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero))
            profile.onCommitted(InputId(0), MachineInput.Start(MachineTime.Zero), initial)
            time = 2
            profile.probe.record(StoreTrace.InputAccepted(InputId(1), InputKind.Dispatch(MachineInput.Dispatch(Act.Go, MachineTime.Zero))))
            time = 7
            profile.probe.record(StoreTrace.ProcessingStarted(InputId(1), 1))
            val explained = machine.decideExplained(initial.snapshot, MachineInput.Dispatch(Act.Go, MachineTime.Zero))
            profile.onDecided(InputId(1), MachineInput.Dispatch(Act.Go, MachineTime.Zero), explained)
            val go = explained.decision
            profile.onCommitted(InputId(1), MachineInput.Dispatch(Act.Go, MachineTime.Zero), go)
            time = 10
            profile.probe.record(StoreTrace.ProcessingFinished(InputId(1), 1, ProcessingOutcome.Handled(1)))
            val frozen = profile.snapshot()
            assertEquals(
                5.milliseconds,
                frozen.transitions
                    .getValue(TransitionId(0))
                    .queue.average,
            )
            assertEquals(
                3.milliseconds,
                frozen.transitions
                    .getValue(TransitionId(0))
                    .processing.average,
            )
            val registration = go.commands.single()
            val wrapped = profile.commands(CommandHandler<Int, Act> { _, _ -> time = 24 })
            time = 14
            wrapped.execute(
                CommandEnvelope(registration.id, registration.command, registration.scope),
                object : ResultSink<Act> {
                    override fun result(action: Act) = Unit
                },
            )
            val data = profile.snapshot().states.getValue(loading)
            assertEquals(7.milliseconds, data.commandQueue.average)
            assertEquals(10.milliseconds, data.commandExecution.average)
            assertEquals(
                0L,
                frozen.states
                    .getValue(loading)
                    .commandExecution.samples,
            )
            val stop = machine.decide(go.snapshot, MachineInput.Dispatch(Act.Stop, MachineTime.Zero))
            profile.onCommitted(InputId(2), MachineInput.Dispatch(Act.Stop, MachineTime.Zero), stop)
            assertEquals(
                17.milliseconds,
                profile
                    .snapshot()
                    .states
                    .getValue(loading)
                    .residence.total,
            )
        }

    @Test fun actualExecutorIsObservedAndCancellationStillPropagates() =
        runTest {
            val profile = MachineProfiler(machine, now = { testScheduler.currentTime.milliseconds })
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            val handler = profile.commands(CommandHandler<Int, Act> { _, _ -> awaitCancellation() })
            val store = MachineStore(machine, Unit, handler, scope, coroutineContext = scope.coroutineContext, observers = listOf(profile)) { profileWith(profile) }
            try {
                store.dispatch(Act.Go)
                runCurrent()
                advanceTimeBy(25)
                store.dispatch(Act.Stop)
                runCurrent()
                val snapshot = profile.snapshot()
                assertEquals(1L, snapshot.transitions.getValue(TransitionId(0)).hits)
                assertEquals(
                    1L,
                    snapshot.transitions
                        .getValue(TransitionId(0))
                        .processing.samples,
                )
                assertEquals(
                    25.milliseconds,
                    snapshot.states
                        .getValue(loading)
                        .commandExecution.total,
                )
                assertEquals(1L, snapshot.states.getValue(loading).cancelledCommands)
            } finally {
                store.close()
                scope.cancel()
            }
        }

    @Test fun boundedCorrelationsNeverFabricateZeroTimings() {
        val profile = MachineProfiler(machine, capacity = 1, now = { 0.milliseconds })
        repeat(3) { profile.probe.record(StoreTrace.InputAccepted(InputId(it.toLong()), InputKind.Startup)) }
        profile.probe.record(StoreTrace.ProcessingStarted(InputId(0), 0))
        profile.probe.record(StoreTrace.ProcessingFinished(InputId(0), 0, ProcessingOutcome.Handled(1)))
        assertEquals(2L, profile.snapshot().droppedCorrelations)
        assertTrue(profile.snapshot().transitions.isEmpty())
    }

    @Test fun restoredExecutorStartsANewMeasurementWindowWithoutReplayingVisits() =
        runTest {
            val initial = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero))
            val loaded = machine.decide(initial.snapshot, MachineInput.Dispatch(Act.Go, MachineTime.Zero))
            var lanes = Lanes<Int>()
            loaded.commands.forEach { lanes = lanes.admit(it).lanes }
            val checkpoint = ExecutorCheckpoint(loaded.snapshot, MachineTime(100.milliseconds), lanes)
            val profile = MachineProfiler(machine, now = { testScheduler.currentTime.milliseconds })
            profile.beginAt(checkpoint.snapshot)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val store =
                resumeMachineStore(
                    machine,
                    checkpoint,
                    profile.commands(CommandHandler<Int, Act> { _, _ -> awaitCancellation() }),
                    scope,
                    observers = listOf(profile),
                ) { profileWith(profile) }
            try {
                store.start()
                runCurrent()
                advanceTimeBy(15)
                store.dispatch(Act.Stop)
                runCurrent()
                val measured = profile.snapshot()
                assertEquals(0L, measured.states.getValue(loading).visits)
                assertEquals(
                    15.milliseconds,
                    measured.states
                        .getValue(loading)
                        .residence.total,
                )
                assertEquals(
                    15.milliseconds,
                    measured.states
                        .getValue(loading)
                        .commandExecution.total,
                )
                assertEquals(1L, measured.states.getValue(loading).cancelledCommands)
                assertEquals(
                    1L,
                    measured.transitions
                        .getValue(TransitionId(1))
                        .processing.samples,
                )
                assertFalse(TransitionId(0) in measured.transitions, "Past transitions are not measured again")
            } finally {
                store.close()
                scope.cancel()
            }
        }

    @Test fun fastCommandBeforeCommitNotificationIsStillMeasuredOnce() =
        runTest {
            var time = 0L
            val profile = MachineProfiler(machine, now = { time.milliseconds })
            val initial = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero))
            val input = MachineInput.Dispatch(Act.Go, MachineTime.Zero)
            val explained = machine.decideExplained(initial.snapshot, input)
            profile.onDecided(InputId(1), input, explained)
            val command = explained.decision.commands.single()
            time = 2
            profile.commands(CommandHandler<Int, Act> { _, _ -> time = 5 }).execute(
                CommandEnvelope(command.id, command.command, command.scope),
                object : ResultSink<Act> {
                    override fun result(action: Act) = Unit
                },
            )
            profile.onCommitted(InputId(1), input, explained.decision)
            val measured = profile.snapshot().states.getValue(loading)
            assertEquals(2.milliseconds, measured.commandQueue.total)
            assertEquals(3.milliseconds, measured.commandExecution.total)
            assertEquals(1L, measured.commandExecution.samples)
        }
}
