package actron.example.weather.core.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun WeatherDebugLayout(
    history: WeatherHistory,
    content: @Composable () -> Unit,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    val visible by history.panelVisible.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { settingsOpen = true }) { Text("Settings") }
        Row(Modifier.weight(1f)) {
            if (visible) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val panelWidth = minOf(360.dp, maxWidth * 0.6f)
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { content() }
                        HistoryPanel(history, Modifier.width(panelWidth).fillMaxHeight())
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Settings") },
            text = {
                Column {
                    Row {
                        Text("Debug panel", Modifier.weight(1f))
                        Switch(modifier = Modifier.testTag("debug-panel-switch"), checked = visible, onCheckedChange = { history.panelVisible.value = it })
                    }
                    Text("The last ${history.capacity} entries are recorded throughout this session, even with the panel closed. Oldest entries are replaced automatically.")
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun HistoryPanel(
    history: WeatherHistory,
    modifier: Modifier,
) {
    val entries by history.entries.collectAsState()
    var selectedId by remember { mutableStateOf(-1L) }
    Surface(modifier.testTag("weather-history-panel"), tonalElevation = 4.dp) {
        Column(Modifier.padding(8.dp)) {
            Text("History · ${entries.size}/${history.capacity}")
            Text("Current session", style = MaterialTheme.typography.labelSmall)
            Row {
                TextButton(onClick = { history.clear() }) { Text("Clear") }
                TextButton(onClick = { history.panelVisible.value = false }) { Text("Close") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(entries.asReversed(), key = { it.id }) { entry ->
                    Column(
                        Modifier
                            .testTag("history-entry-${entry.id}")
                            .fillMaxWidth()
                            .clickable { selectedId = entry.id }
                            .padding(vertical = 8.dp),
                    ) {
                        Text("#${entry.id} ${entry.machine}", style = MaterialTheme.typography.labelMedium)
                        Text(entry.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val selected = entries.filter { it.id == selectedId }
            if (selected.isEmpty()) {
                Text("Select an entry. Older entries may have been overwritten.", style = MaterialTheme.typography.bodySmall)
            } else {
                val entry = selected.single()
                val replay = remember(entry.id) { entry.replay() }
                var frame by remember(entry.id) { mutableStateOf(replay.frame()) }
                Row {
                    TextButton(onClick = { frame = replay.backward() }, enabled = frame.position > 0) { Text("Previous") }
                    TextButton(onClick = { frame = replay.forward() }, enabled = frame.position < frame.length) { Text("Next") }
                    TextButton(onClick = { frame = replay.verify() }) { Text("Verify") }
                }
                Text("Replay ${frame.position}/${frame.length}: ${frame.status}", style = MaterialTheme.typography.labelSmall)
                SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(frame.details + "\n\nSelected entry:\n" + remember(entry.id) { entry.details() }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
