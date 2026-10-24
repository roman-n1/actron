@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.observability.StoreInstanceId
import actron.timetravel.inspect.Availability
import actron.timetravel.inspect.InspectorText
import actron.timetravel.inspect.ProcessingAttachment
import actron.timetravel.inspect.RecordingStatus
import actron.timetravel.inspect.StoreView
import actron.timetravel.inspect.TimelineItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** Ephemeral display snapshots. InspectorState and the controls remain the only mutable owners. */
internal data class InspectorModePresentation(
    val title: String,
    val run: String,
    val completeness: String,
)

internal data class InspectorNotice(
    val text: String,
    val color: Color,
)

internal data class InspectorStorePresentation(
    val id: StoreInstanceId,
    val capability: String,
    val completeness: String,
    val completenessColor: Color,
    val background: Color,
    val counts: String,
    val reasons: List<String>,
    val replay: List<InspectorNotice>,
)

internal data class InspectorTimelineRow(
    val index: Int,
    val text: String,
    val color: Color,
    val background: Color,
)

internal data class InspectorTimelinePresentation(
    val heading: String,
    val rows: List<InspectorTimelineRow>,
)

internal data class InspectorDetailLine(
    val text: String,
    val weight: FontWeight,
)

internal data class InspectorPositionPresentation(
    val heading: String,
    val content: InspectorPositionContent,
)

internal sealed interface InspectorPositionContent {
    data class Prompt(
        val text: String,
    ) : InspectorPositionContent

    data class Journal(
        val lines: List<InspectorDetailLine>,
        val notices: List<String>,
    ) : InspectorPositionContent
}

internal fun inspectorMode(
    state: InspectorState,
    mode: InspectorMode,
): InspectorModePresentation {
    var run = "session -  group -"
    state.inspector.withRun { session, group, _ -> run = "session $session  group $group" }
    return InspectorModePresentation(mode.name.uppercase(), "$run  records ${state.inspector.records.size}", InspectorText.completeness(state.inspector.completeness))
}

internal fun inspectorGroupReasons(state: InspectorState): List<String> {
    val memberReasons = state.inspector.stores.flatMap { it.completeness.reasons }
    return state.inspector.completeness.reasons
        .filter { it !in memberReasons }
        .map(InspectorText::reason)
}

internal fun inspectorStore(
    state: InspectorState,
    store: StoreView,
    selected: Color,
): InspectorStorePresentation {
    var capability = "unregistered"
    store.withCapability { capability = it.name }
    var revision = "-"
    store.withRevision { revision = it.toString() }
    val recording =
        when (val status = store.recording) {
            RecordingStatus.Unrecorded -> "none"
            is RecordingStatus.Attached -> "attached (${status.steps} steps)"
            is RecordingStatus.Mismatch -> "mismatch"
        }
    val availability =
        when (val replay = state.inspector.replayability(store.id)) {
            Availability.Available -> listOf(InspectorNotice("replay available", Color(0xFF2E7D32)))
            is Availability.Unavailable -> replay.reasons.map { InspectorNotice("replay unavailable: $it", Color(0xFF8E24AA)) }
        }
    return InspectorStorePresentation(
        store.id,
        capability,
        InspectorText.completeness(store.completeness),
        if (store.completeness.isComplete) Color(0xFF2E7D32) else Color(0xFFC62828),
        if (state.filter == StoreFilter.Single(store.id)) selected else Color.Transparent,
        "records ${store.records}  processings ${store.processings}  revision $revision  ${if (store.closed) "closed" else "open"}  recording $recording",
        store.completeness.reasons.map(InspectorText::reason),
        availability,
    )
}

internal fun inspectorTimeline(
    state: InspectorState,
    selected: Color,
): InspectorTimelinePresentation {
    val visible = state.visibleTimeline
    val suffix =
        when (val filter = state.filter) {
            is StoreFilter.Single -> " of ${filter.store}"
            StoreFilter.All -> ""
        }
    return InspectorTimelinePresentation(
        "Timeline$suffix (${visible.size})",
        visible.map { (index, item) ->
            InspectorTimelineRow(index, InspectorText.line(item), itemColor(item), if (state.selection == InspectorSelection.Position(index)) selected else Color.Transparent)
        },
    )
}

internal fun inspectorPosition(state: InspectorState): InspectorPositionPresentation {
    val selection = state.selection
    val heading = "Position" + if (selection is InspectorSelection.Position) " ${selection.index}" else ""
    if (selection !is InspectorSelection.Position || selection.index !in state.inspector.timeline.indices) {
        return InspectorPositionPresentation(heading, InspectorPositionContent.Prompt("select a position of the timeline"))
    }
    val item = state.inspector.timeline[selection.index]
    val notice =
        if (item is TimelineItem.Processing && item.attachment is ProcessingAttachment.JournalOnly) {
            listOf("snapshots are what the journal kept; attach the Store's recording to see the machine's")
        } else {
            emptyList()
        }
    return InspectorPositionPresentation(heading, InspectorPositionContent.Journal(InspectorText.detail(item).mapIndexed { index, line -> InspectorDetailLine(line, if (index == 0) FontWeight.Bold else FontWeight.Normal) }, notice))
}

internal fun inspectorForwardAllowed(replay: ReplayControls<*, *, *, *>): Boolean = replay.canStepForward && replay.movement is ReplayMovement.Open

internal fun inspectorModeColor(mode: InspectorMode): Color =
    when (mode) {
        InspectorMode.Live -> Color(0xFF2E7D32)
        InspectorMode.Inspect -> Color(0xFF1565C0)
        InspectorMode.Replay -> Color(0xFF6A1B9A)
        InspectorMode.Branch -> Color(0xFFEF6C00)
    }

private fun itemColor(item: TimelineItem): Color =
    when (item) {
        is TimelineItem.Processing -> Color.Unspecified
        is TimelineItem.Discarded, is TimelineItem.Rejected, is TimelineItem.Pending, is TimelineItem.Dropped -> Color(0xFFEF6C00)
        is TimelineItem.Gap, is TimelineItem.Damage, is TimelineItem.Stopped, is TimelineItem.Unattributed -> Color(0xFFC62828)
        is TimelineItem.Checkpoint -> Color(0xFF1565C0)
        is TimelineItem.Effect, is TimelineItem.Sent, is TimelineItem.Registered, is TimelineItem.Closed, is TimelineItem.Abandoned -> Color(0xFF616161)
    }
