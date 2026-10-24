package actron.timetravel.file

import actron.core.Action
import actron.core.Event
import actron.observability.StoreInstanceId
import actron.statechart.machine.SourceId
import actron.statechart.machine.SourceSnapshot
import actron.timetravel.GroupRecording
import actron.timetravel.GroupStep
import actron.timetravel.Recording

/** Reconstructs the window shared by the order and member files, including idle checkpoints. */
internal class GroupRecordingRange(
    private val order: GroupOrderRun,
    private val members: Map<StoreInstanceId, Pair<Recording<*, *, *, *>, Int>>,
    private val marks: MutableList<RecordingFileMark>,
) {
    fun read(): GroupRecordingFileContents {
        val window = coveredWindow()
        val from = window.first
        val to = window.last + 1
        if (window.isEmpty() || !idleMembersCovered(from, to)) return GroupRecordingFileContents.Unreadable(marks.toList())
        val cuts = matchingCuts(from, to)
        val sliceAt = { at: Int, sources: Map<SourceId, SourceSnapshot> -> slice(at, to, sources) }
        val firstEntry = order.header.firstEntry
        var sources: Map<SourceId, SourceSnapshot> = emptyMap()
        for (cut in cuts) {
            if (cut.position - firstEntry == from) {
                sources = cut.sources
                break
            }
        }
        val recording = sliceAt(from, sources)
        return GroupRecordingFileContents.Readable(recording, firstEntry + from, cuts, marks) { cut -> sliceAt(cut.position - firstEntry, cut.sources) }
    }

    private fun coveredWindow(): IntRange {
        // The range all files cover: from the latest start among the order's and the members', to the earliest end.
        val entries = order.entries
        var from = 0
        var to = entries.size
        for ((member, pair) in members) {
            val (recording, firstStep) = pair
            // Entries of steps the member's file no longer has are before the range.
            val lastUncovered = entries.indexOfLast { it.store == member && it.step < firstStep }
            from = maxOf(from, lastUncovered + 1)
            val beyond = entries.indexOfFirst { it.store == member && it.step >= firstStep + recording.length }
            if (beyond >= 0) to = minOf(to, beyond)
        }
        for (member in order.header.members) {
            if (member !in members && entries.any { it.store == member }) {
                // A member whose file is gone: only the entries before its first one are usable.
                val first = entries.indexOfFirst { it.store == member }
                to = minOf(to, first)
            }
        }
        return from until to
    }

    private fun idleMembersCovered(
        from: Int,
        to: Int,
    ): Boolean {
        val entries = order.entries
        for ((member, pair) in members) {
            if (entries.subList(from, to).any { it.store == member }) continue
            val (recording, firstStep) = pair
            var covered = false
            idleStep(from, member) { covered = it in firstStep..(firstStep + recording.length) }
            if (!covered) {
                marks += RecordingFileMark.StartMismatch(member, "-", "no checkpoint for the member at the surviving order range")
                return false
            }
        }
        return true
    }

    private fun matchingCuts(
        from: Int,
        to: Int,
    ): List<RecordedCut> {
        val entries = order.entries
        val firstEntry = order.header.firstEntry
        return order.cuts.filter { cut ->
            val at = cut.position - firstEntry
            if (at !in from..to) return@filter false
            for ((member, count) in cut.counts) {
                var agrees = true
                stepsBefore(entries, at, member) { step ->
                    if (step != count) {
                        marks += RecordingFileMark.StartMismatch(member, "-", "the cut at ${cut.position} counts $count steps of $member, the order $step")
                        agrees = false
                    }
                }
                if (!agrees) return@filter false
            }
            true
        }
    }

    // The order can establish a member's count from its preceding or following entry.
    private fun stepsBefore(
        entries: List<GroupOrderEntry>,
        at: Int,
        member: StoreInstanceId,
        accept: (Int) -> Unit,
    ): Boolean {
        for (index in at - 1 downTo 0) {
            if (entries[index].store == member) {
                accept(entries[index].step + 1)
                return true
            }
        }
        for (index in at until entries.size) {
            if (entries[index].store == member) {
                accept(entries[index].step)
                return true
            }
        }
        return false
    }

    // An idle member still needs a checkpoint established by the order or an actual cut.
    private fun idleStep(
        at: Int,
        member: StoreInstanceId,
        accept: (Int) -> Unit,
    ): Boolean {
        if (stepsBefore(order.entries, at, member, accept)) return true
        var reported = false
        order.header.boundary.withCut { cut ->
            if (member in cut.counts) {
                accept(cut.counts.getValue(member))
                reported = true
            }
        }
        if (reported) return true
        if (order.header.firstEntry == 0) {
            accept(0)
            return true
        }
        return false
    }

    // The run over entries `from` until `to`: every member trimmed to its checkpoint at its first entry there, the order re-based, the messages in flight computed.
    private fun slice(
        from: Int,
        to: Int,
        sources: Map<SourceId, SourceSnapshot>,
    ): GroupRecording {
        val entries = order.entries
        val range = entries.subList(from, to)
        val trimmed = mutableMapOf<StoreInstanceId, Recording<*, *, *, *>>()
        val offsets = mutableMapOf<StoreInstanceId, Int>()
        for ((member, pair) in members) {
            val (recording, firstStep) = pair

            fun trim(firstInRange: Int) {
                val skip = firstInRange - firstStep
                // Keep only the steps whose order entries survived the process stopping.
                trimmed[member] = recording.sinceStep(skip, range.count { it.store == member })
                offsets[member] = firstInRange
            }
            val first = range.indexOfFirst { it.store == member }
            if (first >= 0) {
                trim(range[first].step)
            } else {
                check(idleStep(from, member, ::trim)) { "[Actron] No checkpoint for $member at the surviving order range" }
            }
        }
        val groupOrder = range.map { GroupStep(it.store, it.step - offsets.getValue(it.store)) }
        // Messages in flight at the range's start: the header's, plus what the skipped entries sent, minus what they received.
        val inFlight =
            order.header.inFlight
                .groupingBy { it }
                .eachCount()
                .toMutableMap()
        for (entry in entries.subList(0, from)) {
            entry.delivery.withReceived { message ->
                if (message in inFlight) {
                    val count = inFlight.getValue(message)
                    if (count <= 1) inFlight.remove(message) else inFlight[message] = count - 1
                }
            }
            for (message in entry.sent) inFlight[message] = (inFlight[message] ?: 0) + 1
        }
        return GroupRecording(trimmed, groupOrder, order.header.routes, inFlight.keys.toList(), order.header.sourceIds + sources.keys, sources)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Recording<*, *, *, *>.sinceStep(
        skip: Int,
        count: Int,
    ): Recording<*, *, *, *> {
        val typed = this as Recording<Any, Action, Any, Event>
        return Recording(typed.definition, typed.version, typed.checkpointAt(skip), typed.steps.drop(skip).take(count))
    }
}
