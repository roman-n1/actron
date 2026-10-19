package actron.example.weather.core.debug

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

@Composable
actual fun rememberHistoryFiles(): HistoryFiles {
    val resolver: android.content.ContentResolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var callback by remember { mutableStateOf<(HistoryFileResult) -> Unit>({}) }
    var pending by remember { mutableStateOf(byteArrayOf()) }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            activityResult.data?.data?.takeIf { activityResult.resultCode == android.app.Activity.RESULT_OK }?.let { selected: android.net.Uri ->
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            try {
                                val bytes: ByteArray =
                                    checkNotNull(resolver.openInputStream(selected)).use { stream: java.io.InputStream ->
                                        val output = java.io.ByteArrayOutputStream()
                                        val buffer = ByteArray(8192)
                                        var count = stream.read(buffer)
                                        while (count >= 0) {
                                            require(output.size() + count <= WeatherRecording.codec.maxBytes) { "Recording is too large" }
                                            output.write(buffer, 0, count)
                                            count = stream.read(buffer)
                                        }
                                        val content: ByteArray = output.toByteArray()
                                        content
                                    }
                                HistoryFileResult.Opened(selected.lastPathSegment.orEmpty(), bytes)
                            } catch (error: Exception) {
                                HistoryFileResult.Failed(error.message.orEmpty())
                            }
                        }
                    callback(result)
                }
            } ?: callback(HistoryFileResult.Cancelled)
        }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            activityResult.data?.data?.takeIf { activityResult.resultCode == android.app.Activity.RESULT_OK }?.let { selected: android.net.Uri ->
                scope.launch {
                    val bytes = pending
                    pending = byteArrayOf()
                    val result =
                        withContext(Dispatchers.IO) {
                            try {
                                check(bytes.isNotEmpty()) { "Export was interrupted; please export again" }
                                checkNotNull(resolver.openOutputStream(selected, "wt")).use { stream: java.io.OutputStream -> stream.write(bytes) }
                                HistoryFileResult.Saved
                            } catch (error: Exception) {
                                HistoryFileResult.Failed(error.message.orEmpty())
                            }
                        }
                    callback(result)
                }
            } ?: run {
                pending = byteArrayOf()
                callback(HistoryFileResult.Cancelled)
            }
        }
    return remember(open, save) {
        object : HistoryFiles {
            override fun open(result: (HistoryFileResult) -> Unit) {
                callback = result
                open.launch(
                    android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
                    },
                )
            }

            override fun save(
                name: String,
                bytes: ByteArray,
                result: (HistoryFileResult) -> Unit,
            ) {
                callback = result
                pending = bytes
                save.launch(
                    android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(android.content.Intent.EXTRA_TITLE, name)
                    },
                )
            }
        }
    }
}

actual fun recordingFileTimestamp(at: kotlin.time.Instant): String = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.ROOT).format(java.util.Date(at.toEpochMilliseconds()))
