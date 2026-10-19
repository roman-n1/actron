package actron.timetravel

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class HistoryRecordingMetadata(
    val projectName: String,
    val exportedAt: String,
)

/** Application configuration, independent of build systems and platform file pickers. */
data class HistoryExportSettings(
    val projectName: String = "Actron",
) {
    init {
        require(projectName.isNotBlank())
    }

    fun metadata(at: Instant = Clock.System.now()): HistoryRecordingMetadata = HistoryRecordingMetadata(projectName, at.toString())

    /** [localTimestamp] comes from the host's local clock formatter. Neither value can introduce a path. */
    fun fileName(localTimestamp: String): String {
        fun safe(value: String): String =
            value
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .take(80)
                .ifBlank { "Actron" }
        return "${safe(projectName)}_${safe(localTimestamp)}.actron.json"
    }
}
