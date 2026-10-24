package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import actron.timetravel.StateChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun <S : Any> HistoryFields(
    entry: HistoryEntry<S>,
    entries: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
) {
    val inspection = state.inspection
    val comparison = inspection.comparison
    val before =
        when (comparison) {
            HistoryComparison.PreviousEvent -> entry.details.before
            is HistoryComparison.Against -> if (comparison.machine == entry.machine.stableId) comparison.fields else entry.details.before
        }
    if (comparison is HistoryComparison.Against) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (comparison.machine == entry.machine.stableId) "A: #${comparison.id} → B: #${entry.id}" else historyText(HistoryText.ComparisonDifferentMachine),
                Modifier.weight(1f).testTag("history-comparison"),
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton({ inspection.comparison = HistoryComparison.PreviousEvent }, Modifier.testTag("history-clear-comparison")) { Text(historyText(HistoryText.ClearComparison)) }
        }
        Text(historyText(HistoryText.ComparisonDiagramHint), style = MaterialTheme.typography.labelSmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryChoice(!inspection.fullState, { inspection.fullState = false }, { Text(historyText(HistoryText.Changes)) })
        HistoryChoice(inspection.fullState, { inspection.fullState = true }, { Text(historyText(HistoryText.FullState)) })
    }
    OutlinedTextField(
        inspection.fieldQuery,
        { inspection.fieldQuery = it },
        Modifier.fillMaxWidth().historyTextInput().testTag("history-field-search"),
        label = { Text(historyText(HistoryText.SearchFields)) },
        singleLine = true,
    )
    when (val focus = inspection.focus) {
        HistoryFieldFocus.All -> {
            Unit
        }

        is HistoryFieldFocus.Path -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${historyText(HistoryText.PinnedField)}: ${focus.path}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                TextButton({ inspection.focus = HistoryFieldFocus.All }, Modifier.testTag("history-unpin-field")) { Text(historyText(HistoryText.AllFields)) }
            }
        }
    }
    val changes =
        historyChanges(before, entry.details.after, inspection.fullState || inspection.focus is HistoryFieldFocus.Path).filter { change ->
            val focused =
                when (val focus = inspection.focus) {
                    HistoryFieldFocus.All -> true
                    is HistoryFieldFocus.Path -> focus.path == change.path
                }
            focused && change.path.contains(inspection.fieldQuery.trim(), ignoreCase = true)
        }
    changes.forEach { change ->
        key(entry.id, change.path, comparison) {
            HistoryField(change, entry, entries, state)
        }
    }
    if (changes.isEmpty()) Text(historyText(if (inspection.fieldQuery.isNotBlank() || inspection.focus is HistoryFieldFocus.Path) HistoryText.NoFields else HistoryText.Unchanged), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun <S : Any> HistoryField(
    change: StateChange,
    entry: HistoryEntry<S>,
    entries: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
) {
    var expanded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val full = state.inspection.fullState || expanded
    Column(Modifier.fillMaxWidth().testTag("history-field-${change.path}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(change.path, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Box {
                TextButton({ menu = true }, Modifier.testTag("history-field-menu-${change.path}")) { Text(historyText(HistoryText.More)) }
                DropdownMenu(menu, { menu = false }) {
                    val previous = entries.filter { it.id < entry.id && it.machine.stableId == entry.machine.stableId && historyFieldChanges(it, change.path) }
                    val next = entries.filter { it.id > entry.id && it.machine.stableId == entry.machine.stableId && historyFieldChanges(it, change.path) }
                    DropdownMenuItem(
                        text = { Text(historyText(HistoryText.PinField)) },
                        onClick = {
                            state.inspection.focus = HistoryFieldFocus.Path(change.path)
                            menu = false
                        },
                        modifier = Modifier.testTag("history-pin-field"),
                    )
                    DropdownMenuItem(
                        text = { Text(historyText(HistoryText.PreviousChange)) },
                        onClick = {
                            previous.lastOrNull()?.let(state::select)
                            menu = false
                        },
                        enabled = previous.isNotEmpty(),
                        modifier = Modifier.testTag("history-field-previous"),
                    )
                    DropdownMenuItem(
                        text = { Text(historyText(HistoryText.NextChange)) },
                        onClick = {
                            next.firstOrNull()?.let(state::select)
                            menu = false
                        },
                        enabled = next.isNotEmpty(),
                        modifier = Modifier.testTag("history-field-next"),
                    )
                    if (!state.inspection.fullState) {
                        DropdownMenuItem(
                            text = { Text(historyText(if (expanded) HistoryText.CompactValue else HistoryText.FullValue)) },
                            onClick = {
                                expanded = !expanded
                                menu = false
                            },
                            modifier = Modifier.testTag("history-field-expand-${change.path}"),
                        )
                    }
                    Text(historyText(HistoryText.FieldSearchWindow), Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        SelectionContainer {
            BoxWithConstraints {
                val values: @Composable (Boolean, Modifier) -> Unit = { after, modifier ->
                    val value = if (after) change.after else change.before
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val comparison = state.inspection.comparison
                        val label =
                            if (comparison is HistoryComparison.Against && comparison.machine == entry.machine.stableId) {
                                if (after) "B: #${entry.id}" else "A: #${comparison.id}"
                            } else {
                                historyText(if (after) HistoryText.After else HistoryText.Before)
                            }
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (full) value else compactFieldValue(change.path, value), style = MaterialTheme.typography.bodySmall, maxLines = if (full) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (maxWidth >= 400.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        values(false, Modifier.weight(1f))
                        values(true, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        values(false, Modifier.fillMaxWidth())
                        values(true, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
