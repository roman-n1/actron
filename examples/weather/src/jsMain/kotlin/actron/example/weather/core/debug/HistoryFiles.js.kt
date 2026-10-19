package actron.example.weather.core.debug

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.get
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.FileReader
import org.w3c.files.get

@Composable
actual fun rememberHistoryFiles(): HistoryFiles =
    remember {
        object : HistoryFiles {
            override fun open(result: (HistoryFileResult) -> Unit) {
                val input = document.createElement("input") as HTMLInputElement
                input.type = "file"
                input.accept = ".json,application/json"
                input.style.display = "none"
                document.body?.appendChild(input)
                input.addEventListener("cancel", {
                    input.remove()
                    result(HistoryFileResult.Cancelled)
                })
                input.onchange = {
                    input.remove()
                    input.files?.get(0)?.let { selected ->
                        if (selected.size.toDouble() > WeatherRecording.codec.maxBytes) {
                            result(HistoryFileResult.Failed("Recording is too large"))
                        } else {
                            val reader = FileReader()
                            reader.onerror = { result(HistoryFileResult.Failed("Cannot read recording")) }
                            reader.onload = {
                                val data = org.khronos.webgl.Int8Array(reader.result as org.khronos.webgl.ArrayBuffer)
                                result(HistoryFileResult.Opened(selected.name, ByteArray(data.length) { data[it] }))
                            }
                            reader.readAsArrayBuffer(selected)
                        }
                    } ?: result(HistoryFileResult.Cancelled)
                    Unit
                }
                input.click()
            }

            override fun save(
                name: String,
                bytes: ByteArray,
                result: (HistoryFileResult) -> Unit,
            ) {
                try {
                    val blob = Blob(arrayOf(bytes.decodeToString()))
                    val url = URL.createObjectURL(blob)
                    val link = document.createElement("a") as HTMLAnchorElement
                    link.href = url
                    link.download = name
                    document.body?.appendChild(link)
                    link.click()
                    link.remove()
                    window.setTimeout({ URL.revokeObjectURL(url) }, 1000)
                    result(HistoryFileResult.Saved)
                } catch (error: Exception) {
                    result(HistoryFileResult.Failed(error.message.orEmpty()))
                }
            }
        }
    }

actual fun recordingFileTimestamp(at: kotlin.time.Instant): String {
    val date = kotlin.js.Date(at.toEpochMilliseconds().toDouble())

    fun pad(value: Int): String = value.toString().padStart(2, '0')
    return "${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}_${pad(date.getHours())}-${pad(date.getMinutes())}-${pad(date.getSeconds())}"
}
