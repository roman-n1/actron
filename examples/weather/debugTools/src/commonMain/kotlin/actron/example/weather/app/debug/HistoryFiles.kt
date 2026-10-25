package actron.example.weather.app.debug

import androidx.compose.runtime.Composable

sealed interface HistoryFileResult {
    data object Cancelled : HistoryFileResult

    data object Saved : HistoryFileResult

    data class Opened(
        val name: String,
        val bytes: ByteArray,
    ) : HistoryFileResult

    data class Failed(
        val message: String,
    ) : HistoryFileResult
}

/** Platform shell only; the recording format and validation live in actron-timetravel. */
interface HistoryFiles {
    fun open(result: (HistoryFileResult) -> Unit)

    fun save(
        name: String,
        bytes: ByteArray,
        result: (HistoryFileResult) -> Unit,
    )
}

@Composable
expect fun rememberHistoryFiles(): HistoryFiles

expect fun recordingFileTimestamp(at: kotlin.time.Instant): String
