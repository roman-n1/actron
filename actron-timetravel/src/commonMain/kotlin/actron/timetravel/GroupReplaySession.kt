package actron.timetravel

import actron.observability.StoreInstanceId
import actron.statechart.machine.Decision
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MessageId
import actron.statechart.machine.SourceId

/**
 * Where a group replay differs from its recording: a member's own divergence, or a bridge
 * delivery the recording cannot have produced (handoff §10: delivered once, to the right
 * instance, after it was sent).
 */
sealed interface GroupMismatch {
    /** The position of the group's order the mismatch is at. */
    val position: Int

    /** A member decided differently from its recording. */
    data class Replay(
        override val position: Int,
        val store: StoreInstanceId,
        val mismatch: ReplayMismatch<*, *, *, *>,
    ) : GroupMismatch

    /** [store] received [message] before the sender's step that emitted it, or the sender never emitted it. */
    data class ReceivedBeforeSent(
        override val position: Int,
        val store: StoreInstanceId,
        val message: MessageId,
    ) : GroupMismatch

    /** [message] names a sender that is not a member of the group. */
    data class SentByNobody(
        override val position: Int,
        val store: StoreInstanceId,
        val message: MessageId,
    ) : GroupMismatch

    /** [store] received [message] a second time; the first delivery was at [first]. */
    data class DeliveredTwice(
        override val position: Int,
        val store: StoreInstanceId,
        val message: MessageId,
        val first: Int,
    ) : GroupMismatch

    /** [store] received [message] although the bridge has no route from its sender to [store]: instances mixed. */
    data class NoRoute(
        override val position: Int,
        val store: StoreInstanceId,
        val message: MessageId,
    ) : GroupMismatch

    /** [store] decided an input of the source [source], which the group had not attached: a source the run missed. */
    data class UnknownSource(
        override val position: Int,
        val store: StoreInstanceId,
        val source: SourceId,
    ) : GroupMismatch

    /**
     * [store] received [message] over the reply route of the pair [pair], but its sender had
     * received no request of the pair from [store] when it emitted it: a reply to nothing. A
     * run since a cut cannot see what came before it: a member that starts at a checkpoint
     * counts as having heard from everyone, and a message in flight at the start is not checked.
     */
    data class ReplyWithoutRequest(
        override val position: Int,
        val store: StoreInstanceId,
        val message: MessageId,
        val pair: String,
    ) : GroupMismatch
}

/** The result of one forward step of a [GroupReplaySession]. */
sealed interface GroupReplayStep {
    data class Finished(
        val position: Int,
    ) : GroupReplayStep

    data class Matched(
        val position: Int,
        val store: StoreInstanceId,
        val decision: Decision<*, *, *>,
    ) : GroupReplayStep

    data class Diverged(
        val mismatch: GroupMismatch,
    ) : GroupReplayStep
}

/**
 * Replays a [GroupRecording]: a cursor over the group's order that steps each member's
 * [ReplaySession] in turn, so at every position each member is at the snapshot it had when the
 * group was there. [verify] decides every member's steps again and checks the bridge
 * deliveries against the sends. Nothing runs, no bridge delivers: the recorded inputs already
 * hold every delivery, and a replay only checks that they could have happened.
 *
 * @param machines The machine of each member; every member of the recording needs one
 * @throws IllegalArgumentException if a member has no machine or its recording is not replayable by it
 */
class GroupReplaySession(
    machines: Map<StoreInstanceId, Machine<*, *, *, *>>,
    val recording: GroupRecording,
) {
    private val replayMembers = GroupReplayMembers(machines, recording)

    /** The number of steps of the group. */
    val length: Int get() = recording.length

    /** The current position, 0 to [length]. */
    var position: Int = 0
        private set

    /** Reports the next group step once; returns false at the end. */
    fun withNext(accept: (GroupStep) -> Unit): Boolean {
        if (position >= length) return false
        accept(recording.order[position])
        return true
    }

    /** Read-only member inspection, always derived from the current group position. */
    val memberViews: Map<StoreInstanceId, GroupReplayMember> =
        recording.members.mapValues { (id, member) ->
            GroupReplayMember(replayMembers.session(id).machine, member) { recording.stepsBefore(position, id) }
        }

    // The recording's bridge/source relationships do not depend on the replay cursor.
    private val bridgeMismatches by lazy { GroupReplayCausality(recording).mismatches }

    /** The snapshot of [store] at the group's position. */
    fun snapshotOf(store: StoreInstanceId): MachineSnapshot<*> = memberView(store).snapshot

    /** The executor's state of [store] at the group's position. */
    fun checkpointOf(store: StoreInstanceId): ExecutorCheckpoint<*, *> = memberView(store).checkpoint

    /**
     * Checks the next input's bridge/source causality, decides it on its member and compares
     * with the recording. Advances on a match; a divergence leaves every member and the group
     * cursor unchanged. [GroupReplayStep.Finished] at the final checkpoint.
     */
    fun stepForward(): GroupReplayStep {
        if (position >= length) return GroupReplayStep.Finished(position)
        val step = recording.order[position]
        for (mismatch in bridgeMismatches) if (mismatch.position == position) return GroupReplayStep.Diverged(mismatch)
        val member = replayMembers.session(step.store)
        // The recorded ordinal is authoritative for every group member.
        member.seek(step.step)
        return when (val result = member.stepForward()) {
            is ReplayStep.Finished -> {
                GroupReplayStep.Finished(position)
            }

            is ReplayStep.Matched<*, *, *, *> -> {
                position++
                GroupReplayStep.Matched(position - 1, step.store, result.decision)
            }

            is ReplayStep.Diverged<*, *, *, *> -> {
                GroupReplayStep.Diverged(GroupMismatch.Replay(position, step.store, result.mismatch))
            }
        }
    }

    /** Moves one step back; `false` at the start. */
    fun stepBackward(): Boolean {
        if (position == 0) return false
        seek(position - 1)
        return true
    }

    /**
     * Moves the group to [position]: each member to the number of its steps before it.
     *
     * @throws IllegalArgumentException if [position] is outside 0..[length]
     */
    fun seek(position: Int) {
        require(position in 0..length) { "[Actron] Position $position is outside 0..$length" }
        replayMembers.seek(position)
        this.position = position
    }

    /**
     * Every mismatch of the group, in order of position: each member's first divergence, and
     * every bridge delivery that was received before it was sent, sent by nobody, delivered
     * twice, delivered where no route leads, or a reply whose sender had received no request of
     * the pair from the receiver. Does not move.
     */
    fun verify(): List<GroupMismatch> {
        val mismatches = replayMembers.verify().toMutableList()
        mismatches += bridgeMismatches
        return mismatches.sortedBy { it.position }
    }

    /**
     * A branch of the whole group from the current position: every member's [Branch] from its
     * checkpoint here, joined by a local bridge over [routes], with the sources' snapshots as at
     * the recording's start for the caller to script their data from.
     */
    fun branch(routes: List<GroupBranch.Route>): GroupBranch {
        replayMembers.seek(position)
        return replayMembers.branch(routes)
    }

    private fun memberView(store: StoreInstanceId): GroupReplayMember = requireNotNull(memberViews[store]) { "[Actron] $store is not a member" }
}
