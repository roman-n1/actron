@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.machine.stateMachine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RecordingSnapshotTest {
    @Serializable private data object Tick : Action

    @Serializable private data object Empty : Event

    private val idle = StateId("Idle")
    private val machine =
        stateMachine<Int, Tick, String, Empty>(DefinitionId("snapshot"), DefinitionVersion("1"), idle) {
            state(idle) { internal<Tick> { value, _ -> value + 1 } }
        }
    private val initial = machine.initialSnapshot(0)
    private val codec = RecordingCodec(Int.serializer(), Tick.serializer(), String.serializer(), Empty.serializer())

    private fun steps(): List<RecordedStep<Int, Tick, String, Empty>> {
        val start = MachineInput.Start(MachineTime.Zero)
        val started = machine.decide(initial, start)
        val tick = MachineInput.Dispatch(Tick, MachineTime.Zero)
        return listOf(RecordedStep.Committed(start, started), RecordedStep.Committed(tick, machine.decide(started.snapshot, tick)))
    }

    private fun recordings(source: List<RecordedStep<Int, Tick, String, Empty>>): List<Recording<Int, Tick, String, Empty>> =
        listOf(
            Recording(machine.id, machine.version, start = ExecutorCheckpoint.initial<Int, String>(initial), steps = source),
            Recording(machine.id, machine.version, initial = initial, steps = source),
        )

    @Test
    fun callerMutationBeforeCheckpointCalculationKeepsTheConstructorSnapshot() {
        val expected = steps()
        val source = expected.toMutableList()
        val recordings = recordings(source)
        val encoded = recordings.map { codec.encode(it) }

        source.clear()

        recordings.forEachIndexed { index, recording ->
            assertEquals(expected, recording.steps)
            assertSame(expected[0], recording.steps[0], "Only the list is copied; step payloads keep their identity")
            assertSame(expected[1], recording.steps[1])
            assertEquals(2, recording.length)
            assertEquals(1, recording.snapshotAt(2).context)
            assertEquals(encoded[index], codec.encode(recording))
        }
    }

    @Test
    fun callerAppendAfterCheckpointCalculationCannotInvalidateTheRecording() {
        val source = mutableListOf<RecordedStep<Int, Tick, String, Empty>>()
        val recordings = recordings(source)
        val encoded = recordings.map { codec.encode(it) }
        recordings.forEach { assertEquals(initial, it.checkpointAt(0).snapshot) }

        source.addAll(steps())

        recordings.forEachIndexed { index, recording ->
            assertEquals(initial, recording.checkpointAt(recording.length).snapshot)
            assertEquals(0, recording.length)
            assertEquals(emptyList(), recording.steps)
            assertEquals(encoded[index], codec.encode(recording))
        }
    }
}
