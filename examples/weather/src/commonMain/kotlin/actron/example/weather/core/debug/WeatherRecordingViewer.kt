package actron.example.weather.core.debug

import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.presentation.*
import actron.timetravel.*
import actron.timetravel.compose.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

class ImportedWeatherRecording(
    val name: String,
    val recording: HistoryRecording<WeatherRecordingFrame>,
) {
    val panel =
        HistoryPanelState<WeatherRecordingFrame>().apply {
            HistoryTimeline
                .fromRecording(recording)
                .entries.value
                .firstOrNull()
                ?.let(::select)
        }
}

/** A separate view session, never a WeatherHistory or a Decompose component. */
@Stable
class WeatherRecordingViewer {
    var imported: List<ImportedWeatherRecording> by mutableStateOf(emptyList())
        private set
    var message: String by mutableStateOf("")
        private set
    var busy: Boolean by mutableStateOf(false)
        private set

    private var generation = 0

    fun dismissMessage() {
        message = ""
    }

    fun close() {
        generation++
        busy = false
        imported = emptyList()
        message = ""
    }

    fun open(
        files: HistoryFiles,
        onOpened: () -> Unit = {},
    ) {
        if (busy) return
        busy = true
        message = ""
        val requestGeneration = generation
        files.open callback@{ result ->
            if (generation != requestGeneration) return@callback
            busy = false
            when (result) {
                is HistoryFileResult.Opened -> {
                    try {
                        val next = ImportedWeatherRecording(result.name, WeatherRecording.import(result.bytes))
                        imported = listOf(next)
                        onOpened()
                    } catch (error: IllegalArgumentException) {
                        message = error.message.orEmpty()
                    }
                }

                is HistoryFileResult.Failed -> {
                    message = result.message
                }

                else -> {
                    Unit
                }
            }
        }
    }

    fun save(
        files: HistoryFiles,
        history: WeatherHistory,
    ) {
        if (busy) return
        message = ""
        val at =
            kotlin.time.Clock.System
                .now()
        val settings =
            if (imported.isEmpty()) {
                history.exportSettings
            } else {
                HistoryExportSettings(
                    imported
                        .single()
                        .recording.metadata.projectName,
                )
            }
        val bytes =
            try {
                if (imported.isEmpty()) WeatherRecording.export(history, at) else WeatherRecording.codec.encode(imported.single().recording.copy(metadata = settings.metadata(at)))
            } catch (error: IllegalArgumentException) {
                message = error.message.orEmpty()
                return
            }
        busy = true
        files.save(settings.fileName(recordingFileTimestamp(at)), bytes) { result ->
            busy = false
            if (result is HistoryFileResult.Failed) message = result.message
        }
    }
}

@Composable
internal fun WeatherRecordingScreen(imported: ImportedWeatherRecording) {
    val selection = imported.panel.selection
    if (selection.isEmpty()) {
        Text(if (androidx.compose.ui.text.intl.Locale.current.language == "ru") "Выберите событие записи" else "Select a recorded event", Modifier.padding(24.dp))
    } else {
        when (val screen = selection.single().snapshot.screen) {
            is WeatherDisplay.Search -> CitySearchScreen(screen.state.toUiState(false), onIntent = {})
            is WeatherDisplay.Weather -> CityWeatherScreen(screen.state.toUiState(false), onIntent = {})
        }
    }
}

@Composable
internal fun RowScope.RecordingActions(
    viewer: WeatherRecordingViewer,
    files: HistoryFiles,
    history: WeatherHistory,
) {
    val russian = androidx.compose.ui.text.intl.Locale.current.language == "ru"
    TextButton({ viewer.open(files) }, enabled = !viewer.busy, modifier = Modifier.testTag("recording-open")) {
        FileDirectionIcon(importing = true)
        Spacer(Modifier.width(4.dp))
        Text(if (russian) "Импорт" else "Import")
    }
    TextButton({ viewer.save(files, history) }, enabled = !viewer.busy, modifier = Modifier.testTag("recording-save")) {
        FileDirectionIcon(importing = false)
        Spacer(Modifier.width(4.dp))
        Text(if (russian) "Экспорт" else "Export")
    }
    if (viewer.imported.isNotEmpty()) {
        TextButton({ viewer.close() }, modifier = Modifier.testTag("recording-close")) { Text(if (russian) "К сеансу" else "Back to session") }
    }
    if (viewer.message.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewer::dismissMessage,
            title = { Text(if (russian) "Ошибка записи" else "Recording error") },
            text = { Text(viewer.message, Modifier.testTag("recording-error")) },
            confirmButton = { TextButton(viewer::dismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun FileDirectionIcon(importing: Boolean) {
    val color = LocalContentColor.current
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val stroke = 1.6.dp.toPx()

        fun point(
            x: Float,
            y: Float,
        ) = androidx.compose.ui.geometry
            .Offset(x * size.width, y * size.height)
        val tip = if (importing) 0.65f else 0.15f
        val tail = if (importing) 0.15f else 0.65f
        val side = if (importing) tip - 0.2f else tip + 0.2f
        drawLine(color, point(0.5f, tail), point(0.5f, tip), stroke)
        drawLine(color, point(0.3f, side), point(0.5f, tip), stroke)
        drawLine(color, point(0.7f, side), point(0.5f, tip), stroke)
        drawLine(color, point(0.15f, 0.65f), point(0.15f, 0.85f), stroke)
        drawLine(color, point(0.15f, 0.85f), point(0.85f, 0.85f), stroke)
        drawLine(color, point(0.85f, 0.85f), point(0.85f, 0.65f), stroke)
    }
}
