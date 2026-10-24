package actron.timetravel.file

import actron.observability.MachineGroupId
import actron.observability.StoreInstanceId
import actron.observability.file.SegmentEnding
import actron.observability.file.SegmentMark
import actron.statechart.machine.MessageId
import actron.statechart.machine.SourceId
import actron.statechart.machine.SourceSnapshot
import actron.timetravel.GroupRecording
import actron.timetravel.GroupRoute

/**
 * What the header of an order segment says; [firstEntry] is the index in the whole run of its
 * first entry, [inFlight] the messages sent before it and not yet received, [cut] the group's
 * cut the segment begins at, if it begins at one.
 */
data class GroupSegmentHeader(
    val fileFormatVersion: Int,
    val group: MachineGroupId,
    val members: List<StoreInstanceId>,
    val routes: List<GroupRoute>,
    val sourceIds: Set<SourceId>,
    val index: Int,
    val firstEntry: Int,
    val inFlight: List<MessageId>,
    val boundary: GroupBoundary = GroupBoundary.Continuation,
)

/**
 * A cut of the group as the files remember it: the [position] in the whole run of the first
 * entry after it, how many steps of each member came before it, and the sources' snapshots
 * there. A run since a cut knows its sources' state, as [GroupRecording.since] does in memory.
 */
sealed interface GroupBoundary {
    fun withCut(accept: (RecordedCut) -> Unit): Boolean

    data object Continuation : GroupBoundary {
        override fun withCut(accept: (RecordedCut) -> Unit): Boolean = false
    }
}

data class RecordedCut(
    val position: Int,
    val counts: Map<StoreInstanceId, Int>,
    val sources: Map<SourceId, SourceSnapshot>,
) : GroupBoundary {
    override fun withCut(accept: (RecordedCut) -> Unit): Boolean {
        accept(this)
        return true
    }
}

/** How a member's decision reached the group: its local input or a delivered bridge message. */
sealed interface GroupDelivery {
    fun withReceived(accept: (MessageId) -> Unit): Boolean

    data object Local : GroupDelivery {
        override fun withReceived(accept: (MessageId) -> Unit): Boolean = false
    }

    data class Bridged(
        val message: MessageId,
    ) : GroupDelivery {
        override fun withReceived(accept: (MessageId) -> Unit): Boolean {
            accept(message)
            return true
        }
    }
}

/** One decision of the group in its order: which member, which of its steps, what it sent over the bridge and what it received. */
data class GroupOrderEntry(
    val store: StoreInstanceId,
    val step: Int,
    val sent: List<MessageId> = emptyList(),
    val delivery: GroupDelivery = GroupDelivery.Local,
) {
    constructor(store: StoreInstanceId, step: Int, sent: List<MessageId> = emptyList(), received: MessageId) : this(store, step, sent, GroupDelivery.Bridged(received))
}

sealed class DecodedGroupSegment {
    abstract val entries: List<GroupOrderEntry>
    abstract val ending: SegmentEnding
    val finished: Boolean get() = ending.finished

    data class Unreadable(
        val issue: SegmentMark,
    ) : DecodedGroupSegment() {
        override val entries: List<GroupOrderEntry> get() = emptyList()
        override val ending: SegmentEnding get() = SegmentEnding.Stopped(issue)
    }

    data class Readable(
        val header: GroupSegmentHeader,
        override val entries: List<GroupOrderEntry>,
        override val ending: SegmentEnding,
    ) : DecodedGroupSegment()
}

/**
 * What [GroupRecordingFiles.read] found: the group's run over the range every member's file and
 * the order file still cover, replayable from the members' checkpoints there, the cuts inside
 * that range, and the marks of everything else.
 *
 * A [GroupRecordingFileContents.Readable] holds the range and its starting position; sources'
 * snapshots are known when the range begins at a cut. Unreadable results retain diagnostics
 * @property position The index in the whole run of the range's first entry
 * @property cuts The group's cuts inside the range, by position; [since] gives the run from one
 */
sealed class GroupRecordingFileContents {
    abstract val cuts: List<RecordedCut>
    abstract val marks: List<RecordingFileMark>

    protected abstract fun slice(cut: RecordedCut): GroupRecording

    /** Continues the readable group range from one of its recorded cuts. */
    fun since(cut: RecordedCut): GroupRecording {
        require(cut in cuts) { "[Actron] $cut is not a cut inside the range read" }
        return slice(cut)
    }

    class Unreadable(
        override val marks: List<RecordingFileMark>,
    ) : GroupRecordingFileContents() {
        override val cuts: List<RecordedCut> get() = emptyList()

        override fun slice(cut: RecordedCut): GroupRecording = error("[Actron] Nothing was read")

        override fun toString(): String = "GroupRecordingFileContents(position=null, cuts=[], marks=$marks)"
    }

    class Readable internal constructor(
        val recording: GroupRecording,
        val position: Int,
        override val cuts: List<RecordedCut>,
        override val marks: List<RecordingFileMark>,
        private val slicing: (RecordedCut) -> GroupRecording,
    ) : GroupRecordingFileContents() {
        override fun slice(cut: RecordedCut): GroupRecording = slicing(cut)

        override fun toString(): String = "GroupRecordingFileContents(position=$position, cuts=${cuts.map { it.position }}, marks=$marks)"
    }
}
