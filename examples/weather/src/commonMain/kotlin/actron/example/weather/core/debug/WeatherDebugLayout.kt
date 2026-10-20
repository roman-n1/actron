package actron.example.weather.core.debug

import actron.example.weather.feature.cities.presentation.CitySearchScreen
import actron.example.weather.feature.cities.presentation.toUiState
import actron.example.weather.feature.weather.presentation.CityWeatherScreen
import actron.example.weather.feature.weather.presentation.toUiState
import actron.timetravel.compose.HistoryPanel
import actron.timetravel.compose.HistoryRecordingPanel
import actron.timetravel.compose.ResizableHistoryLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal val LocalWeatherSettings = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun WeatherDebugLayout(
    history: WeatherHistory,
    onContinue: (WeatherFrame) -> Unit,
    files: HistoryFiles = rememberHistoryFiles(),
    viewer: WeatherRecordingViewer = remember { WeatherRecordingViewer() },
    content: @Composable () -> Unit,
) {
    val russian = androidx.compose.ui.text.intl.Locale.current.language == "ru"
    var settingsOpen by remember { mutableStateOf(false) }
    var diagnosticDemo by remember { mutableStateOf(GraphDiagnosticDemo.Off) }
    val visible by history.panelVisible.collectAsState()
    CompositionLocalProvider(LocalWeatherSettings provides { settingsOpen = true }) {
        Box(Modifier.fillMaxSize()) {
            val preview: @Composable () -> Unit = {
                val selection = history.panel.preview
                if (viewer.imported.isNotEmpty()) {
                    WeatherRecordingScreen(viewer.imported.single())
                } else if (selection.isEmpty()) {
                    content()
                } else {
                    val frame = selection.single().snapshot
                    when (val route = frame.route) {
                        WeatherRoute.Search -> {
                            CitySearchScreen(
                                frame.search.snapshot.context
                                    .toUiState(false),
                                onIntent = {},
                            )
                        }

                        is WeatherRoute.Weather -> {
                            CityWeatherScreen(
                                route.checkpoint.snapshot.context
                                    .toUiState(false),
                                onIntent = {},
                            )
                        }
                    }
                }
            }
            if (!visible) {
                preview()
            } else {
                ResizableHistoryLayout(
                    history.panelLayout,
                    Modifier.fillMaxSize(),
                    content = preview,
                    panel = {
                        Column(Modifier.fillMaxSize()) {
                            if (viewer.imported.isEmpty()) {
                                // The reusable panel delegates continuation/navigation to the host.
                                HistoryPanel(history.timeline, history.panel, { onContinue(it.snapshot) }, Modifier.weight(1f).testTag("weather-history-panel"), WeatherFrame::canResume, graph = { history.graph(it).withDiagnosticDemo(diagnosticDemo) }, actions = { RecordingActions(viewer, files, history) })
                            } else {
                                val imported = viewer.imported.single()
                                // Display-only records have no executor checkpoint or continuation callback.
                                key(imported) { HistoryRecordingPanel(imported.recording, imported.panel, Modifier.weight(1f), WeatherRecording::graph, actions = { RecordingActions(viewer, files, history) }) }
                            }
                        }
                    },
                )
            }
        }
    }
    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text(if (russian) "Настройки" else "Settings") },
            text = {
                Column {
                    Row {
                        Text(if (russian) "Панель отладки" else "Debug panel", Modifier.weight(1f))
                        Switch(visible, {
                            history.panelVisible.value = it
                            if (!it) {
                                history.panel.returnToLive()
                                viewer.close()
                            }
                        }, Modifier.testTag("debug-panel-switch"))
                    }
                    if (visible && viewer.imported.isEmpty()) {
                        TextButton(onClick = { diagnosticDemo = GraphDiagnosticDemo.entries[(diagnosticDemo.ordinal + 1) % GraphDiagnosticDemo.entries.size] }, modifier = Modifier.testTag("graph-diagnostic-demo")) {
                            Text(
                                if (russian) {
                                    "Демо диагностики графа: ${when (diagnosticDemo) {
                                        GraphDiagnosticDemo.Off -> "выкл."
                                        GraphDiagnosticDemo.Warnings -> "предупреждения"
                                        GraphDiagnosticDemo.Errors -> "ошибки"
                                    }}"
                                } else {
                                    "Graph diagnostic demo: $diagnosticDemo"
                                },
                            )
                        }
                        if (diagnosticDemo != GraphDiagnosticDemo.Off) Text(if (russian) "Ошибки добавляются только в рисунок. Рабочая машина не меняется." else "Issues affect only the diagram. The running machine is unchanged.")
                    }
                    Text(if (russian) "Сохраняются последние ${history.capacity} событий текущего запуска. Просмотр истории не запускает запросы. «Продолжить отсюда» удаляет более позднюю историю и перезапускает незавершённые запросы." else "The last ${history.capacity} events are recorded throughout this session. Preview does not run requests. Continue here removes later history and restarts unfinished requests.")
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }, modifier = Modifier.testTag("debug-settings-done")) { Text(if (russian) "Готово" else "Done") } },
        )
    }
}
