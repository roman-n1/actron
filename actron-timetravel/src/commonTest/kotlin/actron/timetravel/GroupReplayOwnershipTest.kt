@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel

import actron.statechart.machine.EffectId
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.machine.MessageId
import actron.statechart.machine.PairRole
import actron.statechart.machine.RoutePair
import actron.statechart.machine.SourceId
import actron.statechart.machine.SourceSnapshot
import actron.timetravel.GroupFixture.PickerAct
import actron.timetravel.GroupFixture.PickerCtx
import actron.timetravel.GroupFixture.RootAct
import actron.timetravel.GroupFixture.RootCtx
import actron.timetravel.GroupFixture.pickerId
import actron.timetravel.GroupFixture.rootId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

class GroupReplayOwnershipTest {
    private fun recording(routes: List<GroupRoute>): GroupRecording {
        val picker = GroupFixture.picker
        val root = GroupFixture.rootMachine
        val pickerRun =
            picker.recordInputs(
                picker.initialSnapshot(PickerCtx()),
                listOf(
                    MachineInput.Start(MachineTime.Zero),
                    MachineInput.Dispatch(PickerAct.Pick("tom"), MachineTime(1.milliseconds)),
                    MachineInput.BridgeReceived(MessageId(rootId, EffectId(1)), PickerAct.Ack(1), MachineTime(3.milliseconds)),
                ),
            )
        val rootRun =
            root.recordInputs(
                root.initialSnapshot(RootCtx()),
                listOf(
                    MachineInput.Start(MachineTime.Zero),
                    MachineInput.BridgeReceived(MessageId(pickerId, EffectId(1)), RootAct.Apply("tom"), MachineTime(2.milliseconds)),
                ),
            )
        return GroupRecording(mapOf(pickerId to pickerRun, rootId to rootRun), listOf(GroupStep(pickerId, 0), GroupStep(rootId, 0), GroupStep(pickerId, 1), GroupStep(rootId, 1), GroupStep(pickerId, 2)), routes)
    }

    private fun routes() = listOf(GroupRoute(pickerId, rootId), GroupRoute(rootId, pickerId))

    @Test
    fun requestOfAnotherPairDoesNotAuthorizeReply() {
        val recorded =
            recording(
                listOf(
                    GroupRoute(pickerId, rootId, RoutePair("request-A", PairRole.Request)),
                    GroupRoute(rootId, pickerId, RoutePair("reply-B", PairRole.Reply)),
                ),
            )
        assertEquals(listOf(GroupMismatch.ReplyWithoutRequest(4, pickerId, MessageId(rootId, EffectId(1)), "reply-B")), GroupReplaySession(GroupFixture.machines, recorded).verify())
        val replay = GroupReplaySession(GroupFixture.machines, recorded)
        replay.seek(4)
        replay.members.getValue(pickerId).seek(0)
        val diverged = assertIs<GroupReplayStep.Diverged>(replay.stepForward())
        assertEquals(recorded.members.getValue(pickerId).snapshotAt(2), replay.snapshotOf(pickerId))
        assertEquals(4, replay.position)
        assertEquals(replay.verify().single(), diverged.mismatch)
        val matched =
            recording(
                listOf(
                    GroupRoute(pickerId, rootId, RoutePair("pair", PairRole.Request)),
                    GroupRoute(rootId, pickerId, RoutePair("pair", PairRole.Reply)),
                ),
            )
        assertEquals(emptyList(), GroupReplaySession(GroupFixture.machines, matched).verify())
    }

    @Test
    fun movingALegacyMemberCannotChangeGroupSnapshotsBranchesOrForwardSteps() {
        val recorded = recording(routes())
        val replay = GroupReplaySession(GroupFixture.machines, recorded)
        val member = replay.members.getValue(pickerId)
        member.seek(member.length)
        assertEquals(recorded.members.getValue(pickerId).initial, replay.snapshotOf(pickerId))
        assertEquals(recorded.members.getValue(pickerId).start, replay.checkpointOf(pickerId))
        assertEquals(recorded.members.getValue(pickerId).initial, replay.branch(emptyList()).snapshot(pickerId))
        member.seek(member.length)
        assertIs<GroupReplayStep.Matched>(replay.stepForward())
        assertEquals(1, replay.position)
        assertEquals(recorded.members.getValue(pickerId).snapshotAt(1), replay.snapshotOf(pickerId))
    }

    @Test
    fun memberViewsFollowOnlyTheGroupCursor() {
        val recorded = recording(routes())
        val replay = GroupReplaySession(GroupFixture.machines, recorded)
        val picker = replay.memberViews.getValue(pickerId)
        val root = replay.memberViews.getValue(rootId)
        assertEquals(0, picker.position)
        assertEquals(0, root.position)
        assertEquals(recorded.members.getValue(pickerId), picker.recording)
        assertEquals(GroupFixture.picker, picker.machine)
        replay.seek(3)
        assertEquals(2, picker.position)
        assertEquals(1, root.position)
        assertEquals(recorded.members.getValue(pickerId).snapshotAt(2), picker.snapshot)
        replay.members.getValue(pickerId).seek(0)
        assertEquals(2, picker.position)
        assertEquals(recorded.members.getValue(pickerId).checkpointAt(2), picker.checkpoint)
        assertIs<GroupReplayStep.Matched>(replay.stepForward())
        assertEquals(2, root.position)
        assertEquals(true, replay.stepBackward())
        assertEquals(1, root.position)
        replay.seek(replay.length)
        assertEquals(picker.length, picker.position)
        assertEquals(root.length, root.position)
    }

    @Test
    fun constructorCollectionsAreSnapshotsAndCannotInvalidateReplayOrItsCache() {
        val original = recording(routes())
        val members = original.members.toMutableMap()
        val order = original.order.toMutableList()
        val routes = original.routes.toMutableList()
        val inFlight = mutableListOf(MessageId(pickerId, EffectId(99)))
        val sourceIds = mutableSetOf(SourceId("socket"))
        val snapshot = SourceSnapshot(SourceId("socket"), "socket", 1, mapOf("offset" to "7"))
        val sources = mutableMapOf(snapshot.source to snapshot)
        val recorded = GroupRecording(members, order, routes, inFlight, sourceIds, sources)
        val replay = GroupReplaySession(GroupFixture.machines, recorded)
        assertEquals(emptyList(), replay.verify())
        members.clear()
        order.clear()
        routes.clear()
        inFlight.clear()
        sourceIds.clear()
        sources.clear()
        assertEquals(original.members, recorded.members)
        assertEquals(original.order, recorded.order)
        assertEquals(original.routes, recorded.routes)
        assertEquals(listOf(MessageId(pickerId, EffectId(99))), recorded.inFlight)
        assertEquals(setOf(SourceId("socket")), recorded.sourceIds)
        assertEquals(mapOf(snapshot.source to snapshot), recorded.sourceSnapshots)
        assertEquals(emptyList(), replay.verify())
        assertEquals(emptyList(), GroupReplaySession(GroupFixture.machines, recorded).verify())
        assertEquals(5, replay.length)
    }
}
