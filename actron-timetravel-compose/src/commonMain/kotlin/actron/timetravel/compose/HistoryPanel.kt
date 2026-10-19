package actron.timetravel.compose

import actron.timetravel.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var selection: List<HistoryEntry<S>> by mutableStateOf(emptyList())
        private set
    var preview: List<HistoryEntry<S>> by mutableStateOf(emptyList())
        private set

    fun select(entry: HistoryEntry<S>) {
        selection = listOf(entry)
        preview = selection
    }

    internal fun clearSelection() {
        selection = emptyList()
        preview = emptyList()
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
) {
    val entries by timeline.entries.collectAsState()
    var filter by remember { mutableStateOf(emptyList<String>()) }
    var picker by remember { mutableStateOf(false) }
    val machines = entries.map { it.machine }.filter { it.kind == HistorySourceKind.Machine }.distinctBy { it.stableId }
    val visible = entries.filter { filter.isEmpty() || (it.machine.kind == HistorySourceKind.Machine && it.machine.stableId in filter) }
    Surface(modifier.testTag("history-panel"), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(historyText(HistoryText.Title), style = MaterialTheme.typography.titleMedium)
                    Muted(historyText(HistoryText.Session))
                }
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
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { picker = true }, modifier = Modifier.testTag("history-machine-filter")) {
                        Text(if (filter.isEmpty()) historyText(HistoryText.AllMachines) else machines.filter { it.stableId in filter }.fold(historyText(HistoryText.PreviousInstance)) { _, machine -> machine.name })
                        Spacer(Modifier.width(8.dp))
                        HistoryArrow(down = true)
                    }
                    DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                        DropdownMenuItem(text = { Text(historyText(HistoryText.AllMachines)) }, onClick = {
                            if (filter.isNotEmpty()) state.clearSelection()
                            filter = emptyList()
                            picker = false
                        })
                        machines.forEach { machine ->
                            DropdownMenuItem(text = { Text(machine.name) }, onClick = {
                                if (filter != listOf(machine.stableId)) state.clearSelection()
                                filter = listOf(machine.stableId)
                                picker = false
                            })
                        }
                    }
                }
            }
            HorizontalDivider()
            BoxWithConstraints(Modifier.weight(1f)) {
                val list: @Composable (Modifier) -> Unit = { listModifier ->
                    Column(listModifier) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Muted("${historyText(HistoryText.Timeline)} (${entries.size} / ${timeline.capacity})")
                            Muted("${visible.size} ${historyText(HistoryText.Events)}")
                        }
                        LazyColumn(Modifier.weight(1f).testTag("history-events")) {
                            items(visible, key = { it.id }) { entry ->
                                EventRow(entry, state.selection.any { it.id == entry.id }) { state.select(entry) }
                            }
                        }
                        if (visible.isEmpty()) Text(historyText(HistoryText.Empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
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
                            EventDetail(entry, visible, state, { selected ->
                                val previousHead = timeline.entries.value.fold(selected.id) { id, item -> maxOf(id, item.id) }
                                onContinue(selected)
                                timeline.truncateAfter(selected.id, previousHead)
                                state.clearSelection()
                            }, canContinue, detailModifier)
                        }
                    }
                }
                if (maxWidth >= 640.dp) {
                    Row(Modifier.fillMaxSize()) {
                        list(Modifier.weight(0.4f).fillMaxHeight())
                        VerticalDivider()
                        detail(Modifier.weight(0.6f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        list(Modifier.weight(0.4f).fillMaxWidth())
                        HorizontalDivider()
                        detail(Modifier.weight(0.6f).fillMaxWidth())
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

@Composable
private fun <S : Any> EventRow(
    entry: HistoryEntry<S>,
    selected: Boolean,
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
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Muted("#${entry.id} · ${entry.machine.name}" + if (entry.machine.kind == HistorySourceKind.Navigation) " · ${historyText(HistoryText.AppEvent)}" else "")
            Text(actionSummary(entry.details.input), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted(entry.details.activeBefore(), Modifier.weight(1f))
                HistoryArrow()
                Muted(entry.details.activeAfter(), Modifier.weight(1f))
            }
            Muted(formatHistoryTime(entry.at))
        }
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun <S : Any> EventDetail(
    entry: HistoryEntry<S>,
    visible: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
    onContinue: (HistoryEntry<S>) -> Unit,
    canContinue: (S) -> Boolean,
    modifier: Modifier,
) {
    var full by remember { mutableStateOf(false) }
    val index = visible.indexOfFirst { it.id == entry.id }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${historyText(HistoryText.Event)} #${entry.id}", style = MaterialTheme.typography.titleSmall)
                Muted(entry.machine.name)
            }
            val previousLabel = historyText(HistoryText.PreviousEvent)
            val nextLabel = historyText(HistoryText.NextEvent)
            IconButton(onClick = { state.select(visible[index - 1]) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = previousLabel }) { HistoryArrow(back = true) }
            IconButton(onClick = { state.select(visible[index + 1]) }, enabled = index >= 0 && index < visible.lastIndex, modifier = Modifier.semantics { contentDescription = nextLabel }) { HistoryArrow() }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            key(entry.id) { TransitionDiagram(entry.details) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !full, onClick = { full = false }, label = { Text(historyText(HistoryText.Changes)) })
                FilterChip(selected = full, onClick = { full = true }, label = { Text(historyText(HistoryText.FullState)) })
            }
            SelectionContainer {
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Muted(historyText(HistoryText.Field), Modifier.weight(0.8f))
                        Muted(historyText(HistoryText.Before), Modifier.weight(1f))
                        Muted(historyText(HistoryText.After), Modifier.weight(1f))
                    }
                    HorizontalDivider()
                    val changes =
                        if (full) {
                            val before = entry.details.before.associate { it.path to it.value }
                            val after = entry.details.after.associate { it.path to it.value }
                            (before.keys + after.keys).map { StateChange(it, before.getOrElse(it) { "—" }, after.getOrElse(it) { "—" }) }
                        } else {
                            entry.details.changes
                        }
                    changes.forEach { change ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(change.path, Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
                            Text(if (full) change.before else compactFieldValue(change.path, change.before), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (full) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                            Text(if (full) change.after else compactFieldValue(change.path, change.after), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = if (full) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    if (changes.isEmpty()) Muted(historyText(HistoryText.Unchanged), Modifier.padding(vertical = 12.dp))
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = {
                    onContinue(entry)
                    state.returnToLive()
                },
                enabled = state.preview.any { it.id == entry.id } && canContinue(entry.snapshot),
                modifier = Modifier.fillMaxWidth().testTag("history-continue"),
                shape = RoundedCornerShape(8.dp),
            ) { Text(historyText(HistoryText.Continue)) }
            Muted(
                if (!canContinue(entry.snapshot)) {
                    historyText(HistoryText.Unsafe)
                } else if (state.preview.isNotEmpty()) {
                    historyText(HistoryText.ContinueHint)
                } else {
                    historyText(HistoryText.SelectToView)
                },
            )
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
