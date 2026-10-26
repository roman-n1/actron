package actron.timetravel.compose

import actron.observability.StoreInstanceId
import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.inspector_position
import actron.timetravel.compose.resources.inspector_position_number
import actron.timetravel.compose.resources.inspector_store_timeline
import actron.timetravel.compose.resources.inspector_timeline
import actron.timetravel.compose.resources.journal_only_snapshots
import actron.timetravel.compose.resources.mode_branch
import actron.timetravel.compose.resources.mode_inspect
import actron.timetravel.compose.resources.mode_live
import actron.timetravel.compose.resources.mode_replay
import actron.timetravel.compose.resources.recording_attached
import actron.timetravel.compose.resources.recording_mismatch
import actron.timetravel.compose.resources.recording_none
import actron.timetravel.compose.resources.replay_available
import actron.timetravel.compose.resources.replay_unavailable
import actron.timetravel.compose.resources.run_identity
import actron.timetravel.compose.resources.select_position
import actron.timetravel.compose.resources.store_closed
import actron.timetravel.compose.resources.store_counts
import actron.timetravel.compose.resources.store_open
import actron.timetravel.compose.resources.unregistered_store
import actron.timetravel.compose.resources.verbatim
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
    val title: PanelText,
    val run: PanelText,
    val completeness: PanelText,
    val background: Color,
    val foreground: Color,
)

internal data class InspectorNotice(
    val text: PanelText,
    val color: Color,
)

internal data class InspectorStorePresentation(
    val id: StoreInstanceId,
    val capability: PanelText,
    val completeness: PanelText,
    val completenessColor: Color,
    val background: Color,
    val counts: PanelText,
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
    val heading: PanelText,
    val rows: List<InspectorTimelineRow>,
)

internal data class InspectorDetailLine(
    val text: String,
    val weight: FontWeight,
)

internal data class InspectorPositionPresentation(
    val heading: PanelText,
    val content: InspectorPositionContent,
)

internal sealed interface InspectorPositionContent {
    data class Prompt(
        val text: PanelText,
    ) : InspectorPositionContent

    data class Journal(
        val lines: List<InspectorDetailLine>,
        val notices: List<PanelText>,
    ) : InspectorPositionContent
}

internal fun inspectorMode(
    state: InspectorState,
    mode: InspectorMode,
    colors: ColorScheme,
): InspectorModePresentation {
    var run = panelText(resource = Res.string.run_identity, "-", "-", state.inspector.records.size)
    state.inspector.withRun { session, group, _ -> run = panelText(resource = Res.string.run_identity, session, group, state.inspector.records.size) }
    val title =
        when (mode) {
            InspectorMode.Live -> Res.string.mode_live
            InspectorMode.Inspect -> Res.string.mode_inspect
            InspectorMode.Replay -> Res.string.mode_replay
            InspectorMode.Branch -> Res.string.mode_branch
        }
    val (background, foreground) =
        when (mode) {
            InspectorMode.Live -> colors.primaryContainer to colors.onPrimaryContainer
            InspectorMode.Inspect -> colors.secondaryContainer to colors.onSecondaryContainer
            InspectorMode.Replay -> colors.tertiaryContainer to colors.onTertiaryContainer
            InspectorMode.Branch -> colors.surfaceContainerHighest to colors.onSurface
        }
    return InspectorModePresentation(panelText(resource = title), run, completenessText(value = state.inspector.completeness), background, foreground)
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
    var capability = panelText(resource = Res.string.unregistered_store)
    store.withCapability { capability = panelText(resource = Res.string.verbatim, it.name) }
    var revision = "-"
    store.withRevision { revision = it.toString() }
    val recording =
        when (val status = store.recording) {
            RecordingStatus.Unrecorded -> panelText(resource = Res.string.recording_none)
            is RecordingStatus.Attached -> panelText(resource = Res.string.recording_attached, status.steps)
            is RecordingStatus.Mismatch -> panelText(resource = Res.string.recording_mismatch)
        }
    val availability =
        when (val replay = state.inspector.replayability(store.id)) {
            Availability.Available -> listOf(InspectorNotice(panelText(resource = Res.string.replay_available), colors.primary))
            is Availability.Unavailable -> replay.reasons.map { InspectorNotice(panelText(resource = Res.string.replay_unavailable, it), colors.tertiary) }
        }
    return InspectorStorePresentation(
        store.id,
        capability,
        completenessText(value = store.completeness),
        if (store.completeness.isComplete) colors.primary else colors.error,
        if (state.filter == StoreFilter.Single(store.id)) colors.secondaryContainer else Color.Transparent,
        panelText(resource = Res.string.store_counts, store.records, store.processings, revision, panelText(resource = if (store.closed) Res.string.store_closed else Res.string.store_open), recording),
        store.completeness.reasons.map(InspectorText::reason),
        availability,
    )
}

internal fun inspectorTimeline(
    state: InspectorState,
    colors: ColorScheme,
): InspectorTimelinePresentation {
    val visible = state.visibleTimeline
    val heading =
        when (val filter = state.filter) {
            is StoreFilter.Single -> panelText(resource = Res.string.inspector_store_timeline, filter.store, visible.size)
            StoreFilter.All -> panelText(resource = Res.string.inspector_timeline, visible.size)
        }
    return InspectorTimelinePresentation(
        heading,
        visible.map { (index, item) ->
            val selected = state.selection == InspectorSelection.Position(index)
            InspectorTimelineRow(index, InspectorText.line(item), if (selected) colors.onPrimaryContainer else itemColor(item, colors), if (selected) colors.primaryContainer else Color.Transparent)
        },
    )
}

internal fun inspectorPosition(state: InspectorState): InspectorPositionPresentation {
    val selection = state.selection
    val heading = if (selection is InspectorSelection.Position) panelText(resource = Res.string.inspector_position_number, selection.index) else panelText(resource = Res.string.inspector_position)
    if (selection !is InspectorSelection.Position || selection.index !in state.inspector.timeline.indices) {
        return InspectorPositionPresentation(heading, InspectorPositionContent.Prompt(panelText(resource = Res.string.select_position)))
    }
    val item = state.inspector.timeline[selection.index]
    val notice =
        if (item is TimelineItem.Processing && item.attachment is ProcessingAttachment.JournalOnly) {
            listOf(panelText(resource = Res.string.journal_only_snapshots))
        } else {
            emptyList()
        }
    return InspectorPositionPresentation(heading, InspectorPositionContent.Journal(InspectorText.detail(item).mapIndexed { index, line -> InspectorDetailLine(line, if (index == 0) FontWeight.Bold else FontWeight.Normal) }, notice))
}

internal fun inspectorForwardAllowed(replay: ReplayControls<*, *, *, *>): Boolean = replay.canStepForward

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
