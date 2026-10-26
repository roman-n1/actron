@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.observability.StoreInstanceId
import actron.timetravel.inspect.Availability
import actron.timetravel.inspect.InspectorText
import actron.timetravel.inspect.ProcessingAttachment
import actron.timetravel.inspect.RecordingStatus
import actron.timetravel.inspect.StoreView
import actron.timetravel.inspect.TimelineItem
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** Ephemeral display snapshots. InspectorState and the controls remain the only mutable owners. */
internal data class InspectorModePresentation(
    val title: String,
    val run: String,
    val completeness: String,
    val background: Color,
    val foreground: Color,
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
    colors: ColorScheme,
): InspectorModePresentation {
    var run = "session -  group -"
    state.inspector.withRun { session, group, _ -> run = "session $session  group $group" }
    val (background, foreground) =
        when (mode) {
            InspectorMode.Live -> colors.primaryContainer to colors.onPrimaryContainer
            InspectorMode.Inspect -> colors.secondaryContainer to colors.onSecondaryContainer
            InspectorMode.Replay -> colors.tertiaryContainer to colors.onTertiaryContainer
            InspectorMode.Branch -> colors.surfaceContainerHighest to colors.onSurface
        }
    return InspectorModePresentation(mode.name.uppercase(), "$run  records ${state.inspector.records.size}", InspectorText.completeness(state.inspector.completeness), background, foreground)
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
    colors: ColorScheme,
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
            Availability.Available -> listOf(InspectorNotice("replay available", colors.primary))
            is Availability.Unavailable -> replay.reasons.map { InspectorNotice("replay unavailable: $it", colors.tertiary) }
        }
    return InspectorStorePresentation(
        store.id,
        capability,
        InspectorText.completeness(store.completeness),
        if (store.completeness.isComplete) colors.primary else colors.error,
        if (state.filter == StoreFilter.Single(store.id)) colors.secondaryContainer else Color.Transparent,
        "records ${store.records}  processings ${store.processings}  revision $revision  ${if (store.closed) "closed" else "open"}  recording $recording",
        store.completeness.reasons.map(InspectorText::reason),
        availability,
    )
}

internal fun inspectorTimeline(
    state: InspectorState,
    colors: ColorScheme,
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
            val selected = state.selection == InspectorSelection.Position(index)
            InspectorTimelineRow(index, InspectorText.line(item), if (selected) colors.onPrimaryContainer else itemColor(item, colors), if (selected) colors.primaryContainer else Color.Transparent)
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

private fun itemColor(
    item: TimelineItem,
    colors: ColorScheme,
): Color =
    when (item) {
        is TimelineItem.Processing -> colors.onSurface
        is TimelineItem.Discarded, is TimelineItem.Rejected, is TimelineItem.Pending, is TimelineItem.Dropped -> colors.tertiary
        is TimelineItem.Gap, is TimelineItem.Damage, is TimelineItem.Stopped, is TimelineItem.Unattributed -> colors.error
        is TimelineItem.Checkpoint -> colors.primary
        is TimelineItem.Effect, is TimelineItem.Sent, is TimelineItem.Registered, is TimelineItem.Closed, is TimelineItem.Abandoned -> colors.onSurfaceVariant
    }
