package actron.timetravel

import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineSnapshot

/** A live read-only view of one member at its group's cursor. Movement belongs to the group. */
class GroupReplayMember internal constructor(
    val machine: Machine<*, *, *, *>,
    val recording: Recording<*, *, *, *>,
    private val currentPosition: () -> Int,
) {
    val length: Int get() = recording.length
    val position: Int get() = currentPosition()
    val snapshot: MachineSnapshot<*> get() = recording.snapshotAt(position)
    val checkpoint: ExecutorCheckpoint<*, *> get() = recording.checkpointAt(position)
}
