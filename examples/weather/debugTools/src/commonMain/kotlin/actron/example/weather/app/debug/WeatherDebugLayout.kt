package actron.example.weather.app.debug

import actron.decompose.session.SessionContinuation
import actron.example.weather.debug.resources.Res
import actron.example.weather.debug.resources.debug_panel
import actron.example.weather.debug.resources.done
import actron.example.weather.debug.resources.resume_reads
import actron.example.weather.debug.resources.retention
import actron.example.weather.debug.resources.settings
import actron.timetravel.compose.HistoryContinuation
import actron.timetravel.compose.HistoryRecordingPanel
import actron.timetravel.compose.ResizableHistoryLayout
import actron.timetravel.compose.ResumableHistoryPanel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource

@Composable
fun WeatherDebugLayout(
    history: WeatherHistory,
    files: HistoryFiles = rememberHistoryFiles(),
    settings: WeatherSettings = remember { WeatherSettings() },
    viewer: WeatherRecordingViewer = remember { WeatherRecordingViewer() },
    content: @Composable () -> Unit,
) {
    val visible by history.panelVisible.collectAsState()
    Box(Modifier.fillMaxSize()) {
        val preview: @Composable () -> Unit = {
            val selection = history.panel.preview
            if (viewer.imported.isNotEmpty()) {
                WeatherRecordingScreen(viewer.imported.single(), onSettings = settings::open)
            } else if (selection.isEmpty()) {
                content()
            } else {
                WeatherPreview(selection.single().snapshot.screen, settings::open)
            }
        }
        if (visible) {
            ResizableHistoryLayout(
                history.panelLayout,
                Modifier.fillMaxSize(),
                content = preview,
                panel = {
                    Column(Modifier.fillMaxSize()) {
                        if (viewer.imported.isEmpty()) {
                            ResumableHistoryPanel(history.timeline, history.panel, { history.continueFrom(it.snapshot) }, modifier = Modifier.weight(1f).testTag("weather-history-panel"), continuation = { frame ->
                                when (val point = frame.continuation) {
                                    is SessionContinuation.Ready -> HistoryContinuation.Available(stringResource(Res.string.resume_reads))
                                    is SessionContinuation.Unavailable -> HistoryContinuation.Unavailable(point.reason)
                                }
                            }, graph = { history.graph(it) }, actions = { RecordingActions(viewer, files, history) })
                        } else {
                            val imported = viewer.imported.single()
                            // Display-only records have no executor checkpoint or continuation callback.
                            key(imported) { HistoryRecordingPanel(imported.recording, imported.panel, Modifier.weight(1f), WeatherRecording::graph, actions = { RecordingActions(viewer, files, history) }) }
                        }
                    }
                },
            )
        } else {
            preview()
        }
    }
    if (settings.isOpen) {
        AlertDialog(
            onDismissRequest = { settings.close() },
            title = { Text(stringResource(resource = Res.string.settings)) },
            text = {
                Column {
                    Row {
                        Text(stringResource(resource = Res.string.debug_panel), Modifier.weight(1f))
                        Switch(visible, {
                            history.setPanelVisible(it, viewer)
                        }, Modifier.testTag("debug-panel-switch"))
                    }
                    Text(stringResource(resource = Res.string.retention, history.capacity))
                }
            },
            confirmButton = { TextButton(onClick = { settings.close() }, modifier = Modifier.testTag("debug-settings-done")) { Text(stringResource(resource = Res.string.done)) } },
        )
    }
}

internal fun WeatherHistory.setPanelVisible(
    visible: Boolean,
    viewer: WeatherRecordingViewer,
) {
    panelVisible.value = visible
    if (!visible) {
        panel.returnToLive()
        viewer.close()
    }
}
