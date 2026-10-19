package actron.example.weather

import actron.example.weather.app.WeatherTheme
import actron.example.weather.core.debug.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WeatherRecordingUiTest {
    @Test fun importedScreensFollowSelectionWithoutLiveOrContinueControls() =
        runComposeUiTest {
            val history = recordingHistory()
            history.panelVisible.value = true
            val viewer = WeatherRecordingViewer()
            val files = RecordingTestFiles(HistoryFileResult.Opened("sample.json", WeatherRecording.export(history)))
            viewer.open(files)
            var continuations = 0
            setContent { WeatherTheme { Box(Modifier.size(1300.dp, 1000.dp)) { WeatherDebugLayout(history, { continuations++ }, files, viewer) { Text("LIVE SESSION") } } } }
            onNodeWithTag("city-search").assertExists()
            onNodeWithTag("history-continue").assertDoesNotExist()
            onNodeWithTag("history-live").assertDoesNotExist()
            onNodeWithTag("history-entry-2").performClick()
            onNodeWithTag("weather-city").assertTextEquals("Москва")
            onNodeWithTag("history-graph-tab").performClick()
            onNodeWithTag("machine-graph").assertExists()
            onNodeWithTag("recording-close").performClick()
            onNodeWithText("LIVE SESSION").assertExists()
            runOnIdle {
                assertEquals(0, continuations)
                assertEquals(2, history.timeline.entries.value.size)
            }
        }

    @Test fun desktopFileIoHandlesUnicodeBoundsAndFailures() {
        val folder: java.nio.file.Path = Files.createTempDirectory("actron-recording-test")
        try {
            val path: java.nio.file.Path = folder.resolve("Погода.json")
            val bytes = WeatherRecording.export(recordingHistory())
            writeHistoryFile(path, bytes)
            assertContentEquals(bytes, readHistoryFile(path, bytes.size))
            assertFailsWith<IllegalArgumentException> { readHistoryFile(path, bytes.size - 1) }
            assertFails { readHistoryFile(folder.resolve("missing"), 1024) }
            assertFails { writeHistoryFile(folder, bytes) }
            // A fixture is emitted only when explicitly requested for manual native dialog testing.
            val fixture: String = System.getProperty("actron.recording.fixture", "")
            if (fixture.isNotEmpty()) {
                writeHistoryFile(
                    java.nio.file.Path
                        .of(fixture),
                    bytes,
                )
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
