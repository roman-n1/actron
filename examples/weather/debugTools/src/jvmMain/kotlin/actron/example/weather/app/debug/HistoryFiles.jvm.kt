package actron.example.weather.app.debug

import actron.example.weather.debug.resources.Res
import actron.example.weather.debug.resources.open_recording
import actron.example.weather.debug.resources.save_recording
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

internal fun readHistoryFile(
    path: Path,
    maxBytes: Int,
): ByteArray =
    Files.newInputStream(path).use { stream: java.io.InputStream ->
        val bytes: ByteArray = stream.readNBytes(maxBytes + 1)
        require(bytes.size <= maxBytes) { "Recording is too large" }
        bytes
    }

internal fun writeHistoryFile(
    path: Path,
    bytes: ByteArray,
) {
    Files.newOutputStream(path).use { stream: java.io.OutputStream -> stream.write(bytes) }
}

@Composable
actual fun rememberHistoryFiles(): HistoryFiles {
    val scope = rememberCoroutineScope()
    val openTitle = stringResource(resource = Res.string.open_recording)
    val saveTitle = stringResource(resource = Res.string.save_recording)
    return remember(scope, openTitle, saveTitle) {
        object : HistoryFiles {
            private fun choose(
                mode: Int,
                name: String,
                bytes: ByteArray,
                result: (HistoryFileResult) -> Unit,
            ) {
                val owner = Frame()
                val dialog = FileDialog(owner, if (mode == FileDialog.LOAD) openTitle else saveTitle, mode)
                try {
                    if (mode == FileDialog.SAVE) dialog.file = name
                    dialog.isVisible = true
                    val selected: List<java.io.File> = dialog.files.toList()
                    if (selected.isEmpty()) {
                        result(HistoryFileResult.Cancelled)
                    } else {
                        scope.launch {
                            val outcome =
                                withContext(Dispatchers.IO) {
                                    try {
                                        val path: Path = selected.single().toPath()
                                        if (mode == FileDialog.LOAD) {
                                            HistoryFileResult.Opened(path.fileName.toString(), readHistoryFile(path, WeatherRecording.codec.maxBytes))
                                        } else {
                                            writeHistoryFile(path, bytes)
                                            HistoryFileResult.Saved
                                        }
                                    } catch (error: Exception) {
                                        HistoryFileResult.Failed(error.message.orEmpty())
                                    }
                                }
                            result(outcome)
                        }
                    }
                } finally {
                    dialog.dispose()
                    owner.dispose()
                }
            }

            override fun open(result: (HistoryFileResult) -> Unit) = choose(FileDialog.LOAD, "", byteArrayOf(), result)

            override fun save(
                name: String,
                bytes: ByteArray,
                result: (HistoryFileResult) -> Unit,
            ) = choose(FileDialog.SAVE, name, bytes, result)
        }
    }
}

actual fun recordingFileTimestamp(at: kotlin.time.Instant): String =
    java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(at.toEpochMilliseconds()))
