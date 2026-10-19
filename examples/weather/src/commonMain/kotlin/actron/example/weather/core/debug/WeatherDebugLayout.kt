package actron.example.weather.core.debug

import actron.example.weather.feature.cities.presentation.CitySearchScreen
import actron.example.weather.feature.cities.presentation.toUiState
import actron.example.weather.feature.weather.presentation.CityWeatherScreen
import actron.example.weather.feature.weather.presentation.toUiState
import actron.timetravel.compose.HistoryPanel
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
    content: @Composable () -> Unit,
) {
    val russian = androidx.compose.ui.text.intl.Locale.current.language == "ru"
    var settingsOpen by remember { mutableStateOf(false) }
    val visible by history.panelVisible.collectAsState()
    CompositionLocalProvider(LocalWeatherSettings provides { settingsOpen = true }) {
        Box(Modifier.fillMaxSize()) {
            val preview: @Composable () -> Unit = {
                val selection = history.panel.preview
                if (selection.isEmpty()) {
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
                        HistoryPanel(history.timeline, history.panel, { onContinue(it.snapshot) }, Modifier.fillMaxSize().testTag("weather-history-panel"), WeatherFrame::canResume, graph = history::graph)
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
                            if (!it) history.panel.returnToLive()
                        }, Modifier.testTag("debug-panel-switch"))
                    }
                    Text(if (russian) "Сохраняются последние ${history.capacity} событий текущего запуска. Просмотр истории не запускает запросы. «Продолжить отсюда» удаляет более позднюю историю и перезапускает незавершённые запросы." else "The last ${history.capacity} events are recorded throughout this session. Preview does not run requests. Continue here removes later history and restarts unfinished requests.")
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }, modifier = Modifier.testTag("debug-settings-done")) { Text(if (russian) "Готово" else "Done") } },
        )
    }
}
