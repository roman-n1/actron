package actron.timetravel.file

import actron.observability.MachineGroupId
import actron.observability.file.SegmentOutput
import actron.observability.file.SegmentStorage
import actron.statechart.machine.MessageId

/**
 * Writer-confined segment state: rotation, flushing and retention. The sink supplies live
 * header metadata and the close policy that preserves primary failures and cancellation.
 */
internal class GroupOrderSegmentWriter(
    private val storage: SegmentStorage,
    private val id: MachineGroupId,
    private val config: RecordingFileConfig,
    private val headerFor: (GroupOrderItem, Int) -> GroupSegmentHeader,
    private val finishOutput: (SegmentOutput) -> Unit,
    private val closeOutput: (SegmentOutput, Result<Unit>) -> Unit,
) {
    private sealed interface SegmentWriter {
        data object Detached : SegmentWriter

        data class Active(
            val output: SegmentOutput,
        ) : SegmentWriter
    }

    private var segmentWriter: SegmentWriter = SegmentWriter.Detached
    private var segmentIndex = -1
    private var segmentBytes = 0
    private var segmentEntries = 0
    private var unflushed = 0

    fun release(completion: Result<Unit>) {
        val active = segmentWriter
        if (active !is SegmentWriter.Active) return
        segmentWriter = SegmentWriter.Detached
        closeOutput(active.output, completion)
    }

    fun write(item: GroupOrderItem) {
        if (item is GroupOrderItem.Cut) {
            // A cut: the segment it begins carries it; the entries follow.
            finish()
            open(item)
            retain()
            return
        }
        item as GroupOrderItem.Order
        val frame = GroupRecordingFileFormat.entryFrame(item.entry)
        if (shouldRotate(item, frame.size)) {
            finish()
            open(item)
            retain()
        }
        val active = segmentWriter
        check(active is SegmentWriter.Active)
        val output = active.output
        output.write(frame)
        segmentBytes += frame.size
        segmentEntries++
        if (++unflushed >= config.flushEveryFrames) {
            output.flush()
            unflushed = 0
        }
    }

    private fun open(item: GroupOrderItem) {
        segmentIndex = if (segmentIndex < 0) nextIndex() else segmentIndex + 1
        val header = GroupRecordingFileFormat.header(headerFor(item, segmentIndex))
        val output = storage.append(GroupRecordingFileFormat.segmentName(id, segmentIndex))
        segmentWriter = SegmentWriter.Active(output)
        output.write(header)
        segmentBytes = header.size
        segmentEntries = 0
        unflushed = 0
    }

    private fun nextIndex(): Int = GroupRecordingFileFormat.namedSegments(storage, id).maxOfOrNull { it.first }?.plus(1) ?: 0

    fun finish() {
        val active = segmentWriter
        if (active !is SegmentWriter.Active) return
        val output = active.output
        segmentWriter = SegmentWriter.Detached
        finishOutput(output)
    }

    private fun retain() {
        val mine = GroupRecordingFileFormat.namedSegments(storage, id).sortedBy { it.first }
        for ((_, name) in mine.dropLast(config.maxSegments)) storage.delete(name)
    }

    private fun shouldRotate(
        item: GroupOrderItem.Order,
        frameBytes: Int,
    ): Boolean {
        if (item.beginSegment || segmentWriter is SegmentWriter.Detached) return true
        return segmentEntries > 0 && segmentBytes + frameBytes > config.maxSegmentBytes
    }
}

/** A queued decision or an actual cut that begins a segment. */
internal sealed class GroupOrderItem {
    abstract val index: Int
    abstract val inFlight: List<MessageId>
    abstract val boundary: GroupBoundary

    class Order(
        val entry: GroupOrderEntry,
        override val index: Int,
        override val inFlight: List<MessageId>,
        val beginSegment: Boolean,
    ) : GroupOrderItem() {
        override val boundary: GroupBoundary get() = GroupBoundary.Continuation
    }

    class Cut(
        val cut: RecordedCut,
        override val inFlight: List<MessageId>,
    ) : GroupOrderItem() {
        override val index: Int get() = cut.position
        override val boundary: GroupBoundary get() = cut
    }
}
