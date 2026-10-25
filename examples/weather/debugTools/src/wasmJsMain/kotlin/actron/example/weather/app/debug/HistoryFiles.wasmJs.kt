package actron.example.weather.app.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.Instant

@Composable
actual fun rememberHistoryFiles(): HistoryFiles = remember { BrowserHistoryFiles() }

private class BrowserHistoryFiles : HistoryFiles {
    override fun open(result: (HistoryFileResult) -> Unit) {
        openRecording(WeatherRecording.codec.maxBytes) { status, name, text ->
            result(
                when (status) {
                    "opened" -> HistoryFileResult.Opened(name, text.encodeToByteArray())
                    "cancelled" -> HistoryFileResult.Cancelled
                    else -> HistoryFileResult.Failed(text)
                },
            )
        }
    }

    override fun save(
        name: String,
        bytes: ByteArray,
        result: (HistoryFileResult) -> Unit,
    ) {
        val error = saveRecording(name, bytes.decodeToString())
        result(if (error.isEmpty()) HistoryFileResult.Saved else HistoryFileResult.Failed(error))
    }
}

private fun openRecording(
    maxBytes: Int,
    complete: (String, String, String) -> Unit,
): Unit =
    js(
        """{
            const input = document.createElement('input');
            input.type = 'file';
            input.accept = '.json,application/json';
            input.style.display = 'none';
            document.body.appendChild(input);
            input.addEventListener('cancel', () => {
                input.remove();
                complete('cancelled', '', '');
            }, {once: true});
            input.addEventListener('change', async () => {
                input.remove();
                const file = input.files[0];
                if (!file) { complete('cancelled', '', ''); return; }
                if (file.size > maxBytes) { complete('failed', '', 'Recording is too large'); return; }
                try {
                    const text = new TextDecoder('utf-8', {fatal: true}).decode(await file.arrayBuffer());
                    complete('opened', file.name, text);
                } catch (error) {
                    complete('failed', '', 'Cannot read recording');
                }
            }, {once: true});
            input.click();
        }""",
    )

private fun saveRecording(
    name: String,
    text: String,
): String =
    js(
        """{
            try {
                const url = URL.createObjectURL(new Blob([text], {type: 'application/json'}));
                const link = document.createElement('a');
                link.href = url;
                link.download = name;
                document.body.appendChild(link);
                link.click();
                link.remove();
                setTimeout(() => URL.revokeObjectURL(url), 1000);
                return '';
            } catch (error) {
                return 'Cannot save recording';
            }
        }""",
    )

private fun localTimestamp(millis: Double): String =
    js(
        """{
            const date = new Date(millis);
            const pad = value => String(value).padStart(2, '0');
            return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) +
                '_' + pad(date.getHours()) + '-' + pad(date.getMinutes()) + '-' + pad(date.getSeconds());
        }""",
    )

actual fun recordingFileTimestamp(at: Instant): String = localTimestamp(at.toEpochMilliseconds().toDouble())
