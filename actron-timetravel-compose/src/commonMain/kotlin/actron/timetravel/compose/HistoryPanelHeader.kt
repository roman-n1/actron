package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun <S : Any> HistoryPanelHeader(
    state: HistoryPanelState<S>,
    visible: List<HistoryEntry<S>>,
    readOnly: Boolean,
) {
    val status =
        if (readOnly) {
            HistoryText.ImportedRecording
        } else if (state.preview.isEmpty()) {
            HistoryText.AppLive
        } else {
            HistoryText.AppPreview
        }
    val onHistory: () -> Unit = {
        if (state.selection.isNotEmpty()) {
            state.previewSelected()
        } else if (visible.isNotEmpty()) {
            state.select(visible.last())
        }
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(historyText(HistoryText.Title), style = MaterialTheme.typography.titleMedium)
            HistoryMuted(historyText(status))
        }
        if (historyAllowsContinuation(readOnly)) {
            HistoryModeSwitch(
                history = state.preview.isNotEmpty(),
                historyAvailable = historyModeAvailable(state, visible),
                onHistory = onHistory,
                onLive = state::returnToLive,
            )
        }
    }
}

@Composable
internal fun <S : Any> HistoryMachinePicker(
    entries: List<HistoryEntry<S>>,
    inspection: HistoryInspection,
    actions: @Composable RowScope.() -> Unit,
) {
    var picker by remember { mutableStateOf(false) }
    val machines = entries.map { it.machine }.distinctBy { it.stableId }
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
}
