package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import actron.timetravel.HistoryRecording
import actron.timetravel.HistoryTimeline
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlin.time.Instant

/** Uses the device locale, time zone, and platform date/time conventions. */
@Composable
expect fun formatHistoryTime(instant: Instant): String

/** Reusable inspector. The host renders preview and owns restoration; selection never runs handlers. */
@Composable
fun <S : Any> HistoryPanel(
    timeline: HistoryTimeline<S>,
    state: HistoryPanelState<S>,
    onContinue: (HistoryEntry<S>) -> Unit,
    modifier: Modifier = Modifier,
    canContinue: (S) -> Boolean = { true },
    graph: (HistoryEntry<S>) -> HistoryGraph = { HistoryGraph.Unavailable },
    actions: @Composable RowScope.() -> Unit = {},
) {
    val ready = historyText(HistoryText.ContinueReady)
    val unavailable = historyText(HistoryText.Unsafe)
    HistoryPanelContent(timeline, state, onContinue, modifier, { snapshot ->
        if (canContinue(snapshot)) HistoryContinuation.Available(ready) else HistoryContinuation.Unavailable(unavailable)
    }, graph, false, actions)
}

/** Inspector with explicit host-supplied restoration consequences or an unavailable reason. */
@Composable
fun <S : Any> HistoryPanel(
    timeline: HistoryTimeline<S>,
    state: HistoryPanelState<S>,
    onContinue: (HistoryEntry<S>) -> Unit,
    continuation: (S) -> HistoryContinuation,
    modifier: Modifier = Modifier,
    graph: (HistoryEntry<S>) -> HistoryGraph = { HistoryGraph.Unavailable },
    actions: @Composable RowScope.() -> Unit = {},
) {
    HistoryPanelContent(timeline, state, onContinue, modifier, continuation, graph, false, actions)
}

/** Detached recording viewer: deliberately exposes no continuation callback or live mode. */
@Composable
fun <S : Any> HistoryRecordingPanel(
    recording: HistoryRecording<S>,
    state: HistoryPanelState<S>,
    modifier: Modifier = Modifier,
    graph: (HistoryEntry<S>) -> HistoryGraph = { HistoryGraph.Unavailable },
    actions: @Composable RowScope.() -> Unit = {},
) {
    val timeline = remember(recording) { HistoryTimeline.fromRecording(recording) }
    val unavailable = historyText(HistoryText.ImportedRecording)
    HistoryPanelContent(timeline, state, {}, modifier, { HistoryContinuation.Unavailable(unavailable) }, graph, true, actions, recording)
}

@Composable
private fun <S : Any> HistoryPanelContent(
    timeline: HistoryTimeline<S>,
    state: HistoryPanelState<S>,
    onContinue: (HistoryEntry<S>) -> Unit,
    modifier: Modifier,
    continuation: (S) -> HistoryContinuation,
    graph: (HistoryEntry<S>) -> HistoryGraph,
    readOnly: Boolean,
    actions: @Composable RowScope.() -> Unit,
    source: Any = timeline,
) {
    remember(state, source) { state.bindSource(source) }
    val entries by timeline.entries.collectAsState()
    val inspection = state.inspection
    val graphViewports = remember { mutableMapOf<String, GraphViewport>() }
    val visible = remember(entries, inspection.filter, inspection.query, inspection.bookmarksOnly, inspection.bookmarks) { entries.filter(inspection::includes) }
    SideEffect { inspection.retain(entries.map { it.id }.toSet()) }
    Surface(modifier.testTag("history-panel").historyNavigation(state, entries, visible), color = MaterialTheme.colorScheme.surface) {
        Column {
            HistoryPanelHeader(state, visible, readOnly)
            HorizontalDivider()
            HistoryMachinePicker(entries, inspection, actions)
            HorizontalDivider()
            HistoryPanelBody(timeline, state, entries, visible, graphViewports, graph, { selected ->
                val previousHead = timeline.entries.value.fold(selected.id) { id, item -> maxOf(id, item.id) }
                onContinue(selected)
                timeline.truncateAfter(selected.id, previousHead)
                state.clearSelection()
            }, continuation, readOnly, Modifier.weight(1f))
        }
    }
}

/** Short inputs stay intact; complete payloads remain available on demand. */
internal fun actionSummary(input: String): String = if (input.length <= 100 && '\n' !in input) input else input.substringBefore('(').substringBefore('\n').take(80)

/** Shorten serializer type discriminators only; ordinary domain values remain untouched. */
internal fun compactFieldValue(
    path: String,
    value: String,
): String = if (path == "type" || path.endsWith(".type")) value.replace(Regex("(?<![A-Za-z0-9_])(?:[a-z_][A-Za-z0-9_]*\\.)+"), "") else value
