package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import actron.timetravel.HistoryTimeline
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp

/** Keeps list scroll/collapse state alive while the selected event or layout changes. */
@Composable
internal fun <S : Any> HistoryPanelBody(
    timeline: HistoryTimeline<S>,
    state: HistoryPanelState<S>,
    entries: List<HistoryEntry<S>>,
    visible: List<HistoryEntry<S>>,
    graphViewports: MutableMap<String, GraphViewport>,
    graph: (HistoryEntry<S>) -> HistoryGraph,
    onContinue: (HistoryEntry<S>) -> Unit,
    continuation: (S) -> HistoryContinuation,
    readOnly: Boolean,
    modifier: Modifier,
) {
    val inspection = state.inspection
    val counter = historyEventCounter(state, entries, timeline.capacity, Locale.current.language)
    BoxWithConstraints(modifier) {
        val narrow = maxWidth < 640.dp
        val listExpanded = remember { mutableStateOf(true) }
        val listState = rememberLazyListState()
        LaunchedEffect(state.scrollRequest, inspection.filter, inspection.query, inspection.bookmarksOnly, if (inspection.bookmarksOnly) inspection.bookmarks else emptySet<Long>(), listExpanded.value) {
            state.selection.forEach { selected ->
                val index = visible.indexOfFirst { it.id == selected.id }
                if (index >= 0) listState.scrollToItem(index)
            }
        }
        val list: @Composable (Modifier) -> Unit = { listModifier ->
            HistoryEventList(entries, visible, state, timeline.capacity, listState, listModifier) { entry ->
                state.selectInList(entry)
                if (narrow) listExpanded.value = false
            }
        }
        val detail: @Composable (Modifier) -> Unit = { detailModifier ->
            HistorySelectionDetail(entries, visible, state, graphViewports, graph, onContinue, continuation, readOnly, detailModifier) {
                inspection.reveal()
                listExpanded.value = true
            }
        }
        HistoryResponsiveLayout(narrow, state.selection.isNotEmpty(), listExpanded.value, counter, { listExpanded.toggle() }, list, detail)
    }
}

/** Placement only; selection and the lifetime of scroll/graph state belong to the host above. */
@Composable
private fun HistoryResponsiveLayout(
    narrow: Boolean,
    hasSelection: Boolean,
    listExpanded: Boolean,
    counter: String,
    onToggleList: () -> Unit,
    list: @Composable (Modifier) -> Unit,
    detail: @Composable (Modifier) -> Unit,
) {
    when (historyPanelPlacement(narrow, hasSelection)) {
        HistoryPanelPlacement.Wide -> {
            Row(Modifier.fillMaxSize()) {
                list(Modifier.weight(0.4f).fillMaxHeight())
                VerticalDivider()
                detail(Modifier.weight(0.6f).fillMaxHeight())
            }
            return
        }

        HistoryPanelPlacement.ListOnly -> {
            list(Modifier.fillMaxSize())
            return
        }

        HistoryPanelPlacement.Selected -> {
            Unit
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            HistoryMuted(counter, Modifier.weight(1f).testTag("history-collapsed-event-counter"))
            TextButton(onToggleList, Modifier.testTag("history-toggle-list")) {
                Text(historyText(if (listExpanded) HistoryText.HideEventList else HistoryText.ShowEventList))
            }
        }
        if (listExpanded) list(Modifier.weight(0.4f).fillMaxWidth())
        HorizontalDivider()
        detail(Modifier.weight(if (listExpanded) 0.6f else 1f).fillMaxWidth())
    }
}

@Composable
private fun <S : Any> HistorySelectionDetail(
    entries: List<HistoryEntry<S>>,
    visible: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
    graphViewports: MutableMap<String, GraphViewport>,
    graph: (HistoryEntry<S>) -> HistoryGraph,
    onContinue: (HistoryEntry<S>) -> Unit,
    continuation: (S) -> HistoryContinuation,
    readOnly: Boolean,
    modifier: Modifier,
    onReveal: () -> Unit,
) {
    val inspection = state.inspection
    if (state.selection.isEmpty()) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(historyText(HistoryText.Explore), style = MaterialTheme.typography.titleMedium)
                HistoryMuted(historyText(HistoryText.SelectHint))
            }
        }
    } else {
        state.selection.forEach { entry ->
            Column(modifier) {
                HistoryNavigation(entry, entries, visible, state) {
                    onReveal()
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryChoice(historyEventSelected(inspection), { inspection.graph = false }, { Text(historyText(HistoryText.Event)) }, modifier = Modifier.testTag("history-event-tab"))
                    HistoryChoice(inspection.graph, { inspection.graph = true }, { Text(historyText(HistoryText.Graph)) }, modifier = Modifier.testTag("history-graph-tab"))
                }
                if (inspection.graph) {
                    key(entry.machine.stableId) { MachineGraph(graph(entry), graphViewports.getOrPut(entry.machine.stableId) { GraphViewport() }, Modifier.weight(1f).fillMaxWidth()) }
                } else {
                    HistoryEventDetail(entry, entries, state, onContinue, continuation, Modifier.weight(1f).fillMaxWidth(), readOnly)
                }
            }
        }
    }
}
