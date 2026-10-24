package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.Machine

/** The saved checkpoint format, compatibility and resumability checks for one machine. */
@ExperimentalActronApi
internal class MachineCheckpointCodec<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    private val codec: RecordingCodec<C, A, CMD, E>,
) {
    internal val definitionKey: String get() = machine.id.value

    /** Rejects incompatible or non-resumable checkpoints rather than silently losing work. */
    fun encode(checkpoint: ExecutorCheckpoint<C, CMD>): String {
        validate(checkpoint)
        return codec.encode(Recording(machine.id, machine.version, checkpoint, emptyList()))
    }

    /** Throws [IllegalArgumentException] for invalid, incompatible or non-resumable data. */
    fun decode(saved: String): ExecutorCheckpoint<C, CMD> {
        val recording =
            when (val result = codec.decode(saved)) {
                is DecodedRecording.Decoded -> result.recording
                is DecodedRecording.Invalid -> throw IllegalArgumentException(result.reason)
                is DecodedRecording.Unsupported -> throw IllegalArgumentException(result.reason)
            }
        require(recording.steps.isEmpty()) { "Expected a checkpoint, not a recorded history" }
        require(recording.definition == machine.id && recording.version == machine.version) { "Saved machine definition or version differs" }
        return recording.start.also(::validate)
    }

    private fun validate(checkpoint: ExecutorCheckpoint<C, CMD>) {
        require(checkpoint.snapshot.definition == machine.id && checkpoint.snapshot.version == machine.version) { "Checkpoint belongs to a different machine" }
        require(checkpoint.effects.isEmpty()) { "Cannot persist unacknowledged effects" }
        require(checkpoint.ending.isEmpty()) { "Cannot persist undelivered command outcomes" }
        require(machine.checkInvariants(checkpoint.snapshot).isEmpty()) { "Checkpoint violates machine invariants" }
    }
}
