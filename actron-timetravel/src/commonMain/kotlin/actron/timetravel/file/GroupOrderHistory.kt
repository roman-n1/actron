package actron.timetravel.file

import actron.observability.MachineGroupId
import actron.observability.StoreInstanceId
import actron.observability.file.SegmentMark
import actron.observability.file.SegmentStorage

/** Reads continuity and diagnostics of the order stream, without opening member recordings. */
internal class GroupOrderHistory(
    private val storage: SegmentStorage,
) {
    fun read(
        group: MachineGroupId,
        marks: MutableList<RecordingFileMark>,
    ): List<GroupOrderRun> {
        // The order file's last continuous range.
        val segments = GroupRecordingFileFormat.namedSegments(storage, group).sortedBy { it.first }
        val runs = mutableListOf<GroupOrderRun>()
        var canContinue = false
        var expectedIndex = 0
        for ((index, name) in segments) {
            if (index > expectedIndex) {
                marks += RecordingFileMark.MissingSegments(StoreInstanceId(group.value), expectedIndex, index - 1)
                canContinue = false
            }
            expectedIndex = index + 1
            val decoded = GroupRecordingFileFormat.decodeSegment(name, storage.read(name))
            if (decoded !is DecodedGroupSegment.Readable) {
                decoded.ending.withIssue { marks += RecordingFileMark.Damaged(it) }
                canContinue = false
                continue
            }
            val header = decoded.header
            if (canContinue && header.firstEntry != runs.last().nextEntry) {
                marks += RecordingFileMark.StepsMissing(StoreInstanceId(group.value), runs.last().nextEntry, header.firstEntry)
                canContinue = false
            }
            if (!canContinue) {
                runs += GroupOrderRun(header, mutableListOf())
                canContinue = true
            }
            val run = runs.last()
            header.boundary.withCut { run.cuts += it }
            run.entries += decoded.entries
            run.nextEntry = header.firstEntry + decoded.entries.size
            decoded.ending.withIssue {
                marks += RecordingFileMark.Damaged(it)
                if (it !is SegmentMark.Unfinished) canContinue = false
            }
        }
        return runs
    }
}

internal class GroupOrderRun(
    val header: GroupSegmentHeader,
    val entries: MutableList<GroupOrderEntry>,
) {
    var nextEntry: Int = header.firstEntry
    val cuts = mutableListOf<RecordedCut>()
}
