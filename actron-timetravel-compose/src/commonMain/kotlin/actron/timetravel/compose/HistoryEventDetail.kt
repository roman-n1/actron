package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryEntry
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp

@Composable
internal fun <S : Any> HistoryEventDetail(
    entry: HistoryEntry<S>,
    entries: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
    onContinue: (HistoryEntry<S>) -> Unit,
    continuation: @Composable (S) -> HistoryContinuation,
    modifier: Modifier,
    readOnly: Boolean,
) {
    Column(modifier) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            key(entry.id) { TransitionDiagram(entry.details) }
            HistoryMuted("${entry.details.source} · ${entry.details.outcome}")
            HistoryFields(entry, entries, state)
        }
        if (historyAllowsContinuation(readOnly)) HistoryContinuationAction(entry, entries, state, onContinue, continuation)
    }
}

@Composable
private fun <S : Any> HistoryContinuationAction(
    entry: HistoryEntry<S>,
    entries: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
    onContinue: (HistoryEntry<S>) -> Unit,
    continuation: @Composable (S) -> HistoryContinuation,
) {
    val capability = continuation(entry.snapshot)
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = {
                onContinue(entry)
                state.returnToLive()
            },
            enabled = historyContinuationEnabled(state, entry, capability),
            modifier = Modifier.fillMaxWidth().testTag("history-continue"),
            shape = MaterialTheme.shapes.small,
        ) { Text(historyText(HistoryText.Continue)) }
        HistoryMuted(
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

@Composable
private fun StateCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryMuted(label)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TransitionDiagram(details: HistoryDetails) {
    val states = historyTransitionStates(details, historyText(text = HistoryText.NoStateChange))
    Column(Modifier.fillMaxWidth().testTag("history-transition"), horizontalAlignment = Alignment.CenterHorizontally) {
        HistoryActionPayload(details)
        HistoryTransitionArrows(Modifier.fillMaxWidth().height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StateCard(historyText(HistoryText.StateBefore), states.before, Modifier.weight(1f))
            StateCard(historyText(HistoryText.StateAfter), states.after, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HistoryActionPayload(details: HistoryDetails) {
    val expanded = remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth(0.9f),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryMuted(historyText(HistoryText.Action))
            SelectionContainer {
                Text(if (expanded.value) details.input else actionSummary(details.input), Modifier.testTag("history-selected-input"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (actionSummary(details.input) != details.input) {
                TextButton(onClick = { expanded.toggle() }, modifier = Modifier.testTag("history-action-payload")) {
                    Text(historyText(if (expanded.value) HistoryText.HidePayload else HistoryText.ShowPayload))
                }
            }
        }
    }
}
