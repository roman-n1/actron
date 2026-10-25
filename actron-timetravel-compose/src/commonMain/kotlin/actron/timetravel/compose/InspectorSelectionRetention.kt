@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.observability.GroupSeq
import actron.timetravel.inspect.Inspector
import actron.timetravel.inspect.TimelineItem

/** An index is a view position, never the identity of a retained journal event. */
internal fun retainInspectorSelection(
    previous: Inspector,
    next: Inspector,
    selection: InspectorSelection,
): InspectorSelection {
    if (selection !is InspectorSelection.Position || previous === next) return selection
    if (selection.index !in previous.timeline.indices || !sameInspectorRun(previous, next)) return InspectorSelection.Overview
    val selected = previous.timeline[selection.index]
    var index = -1
    selected.withPosition { seq, _ ->
        index = next.timeline.indexOfFirst { sameJournalEvent(selected, it, seq) }
    }
    return if (index >= 0) InspectorSelection.Position(index) else InspectorSelection.Overview
}

private fun sameInspectorRun(
    previous: Inspector,
    next: Inspector,
): Boolean {
    var same = false
    previous.withRun { session, group, mode ->
        next.withRun { nextSession, nextGroup, nextMode -> same = session == nextSession && group == nextGroup && mode == nextMode }
    }
    return same
}

private fun sameJournalEvent(
    selected: TimelineItem,
    candidate: TimelineItem,
    seq: GroupSeq,
): Boolean {
    if (selected::class != candidate::class || selected.subject != candidate.subject) return false
    var same = false
    candidate.withPosition { candidateSeq, _ -> same = candidateSeq == seq }
    return same
}
