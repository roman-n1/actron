package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One cursor bar for both the complete machine graph and the graphical event details. */
@Composable
internal fun <S : Any> HistoryNavigation(
    entry: HistoryEntry<S>,
    entries: List<HistoryEntry<S>>,
    visible: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
    onReveal: () -> Unit,
) {
    val inspection = state.inspection
    val previousLabel = historyText(text = HistoryText.PreviousEvent)
    val nextLabel = historyText(text = HistoryText.NextEvent)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("history-navigation")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${entry.machine.name} · #${entry.id}", style = MaterialTheme.typography.titleSmall)
                HistoryMuted(formatHistoryTime(entry.at))
            }
            IconButton(
                { visible.lastOrNull { it.id < entry.id }?.let(state::select) },
                enabled = visible.any { it.id < entry.id },
                modifier = Modifier.testTag("history-previous").semantics { contentDescription = previousLabel },
            ) { HistoryArrow(back = true) }
            IconButton(
                { visible.firstOrNull { it.id > entry.id }?.let(state::select) },
                enabled = visible.any { it.id > entry.id },
                modifier = Modifier.testTag("history-next").semantics { contentDescription = nextLabel },
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
                HistoryMuted(historyText(if (entries.any { it.id == entry.id }) HistoryText.HiddenSelection else HistoryText.OutsideWindow), Modifier.weight(1f).testTag("history-selection-status"))
                if (entries.any { it.id == entry.id }) {
                    TextButton(onReveal, Modifier.testTag("history-reveal-selection")) { Text(historyText(HistoryText.Reveal)) }
                }
            }
        }
    }
}

/** Bubble after children so text editors and graph gestures can consume their own keys first. */
internal fun <S : Any> Modifier.historyNavigation(
    state: HistoryPanelState<S>,
    entries: List<HistoryEntry<S>>,
    visible: List<HistoryEntry<S>>,
): Modifier =
    onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !event.isHistoryNavigationModifier()) {
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
    }
