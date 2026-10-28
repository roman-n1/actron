package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.observability.StoreInstanceId
import actron.statechart.machine.Machine

/** Owns member sessions and maps their cursors and divergences into the recorded group order. */
internal class GroupReplayMembers(
    machines: Map<StoreInstanceId, Machine<*, *, *, *>>,
    private val recording: GroupRecording,
) {
    val sessions: Map<StoreInstanceId, ReplaySession<*, *, *, *>> =
        recording.members.mapValues { (id, memberRecording) ->
            val machine = requireNotNull(machines[id]) { "[Actron] No machine for member $id" }
            open(machine, memberRecording)
        }

    fun seek(position: Int) {
        for ((id, session) in sessions) session.seek(recording.stepsBefore(position, id))
    }

    fun verify(): List<GroupMismatch> =
        buildList {
            for ((id, session) in sessions) {
                when (val result = session.verification()) {
                    ReplayVerification.Passed -> Unit
                    is ReplayVerification.Diverged -> add(GroupMismatch.Replay(positionOf(id, result.mismatch.position), id, result.mismatch))
                }
            }
        }

    fun branch(routes: List<GroupBranch.Route>): GroupBranch = GroupBranch(sessions.mapValues { (_, session) -> session.branch() }, routes, recording.sourceSnapshots)

    fun session(store: StoreInstanceId): ReplaySession<*, *, *, *> = requireNotNull(sessions[store]) { "[Actron] $store is not a member" }

    private fun positionOf(
        store: StoreInstanceId,
        step: Int,
    ): Int = recording.order.indexOfFirst { it.store == store && it.step == step }.let { if (it < 0) recording.length else it }

    @Suppress("UNCHECKED_CAST")
    private fun open(
        machine: Machine<*, *, *, *>,
        recording: Recording<*, *, *, *>,
    ): ReplaySession<*, *, *, *> = ReplaySession(machine as Machine<Any, Action, Any, Event>, recording as Recording<Any, Action, Any, Event>)
}
