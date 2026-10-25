package actron.example.weather.app.debug

/** Swift owns document-picker delegates and their presentation lifetime. */
interface IosRecordingFiles {
    fun open(
        maxBytes: Int,
        opened: (String, String) -> Unit,
        cancelled: () -> Unit,
        failed: (String) -> Unit,
    )

    fun save(
        name: String,
        text: String,
        saved: () -> Unit,
        cancelled: () -> Unit,
        failed: (String) -> Unit,
    )
}
