package actron.example.weather.app.debug

import actron.example.weather.app.session.WeatherFrame
import actron.example.weather.app.session.WeatherRoute
import actron.example.weather.feature.cities.presentation.CitySearchScreen
import actron.example.weather.feature.cities.presentation.toUiState
import actron.example.weather.feature.weather.presentation.CityWeatherScreen
import actron.example.weather.feature.weather.presentation.toUiState
import actron.timetravel.compose.HistoryContinuation
import actron.timetravel.compose.HistoryPanel
import actron.timetravel.compose.HistoryRecordingPanel
import actron.timetravel.compose.ResizableHistoryLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
fun WeatherDebugLayout(
    history: WeatherHistory,
    onContinue: (WeatherHistoryFrame) -> Unit,
    files: HistoryFiles = rememberHistoryFiles(),
    settings: WeatherSettings = remember { WeatherSettings() },
    viewer: WeatherRecordingViewer = remember { WeatherRecordingViewer() },
    content: @Composable () -> Unit,
) {
    val russian = androidx.compose.ui.text.intl.Locale.current.language == "ru"
    val visible by history.panelVisible.collectAsState()
    Box(Modifier.fillMaxSize()) {
        val preview: @Composable () -> Unit = {
            val selection = history.panel.preview
            if (viewer.imported.isNotEmpty()) {
                WeatherRecordingScreen(viewer.imported.single(), onSettings = settings::open)
            } else if (selection.isEmpty()) {
                content()
            } else {
                val frame = selection.single().snapshot.application
                when (val route = frame.route) {
                    WeatherRoute.Search -> {
                        CitySearchScreen(
                            frame.search.snapshot.context
                                .toUiState(false),
                            onIntent = {},
                            settingsAction = { WeatherSettingsButton(settings::open) },
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
        if (visible) {
            ResizableHistoryLayout(
                history.panelLayout,
                Modifier.fillMaxSize(),
                content = preview,
                panel = {
                    Column(Modifier.fillMaxSize()) {
                        if (viewer.imported.isEmpty()) {
                            // The reusable panel delegates continuation/navigation to the host.
                            HistoryPanel(history.timeline, history.panel, { onContinue(it.snapshot) }, modifier = Modifier.weight(1f).testTag("weather-history-panel"), continuation = { it.application.continuation(russian) }, graph = { history.graph(it) }, actions = { RecordingActions(viewer, files, history) })
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
            title = { Text(if (russian) "Настройки" else "Settings") },
            text = {
                Column {
                    Row {
                        Text(if (russian) "Панель отладки" else "Debug panel", Modifier.weight(1f))
                        Switch(visible, {
                            history.setPanelVisible(it, viewer)
                        }, Modifier.testTag("debug-panel-switch"))
                    }
                    Text(if (russian) "Сохраняются последние ${history.capacity} событий текущего запуска. Просмотр истории не запускает запросы. «Продолжить отсюда» удаляет более позднюю историю и перезапускает незавершённые запросы." else "The last ${history.capacity} events are recorded throughout this session. Preview does not run requests. Continue here removes later history and restarts unfinished requests.")
                }
            },
            confirmButton = { TextButton(onClick = { settings.close() }, modifier = Modifier.testTag("debug-settings-done")) { Text(if (russian) "Готово" else "Done") } },
        )
    }
}

/** Explain the same executor restrictions used by WeatherFrame.canResume. */
internal fun WeatherFrame.continuation(russian: Boolean): HistoryContinuation {
    val ending =
        search.ending.size +
            when (val screen = route) {
                WeatherRoute.Search -> 0
                is WeatherRoute.Weather -> screen.checkpoint.ending.size
            }
    val effects =
        search.effects.size +
            when (val screen = route) {
                WeatherRoute.Search -> 0
                is WeatherRoute.Weather -> screen.checkpoint.effects.size
            }
    if (ending == 0 && effects == 0) {
        return HistoryContinuation.Available(
            if (russian) "Незавершённые запросы будут запущены заново." else "Unfinished requests will be restarted.",
        )
    }
    val reasons = mutableListOf<String>()
    if (ending > 0) reasons.add(if (russian) "Не обработаны завершения команд: $ending." else "Command completions awaiting a decision: $ending.")
    if (effects > 0) reasons.add(if (russian) "Не подтверждена обработка эффектов: $effects." else "Effects awaiting acknowledgement: $effects.")
    return HistoryContinuation.Unavailable(reasons.joinToString(" "))
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
