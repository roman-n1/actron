package actron.timetravel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Portable display data, not an executable checkpoint. The host owns the snapshot schema. */
@Serializable
data class HistoryRecord<S : Any>(
    val id: Long,
    val machine: HistoryMachine,
    val branch: Long,
    val at: String,
    val details: HistoryDetails,
    val snapshot: S,
)

@Serializable
data class HistoryRecording<S : Any>(
    val schema: String,
    val entries: List<HistoryRecord<S>>,
    val metadata: HistoryRecordingMetadata,
    val format: Int = 1,
    val definitions: List<HistoryDefinition> = emptyList(),
) {
    fun validate() {
        require(format == 1) { "Unsupported recording format" }
        require(schema.isNotBlank()) { "Missing snapshot schema" }
        require(metadata.projectName.isNotBlank()) { "Missing project name" }
        Instant.parse(metadata.exportedAt)
        var previous = 0L
        val machines = mutableMapOf<Long, HistoryMachine>()
        entries.forEach {
            require(it.id > previous && it.branch >= 0) { "Invalid event order or branch" }
            require(it.machine.id > 0 && it.machine.stableId.isNotBlank() && it.machine.name.isNotBlank()) { "Invalid machine identity" }
            require(machines.getOrPut(it.machine.id) { it.machine } == it.machine) { "Conflicting machine identity" }
            Instant.parse(it.at)
            previous = it.id
        }
    }
}

/** Transform snapshots to display-only DTOs and redact sensitive fields before exporting. */
fun <S : Any, V : Any> HistoryTimeline<S>.recording(
    schema: String,
    snapshot: (S) -> V,
    details: (HistoryDetails) -> HistoryDetails = { it },
    metadata: HistoryRecordingMetadata = HistoryExportSettings().metadata(),
): HistoryRecording<V> = HistoryRecording(schema, entries.value.map { HistoryRecord(it.id, it.machine, it.branch, it.at.toString(), details(it.details), snapshot(it.snapshot)) }, metadata)

/** Bounded, strict JSON transport. Decoding never runs commands, timers, or machine code. */
class HistoryRecordingCodec<S : Any>(
    val schema: String,
    serializer: KSerializer<S>,
    val maxBytes: Int = 8 * 1024 * 1024,
    val maxEntries: Int = 10_000,
    val maxDepth: Int = 64,
    val definitions: List<HistoryDefinition> = emptyList(),
) {
    init {
        require(schema.isNotBlank() && maxBytes > 0 && maxEntries > 0 && maxDepth > 0)
    }

    private val serializer = HistoryRecording.serializer(serializer)
    private val json = Json { encodeDefaults = true }

    fun encode(recording: HistoryRecording<S>): ByteArray {
        validate(recording)
        val bytes = json.encodeToString(serializer, recording).encodeToByteArray()
        require(bytes.size <= maxBytes) { "Recording is too large" }
        checkDepth(bytes.decodeToString())
        return bytes
    }

    fun decode(bytes: ByteArray): HistoryRecording<S> {
        require(bytes.size <= maxBytes) { "Recording is too large" }
        val text =
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (error: Exception) {
                throw IllegalArgumentException("Recording is not valid UTF-8", error)
            }
        checkDepth(text)
        return json.decodeFromString(serializer, text).also(::validate)
    }

    private fun validate(recording: HistoryRecording<S>) {
        require(recording.schema == schema) { "Incompatible snapshot schema" }
        require(recording.entries.size <= maxEntries) { "Too many events" }
        require(recording.definitions == definitions) {
            val changed = (recording.definitions + definitions).map { it.id }.distinct().filter { id -> recording.definitions.filter { it.id == id } != definitions.filter { it.id == id } }
            "Incompatible machine version or graph: ${changed.joinToString()}. Open with the application version that created the recording."
        }
        recording.validate()
    }

    private fun checkDepth(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { char ->
            if (quoted) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    quoted = false
                }
            } else {
                when (char) {
                    '"' -> {
                        quoted = true
                    }

                    '{', '[' -> {
                        depth++
                        require(depth <= maxDepth) { "Recording nesting is too deep" }
                    }

                    '}', ']' -> {
                        depth--
                        require(depth >= 0) { "Invalid JSON nesting" }
                    }
                }
            }
        }
        require(depth == 0 && !quoted) { "Incomplete JSON recording" }
    }
}
