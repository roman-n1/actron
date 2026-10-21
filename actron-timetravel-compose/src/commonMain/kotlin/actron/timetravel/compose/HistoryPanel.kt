package actron.timetravel.compose

import actron.timetravel.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.time.Instant

/** Uses the device locale, time zone, and platform date/time conventions. */
@Composable
expect fun formatHistoryTime(instant: Instant): String

/** Selecting an entry immediately previews its immutable snapshot, including after eviction. */
@Stable
class HistoryPanelState<S : Any> {
    internal val inspection = HistoryInspection()
    var selection: List<HistoryEntry<S>> by mutableStateOf(emptyList())
        private set
    var preview: List<HistoryEntry<S>> by mutableStateOf(emptyList())
        private set

    fun select(entry: HistoryEntry<S>) {
        selection.filter { it.id != entry.id }.forEach { inspection.depart(it.id) }
        selection = listOf(entry)
        preview = selection
    }

    internal fun clearSelection() {
        selection = emptyList()
        preview = emptyList()
        inspection.clearNavigation()
    }

    internal fun navigate(
        entries: List<HistoryEntry<S>>,
        backward: Boolean,
    ) {
        inspection.retain(entries.map { it.id }.toSet())
        val history = if (backward) inspection.back else inspection.forward
        if (history.isEmpty()) return
        val destination = history.last()
        val current = selection.map { inspection.location(it.id) }
        if (backward) {
            inspection.back = history.dropLast(1)
            inspection.forward = (inspection.forward + current).takeLast(50)
        } else {
            inspection.forward = history.dropLast(1)
            inspection.back = (inspection.back + current).takeLast(50)
        }
        inspection.restore(destination)
        entries.filter { it.id == destination.id }.forEach {
            selection = listOf(it)
            preview = selection
        }
    }

    fun previewSelected() {
        preview = selection
    }

    fun returnToLive() {
        preview = emptyList()
    }
}

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
    HistoryPanelContent(timeline, state, {}, modifier, { HistoryContinuation.Unavailable(unavailable) }, graph, true, actions)
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
) {
    val entries by timeline.entries.collectAsState()
    val inspection = state.inspection
    val graphViewports = remember { mutableMapOf<String, GraphViewport>() }
    var picker by remember { mutableStateOf(false) }
    val machines = entries.map { it.machine }.distinctBy { it.stableId }
    val visible = remember(entries, inspection.filter, inspection.query, inspection.bookmarksOnly, inspection.bookmarks) { entries.filter(inspection::includes) }
    SideEffect { inspection.retain(entries.map { it.id }.toSet()) }
    // Bubble shortcuts after children: a text editor or the graph can consume its own keys first.
    Surface(
        modifier.testTag("history-panel").onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || !event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) {
                false
            } else {
                when (event.key) {
                    Key.DirectionLeft -> {
                        state.navigate(entries, true)
                        true
                    }

                    Key.DirectionRight -> {
                        state.navigate(entries, false)
                        true
                    }

                    Key.DirectionUp -> {
                        state.selection.forEach { selected -> visible.lastOrNull { it.id < selected.id }?.let(state::select) }
                        true
                    }

                    Key.DirectionDown -> {
                        state.selection.forEach { selected -> visible.firstOrNull { it.id > selected.id }?.let(state::select) }
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(historyText(HistoryText.Title), style = MaterialTheme.typography.titleMedium)
                    Muted(if (readOnly) historyText(HistoryText.ImportedRecording) else historyText(if (state.preview.isEmpty()) HistoryText.AppLive else HistoryText.AppPreview))
                }
                if (!readOnly) {
                    HistoryModeSwitch(
                        history = state.preview.isNotEmpty(),
                        historyAvailable = state.selection.isNotEmpty() || visible.isNotEmpty(),
                        onHistory = {
                            if (state.selection.isNotEmpty()) {
                                state.previewSelected()
                            } else if (visible.isNotEmpty()) {
                                state.select(visible.last())
                            }
                        },
                        onLive = state::returnToLive,
                    )
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { picker = true }, modifier = Modifier.testTag("history-machine-filter")) {
                        Text(
                            when (val filter = inspection.filter) {
                                HistoryMachineFilter.All -> historyText(HistoryText.AllMachines)
                                is HistoryMachineFilter.Machine -> machines.filter { it.stableId == filter.stableId }.fold(historyText(HistoryText.PreviousInstance)) { _, machine -> machine.name }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        HistoryArrow(down = true)
                    }
                    DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                        DropdownMenuItem(text = { Text(historyText(HistoryText.AllMachines)) }, onClick = {
                            inspection.filter = HistoryMachineFilter.All
                            picker = false
                        })
                        machines.forEach { machine ->
                            DropdownMenuItem(text = { Text(machine.name) }, onClick = {
                                inspection.filter = HistoryMachineFilter.Machine(machine.stableId)
                                picker = false
                            })
                        }
                    }
                }
                actions()
            }
            HorizontalDivider()
            BoxWithConstraints(Modifier.weight(1f)) {
                val narrow = maxWidth < 640.dp
                var listExpanded by remember { mutableStateOf(true) }
                val listState = rememberLazyListState()
                LaunchedEffect(state.selection, inspection.filter, inspection.query, inspection.bookmarksOnly, if (inspection.bookmarksOnly) inspection.bookmarks else emptySet<Long>(), listExpanded) {
                    state.selection.forEach { selected ->
                        val index = visible.indexOfFirst { it.id == selected.id }
                        if (index >= 0) listState.scrollToItem(index)
                    }
                }
                val list: @Composable (Modifier) -> Unit = { listModifier ->
                    Column(listModifier) {
                        OutlinedTextField(
                            inspection.query,
                            { inspection.query = it },
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("history-search"),
                            label = { Text(historyText(HistoryText.Search)) },
                            singleLine = true,
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Muted("${visible.size} / ${entries.size} ${historyText(HistoryText.Events)} · ${historyText(HistoryText.Capacity)} ${timeline.capacity}", Modifier.weight(1f))
                            Checkbox(inspection.bookmarksOnly, { inspection.bookmarksOnly = it }, Modifier.testTag("history-bookmarks-only"))
                            Text(historyText(HistoryText.Bookmarks), style = MaterialTheme.typography.labelMedium)
                        }
                        LazyColumn(Modifier.weight(1f).testTag("history-events"), state = listState) {
                            items(visible, key = { it.id }) { entry ->
                                EventRow(entry, state.selection.any { it.id == entry.id }, entry.id in inspection.bookmarks, onBookmark = { inspection.toggleBookmark(entry.id) }) {
                                    state.select(entry)
                                    if (narrow) listExpanded = false
                                }
                            }
                        }
                        if (visible.isEmpty()) Text(historyText(if (entries.isEmpty()) HistoryText.Empty else HistoryText.NoMatches), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                val detail: @Composable (Modifier) -> Unit = { detailModifier ->
                    if (state.selection.isEmpty()) {
                        Box(detailModifier.padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(historyText(HistoryText.Explore), style = MaterialTheme.typography.titleMedium)
                                Muted(historyText(HistoryText.SelectHint))
                            }
                        }
                    } else {
                        state.selection.forEach { entry ->
                            Column(detailModifier) {
                                HistoryNavigation(entry, entries, visible, state) {
                                    inspection.reveal()
                                    listExpanded = true
                                }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    HistoryChoice(!inspection.graph, { inspection.graph = false }, { Text(historyText(HistoryText.Event)) }, modifier = Modifier.testTag("history-event-tab"))
                                    HistoryChoice(inspection.graph, { inspection.graph = true }, { Text(historyText(HistoryText.Graph)) }, modifier = Modifier.testTag("history-graph-tab"))
                                }
                                if (inspection.graph) {
                                    key(entry.machine.stableId) { MachineGraph(graph(entry), graphViewports.getOrPut(entry.machine.stableId) { GraphViewport() }, Modifier.weight(1f).fillMaxWidth()) }
                                } else {
                                    EventDetail(entry, entries, state, { selected ->
                                        val previousHead = timeline.entries.value.fold(selected.id) { id, item -> maxOf(id, item.id) }
                                        onContinue(selected)
                                        timeline.truncateAfter(selected.id, previousHead)
                                        state.clearSelection()
                                    }, continuation, Modifier.weight(1f).fillMaxWidth(), readOnly)
                                }
                            }
                        }
                    }
                }
                if (!narrow) {
                    Row(Modifier.fillMaxSize()) {
                        list(Modifier.weight(0.4f).fillMaxHeight())
                        VerticalDivider()
                        detail(Modifier.weight(0.6f).fillMaxHeight())
                    }
                } else if (state.selection.isEmpty()) {
                    list(Modifier.fillMaxSize())
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Muted("${visible.size} / ${entries.size} ${historyText(HistoryText.Events)}", Modifier.weight(1f))
                            TextButton({ listExpanded = !listExpanded }, Modifier.testTag("history-toggle-list")) {
                                Text(historyText(if (listExpanded) HistoryText.HideEventList else HistoryText.ShowEventList))
                            }
                        }
                        if (listExpanded) list(Modifier.weight(0.4f).fillMaxWidth())
                        HorizontalDivider()
                        detail(Modifier.weight(if (listExpanded) 0.6f else 1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun Muted(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun HistoryDetails.activeBefore(): String = before.filter { it.path == "active" || it.path == "screen" }.joinToString { it.value }.ifEmpty { "—" }

private fun HistoryDetails.activeAfter(): String = after.filter { it.path == "active" || it.path == "screen" }.joinToString { it.value }.ifEmpty { "—" }

/** One cursor bar for both the complete machine graph and the graphical event details. */
@Composable
private fun <S : Any> HistoryNavigation(
    entry: HistoryEntry<S>,
    entries: List<HistoryEntry<S>>,
    visible: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
    onReveal: () -> Unit,
) {
    val inspection = state.inspection
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("history-navigation")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${entry.machine.name} · #${entry.id}", style = MaterialTheme.typography.titleSmall)
                Muted(formatHistoryTime(entry.at))
            }
            IconButton(
                { visible.lastOrNull { it.id < entry.id }?.let(state::select) },
                enabled = visible.any { it.id < entry.id },
                modifier = Modifier.testTag("history-previous").semantics { contentDescription = HistoryText.PreviousEvent.forLanguage(androidx.compose.ui.text.intl.Locale.current.language) },
            ) { HistoryArrow(back = true) }
            IconButton(
                { visible.firstOrNull { it.id > entry.id }?.let(state::select) },
                enabled = visible.any { it.id > entry.id },
                modifier = Modifier.testTag("history-next").semantics { contentDescription = HistoryText.NextEvent.forLanguage(androidx.compose.ui.text.intl.Locale.current.language) },
            ) { HistoryArrow() }
            Box {
                TextButton({ menu = true }, Modifier.testTag("history-navigation-menu")) { Text(historyText(HistoryText.More)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(historyText(HistoryText.FirstEvent)) }, onClick = {
                        visible.firstOrNull()?.let(state::select)
                        menu = false
                    }, enabled = visible.isNotEmpty(), modifier = Modifier.testTag("history-first"))
                    DropdownMenuItem(text = { Text(historyText(HistoryText.LastEvent)) }, onClick = {
                        visible.lastOrNull()?.let(state::select)
                        menu = false
                    }, enabled = visible.isNotEmpty(), modifier = Modifier.testTag("history-last"))
                    DropdownMenuItem(text = { Text(historyText(HistoryText.NavigationBack)) }, onClick = {
                        state.navigate(entries, true)
                        menu = false
                    }, enabled = inspection.back.isNotEmpty(), modifier = Modifier.testTag("history-navigation-back"))
                    DropdownMenuItem(text = { Text(historyText(HistoryText.NavigationForward)) }, onClick = {
                        state.navigate(entries, false)
                        menu = false
                    }, enabled = inspection.forward.isNotEmpty())
                    DropdownMenuItem(text = { Text(historyText(HistoryText.PinComparison)) }, onClick = {
                        inspection.comparison = HistoryComparison.Against(entry.id, entry.machine.stableId, entry.details.after.toList())
                        menu = false
                    }, modifier = Modifier.testTag("history-pin-comparison"))
                }
            }
        }
        if (visible.none { it.id == entry.id }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted(historyText(if (entries.any { it.id == entry.id }) HistoryText.HiddenSelection else HistoryText.OutsideWindow), Modifier.weight(1f).testTag("history-selection-status"))
                if (entries.any { it.id == entry.id }) {
                    TextButton(onReveal, Modifier.testTag("history-reveal-selection")) { Text(historyText(HistoryText.Reveal)) }
                }
            }
        }
    }
}

@Composable
private fun <S : Any> EventRow(
    entry: HistoryEntry<S>,
    selected: Boolean,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .clickable(onClick = onSelect)
            .testTag("history-entry-${entry.id}")
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) colors.primary else colors.surface))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Muted("#${entry.id} · ${entry.machine.name}")
            Text(actionSummary(entry.details.input), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted(entry.details.activeBefore(), Modifier.weight(1f))
                HistoryArrow()
                Muted(entry.details.activeAfter(), Modifier.weight(1f))
            }
            Muted(formatHistoryTime(entry.at))
        }
        val bookmarkLabel = historyText(if (bookmarked) HistoryText.RemoveBookmark else HistoryText.AddBookmark)
        IconToggleButton(
            checked = bookmarked,
            onCheckedChange = { onBookmark() },
            modifier =
                Modifier
                    .padding(top = 4.dp, end = 4.dp)
                    .size(40.dp)
                    .testTag("history-entry-bookmark-${entry.id}")
                    .semantics { contentDescription = bookmarkLabel },
        ) {
            val color = colors.onSurfaceVariant
            Canvas(Modifier.size(18.dp)) {
                val outline =
                    Path().apply {
                        moveTo(size.width * 0.2f, size.height * 0.1f)
                        lineTo(size.width * 0.8f, size.height * 0.1f)
                        lineTo(size.width * 0.8f, size.height * 0.9f)
                        lineTo(size.width * 0.5f, size.height * 0.68f)
                        lineTo(size.width * 0.2f, size.height * 0.9f)
                        close()
                    }
                drawPath(outline, color, style = if (bookmarked) Fill else Stroke(1.5.dp.toPx()))
            }
        }
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun <S : Any> EventDetail(
    entry: HistoryEntry<S>,
    entries: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
    onContinue: (HistoryEntry<S>) -> Unit,
    continuation: (S) -> HistoryContinuation,
    modifier: Modifier,
    readOnly: Boolean,
) {
    Column(modifier) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            key(entry.id) { TransitionDiagram(entry.details) }
            Muted("${entry.details.source} · ${entry.details.outcome}")
            HistoryFields(entry, entries, state)
        }
        if (!readOnly) {
            val capability = continuation(entry.snapshot)
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = {
                        onContinue(entry)
                        state.returnToLive()
                    },
                    enabled = state.preview.any { it.id == entry.id } && capability is HistoryContinuation.Available,
                    modifier = Modifier.fillMaxWidth().testTag("history-continue"),
                    shape = RoundedCornerShape(8.dp),
                ) { Text(historyText(HistoryText.Continue)) }
                Muted(
                    when (capability) {
                        is HistoryContinuation.Unavailable -> {
                            capability.reason
                        }

                        is HistoryContinuation.Available -> {
                            if (state.preview.isNotEmpty()) {
                                "${capability.consequences}\n${historyText(HistoryText.ContinueHint)} ${entries.count { it.id > entry.id }}"
                            } else {
                                historyText(HistoryText.SelectToView)
                            }
                        }
                    },
                    Modifier.testTag("history-continuation-reason"),
                )
            }
        }
    }
}

@Composable
private fun StateCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Muted(label)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoryModeSwitch(
    history: Boolean,
    historyAvailable: Boolean,
    onHistory: () -> Unit,
    onLive: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceContainerHighest) {
        Row(Modifier.padding(3.dp).selectableGroup()) {
            listOf(true, false).forEach { isHistory ->
                val selected = history == isHistory
                val enabled = !isHistory || historyAvailable
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (selected) colors.primary else colors.surfaceContainerHighest,
                    contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                ) {
                    Text(
                        if (isHistory) historyText(HistoryText.History) else historyText(HistoryText.Live),
                        Modifier
                            .selectable(
                                selected = selected,
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = if (isHistory) onHistory else onLive,
                            ).testTag(if (isHistory) "history-mode-history" else "history-live")
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransitionDiagram(details: HistoryDetails) {
    var expanded by remember { mutableStateOf(false) }
    val lineColor = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth().testTag("history-transition"), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted(historyText(HistoryText.Action))
                SelectionContainer {
                    Text(if (expanded) details.input else actionSummary(details.input), Modifier.testTag("history-selected-input"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                if (actionSummary(details.input) != details.input) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("history-action-payload")) {
                        Text(historyText(if (expanded) HistoryText.HidePayload else HistoryText.ShowPayload))
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val left = size.width * 0.24f
            val right = size.width * 0.76f
            val stroke = 1.5.dp.toPx()
            val tip = 5.dp.toPx()
            drawLine(lineColor, Offset(left, size.height), Offset(left, 0f), stroke)
            drawLine(lineColor, Offset(left, 0f), Offset(left - tip, tip), stroke)
            drawLine(lineColor, Offset(left, 0f), Offset(left + tip, tip), stroke)
            drawLine(lineColor, Offset(right, 0f), Offset(right, size.height), stroke)
            drawLine(lineColor, Offset(right, size.height), Offset(right - tip, size.height - tip), stroke)
            drawLine(lineColor, Offset(right, size.height), Offset(right + tip, size.height - tip), stroke)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StateCard(historyText(HistoryText.StateBefore), details.activeBefore(), Modifier.weight(1f))
            StateCard(historyText(HistoryText.StateAfter), details.activeAfter(), Modifier.weight(1f))
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

/** Drawn geometry works even when the platform font lacks arrow glyphs. */
@Composable
private fun HistoryArrow(
    back: Boolean = false,
    down: Boolean = false,
) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.6.dp.toPx()

        fun point(
            x: Float,
            y: Float,
        ): Offset = if (down) Offset(y * size.width, x * size.height) else Offset((if (back) 1f - x else x) * size.width, y * size.height)
        if (!down) drawLine(color, point(0.15f, 0.5f), point(0.8f, 0.5f), stroke)
        drawLine(color, point(0.5f, 0.2f), point(0.8f, 0.5f), stroke)
        drawLine(color, point(0.5f, 0.8f), point(0.8f, 0.5f), stroke)
    }
}
