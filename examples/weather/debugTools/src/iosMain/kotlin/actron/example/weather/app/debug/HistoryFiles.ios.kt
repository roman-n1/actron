package actron.example.weather.app.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import kotlin.time.Instant

internal val LocalIosRecordingFiles = staticCompositionLocalOf<IosRecordingFiles> { error("The iOS host must supply document access") }

@Composable
actual fun rememberHistoryFiles(): HistoryFiles {
    val files = LocalIosRecordingFiles.current
    return androidx.compose.runtime.remember(files) { IosHistoryFiles(files) }
}

private class IosHistoryFiles(
    private val files: IosRecordingFiles,
) : HistoryFiles {
    override fun open(result: (HistoryFileResult) -> Unit) {
        files.open(
            WeatherRecording.codec.maxBytes,
            opened = { name, text -> result(HistoryFileResult.Opened(name, text.encodeToByteArray())) },
            cancelled = { result(HistoryFileResult.Cancelled) },
            failed = { result(HistoryFileResult.Failed(it)) },
        )
    }

    override fun save(
        name: String,
        bytes: ByteArray,
        result: (HistoryFileResult) -> Unit,
    ) {
        files.save(
            name,
            bytes.decodeToString(),
            saved = { result(HistoryFileResult.Saved) },
            cancelled = { result(HistoryFileResult.Cancelled) },
            failed = { result(HistoryFileResult.Failed(it)) },
        )
    }
}

actual fun recordingFileTimestamp(at: Instant): String =
    NSDateFormatter()
        .apply {
            dateFormat = "yyyy-MM-dd_HH-mm-ss"
            timeZone = NSTimeZone.localTimeZone
        }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(at.toEpochMilliseconds() / 1000.0))
