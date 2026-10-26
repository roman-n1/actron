package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.statechart.StateId
import actron.statechart.machine.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class BoundedMachineRecorderTest {
    @Serializable
    private sealed interface Act : Action {
        @Serializable
        data object Go : Act

        @Serializable
        data object Tick : Act

        @Serializable
        data object Boom : Act

        @Serializable
        data object Unknown : Act
    }

    @Serializable
    private sealed interface Ev : Event

    private val idle = StateId("Idle")
    private val busy = StateId("Busy")
    private val machine =
        stateMachine<Int, Act, String, Ev>(DefinitionId("bounded"), DefinitionVersion("1"), idle) {
            state(idle) { on<Act.Go>(busy) }
            state(busy) {
                onEnter {
                    command("first", LaneId("queue"), ConcurrencyPolicy.Sequential)
                    command("second", LaneId("queue"), ConcurrencyPolicy.Sequential)
                    command("old", LaneId("latest"), ConcurrencyPolicy.Latest)
                    command("new", LaneId("latest"), ConcurrencyPolicy.Latest)
                }
                internal<Act.Tick> { value, _ -> value + 1 }
                internal<Act.Boom>(guard = { _, _ -> error("expected failure") }) { value, _ -> value }
                after(5.seconds, idle)
            }
        }

    private fun record(
        observer: DecisionObserver<Int, Act, String, Ev>,
        input: MachineInput<Act>,
        decision: Decision<Int, String, Ev>,
    ) {
        when (val outcome = decision.outcome) {
            DecisionOutcome.Handled -> observer.onCommitted(InputAttribution.Unattributed, input, decision)
            is DecisionOutcome.Ignored -> observer.onIgnored(InputAttribution.Unattributed, input, outcome.reason)
            is DecisionOutcome.Failed -> observer.onFailed(InputAttribution.Unattributed, input, outcome.failure)
        }
    }

    @Test
    fun everyEvictedWindowReplaysAndBranchesWithCommandsQueuesAndTimers() {
        for (capacity in listOf(1, 2, 5)) {
            val bounded = BoundedMachineRecorder(machine, 0, capacity)
            val full = MachineRecorder(machine, 0)
            var snapshot = machine.initialSnapshot(0)

            fun send(input: MachineInput<Act>) {
                val decision = machine.decide(snapshot, input)
                record(full, input, decision)
                record(bounded, input, decision)
                snapshot = decision.snapshot
                val window = bounded.capture()
                assertTrue(window.recording.length <= capacity)
                val original = full.recording()
                assertEquals(original.checkpointAt(window.firstStep.toInt()), window.recording.start)
                assertEquals(original.checkpointAt(original.length), window.recording.checkpointAt(window.recording.length))
                assertFalse(ReplaySession(machine, window.recording).verify { fail(it.describe()) })
                val codec = RecordingCodec(Int.serializer(), Act.serializer(), String.serializer(), Ev.serializer())
                val decoded = assertIs<DecodedRecording.Decoded<Int, Act, String, Ev>>(codec.decode(codec.encode(window.recording)))
                assertFalse(ReplaySession(machine, decoded.recording).verify { fail(it.describe()) })
                assertEquals(window.recording.start, decoded.recording.start)
            }
            send(MachineInput.Start(MachineTime.Zero))
            send(MachineInput.Dispatch(Act.Go, MachineTime.Zero))
            repeat(12) { send(MachineInput.Dispatch(Act.Tick, MachineTime.Zero)) }
            val captured = bounded.capture()
            val replay = ReplaySession(machine, captured.recording)
            val branch = replay.branch()
            assertTrue(branch.awaiting.isNotEmpty())
            assertTrue(branch.queued.isNotEmpty())
            assertTrue(
                branch.checkpoint.snapshot.timers
                    .isNotEmpty(),
            )
            val first = branch.awaiting.first { it.command == "first" }
            branch.complete(first.id)
            assertTrue(branch.awaiting.any { it.command == "second" })
            send(MachineInput.Dispatch(Act.Unknown, MachineTime.Zero))
            send(MachineInput.Dispatch(Act.Boom, MachineTime.Zero))
            send(MachineInput.TimerFired(snapshot.timers.keys.single(), MachineTime(5.seconds)))
            assertFalse(ReplaySession(machine, captured.recording).verify { fail(it.describe()) }, "Previously captured windows must not change")
        }
    }

    @Test
    fun clearAndExplicitDiscardPreserveCheckpointAndAbsoluteIndices() {
        val recorder = BoundedMachineRecorder(machine, 0, 2)
        var snapshot = machine.initialSnapshot(0)
        for (input in listOf(MachineInput.Start(MachineTime.Zero), MachineInput.Dispatch(Act.Go, MachineTime.Zero))) {
            val decision = machine.decide(snapshot, input)
            record(recorder, input, decision)
            snapshot = decision.snapshot
        }
        val end = recorder.recording().checkpointAt(2)
        recorder.discardBefore(1)
        assertEquals(1, recorder.capture().recording.length)
        recorder.clear()
        assertEquals(2L, recorder.capture().firstStep)
        assertEquals(end, recorder.capture().recording.start)
        val input = MachineInput.Dispatch(Act.Tick, MachineTime.Zero)
        record(recorder, input, machine.decide(snapshot, input))
        assertEquals(3L, recorder.nextStep)
        assertFalse(ReplaySession(machine, recorder.recording()).verify { fail(it.describe()) })
        assertFailsWith<IllegalArgumentException> { recorder.discardBefore(4) }
        assertFailsWith<IllegalArgumentException> { BoundedMachineRecorder(machine, 0, 0) }
        val resumed = BoundedMachineRecorder(machine, end, 1)
        record(resumed, input, machine.decide(snapshot, input))
        assertFalse(ReplaySession(machine, resumed.recording()).verify { fail(it.describe()) })
    }

    @Test
    fun unknownOriginRemainsVisibleAfterEviction() {
        val recorder = BoundedMachineRecorder(machine, 0, 1)
        val input = MachineInput.Start(MachineTime.Zero)
        val decision = machine.decide(machine.initialSnapshot(42), input)
        record(recorder, input, decision)
        assertIs<RecordingOrigin.UnknownBeginning>(recorder.capture().origin)
        val next = MachineInput.Dispatch(Act.Unknown, MachineTime.Zero)
        record(recorder, next, machine.decide(decision.snapshot, next))
        assertIs<RecordingOrigin.UnknownBeginning>(recorder.capture().origin)
    }
}
