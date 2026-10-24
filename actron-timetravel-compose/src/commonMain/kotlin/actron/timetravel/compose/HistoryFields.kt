package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import actron.timetravel.StateChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val changes = inspection.changes(entry)
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
    val full = state.inspection.fullState || expanded
    Column(Modifier.fillMaxWidth().testTag("history-field-${change.path}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(change.path, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            HistoryFieldMenu(change, entry, entries, state, expanded) { expanded = !expanded }
        }
        HistoryFieldValues(change, entry, state.inspection.comparison, full)
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun <S : Any> HistoryFieldMenu(
    change: StateChange,
    entry: HistoryEntry<S>,
    entries: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
    expanded: Boolean,
    toggleExpanded: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton({ menu = true }, Modifier.testTag("history-field-menu-${change.path}")) { Text(historyText(HistoryText.More)) }
        DropdownMenu(menu, { menu = false }) {
            HistoryFieldMenuItems(change, entry, entries, state, expanded, toggleExpanded) { menu = false }
        }
    }
}

@Composable
private fun <S : Any> HistoryFieldMenuItems(
    change: StateChange,
    entry: HistoryEntry<S>,
    entries: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
    expanded: Boolean,
    toggleExpanded: () -> Unit,
    dismiss: () -> Unit,
) {
    val previous = entries.filter { it.id < entry.id && it.machine.stableId == entry.machine.stableId && historyFieldChanges(it, change.path) }
    val next = entries.filter { it.id > entry.id && it.machine.stableId == entry.machine.stableId && historyFieldChanges(it, change.path) }
    DropdownMenuItem(
        text = { Text(historyText(HistoryText.PinField)) },
        onClick = {
            state.inspection.focus = HistoryFieldFocus.Path(change.path)
            dismiss()
        },
        modifier = Modifier.testTag("history-pin-field"),
    )
    DropdownMenuItem(
        text = { Text(historyText(HistoryText.PreviousChange)) },
        onClick = {
            previous.lastOrNull()?.let(state::select)
            dismiss()
        },
        enabled = previous.isNotEmpty(),
        modifier = Modifier.testTag("history-field-previous"),
    )
    DropdownMenuItem(
        text = { Text(historyText(HistoryText.NextChange)) },
        onClick = {
            next.firstOrNull()?.let(state::select)
            dismiss()
        },
        enabled = next.isNotEmpty(),
        modifier = Modifier.testTag("history-field-next"),
    )
    if (!state.inspection.fullState) {
        DropdownMenuItem(
            text = { Text(historyText(if (expanded) HistoryText.CompactValue else HistoryText.FullValue)) },
            onClick = {
                toggleExpanded()
                dismiss()
            },
            modifier = Modifier.testTag("history-field-expand-${change.path}"),
        )
    }
    Text(historyText(HistoryText.FieldSearchWindow), Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun <S : Any> HistoryFieldValues(
    change: StateChange,
    entry: HistoryEntry<S>,
    comparison: HistoryComparison,
    full: Boolean,
) {
    SelectionContainer {
        BoxWithConstraints {
            if (maxWidth >= 400.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HistoryFieldValue(change, entry, comparison, full, false, Modifier.weight(1f))
                    HistoryFieldValue(change, entry, comparison, full, true, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryFieldValue(change, entry, comparison, full, false, Modifier.fillMaxWidth())
                    HistoryFieldValue(change, entry, comparison, full, true, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun <S : Any> HistoryFieldValue(
    change: StateChange,
    entry: HistoryEntry<S>,
    comparison: HistoryComparison,
    full: Boolean,
    after: Boolean,
    modifier: Modifier,
) {
    val value = if (after) change.after else change.before
    val label =
        if (comparison is HistoryComparison.Against && comparison.machine == entry.machine.stableId) {
            if (after) "B: #${entry.id}" else "A: #${comparison.id}"
        } else {
            historyText(if (after) HistoryText.After else HistoryText.Before)
        }
    val displayed = if (full) value else compactFieldValue(change.path, value)
    val maxLines = if (full) Int.MAX_VALUE else 3
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(displayed, style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
