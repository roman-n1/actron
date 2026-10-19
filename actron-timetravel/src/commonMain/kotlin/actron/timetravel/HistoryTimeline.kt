package actron.timetravel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant

/** [id] identifies a lifetime; [stableId] groups lifetimes of one logical machine, independently of its display name. */
@Serializable
data class HistoryMachine(
    val id: Long,
    val name: String,
    val stableId: String = name,
)

@Serializable
data class StateField(
    val path: String,
    val value: String,
)

data class StateChange(
    val path: String,
    val before: String,
    val after: String,
)

@Serializable
data class HistoryDetails(
    val input: String,
    val source: String,
    val outcome: String,
    val before: List<StateField>,
    val after: List<StateField>,
    val operations: List<String> = emptyList(),
    val explanation: String = "",
) {
    val changes: List<StateChange> get() {
        val left = before.associate { it.path to it.value }
        val right = after.associate { it.path to it.value }
        return (left.keys + right.keys)
            .filter { left[it] != right[it] }
            .map { StateChange(it, left.getOrElse(it) { "<absent>" }, right.getOrElse(it) { "<absent>" }) }
    }
}

/** Structured values supplied by the application; no reflection or parsing of toString(). */
fun historyFields(value: JsonElement): List<StateField> {
    fun walk(
        node: JsonElement,
        path: String,
    ): List<StateField> =
        when (node) {
            is JsonObject -> if (node.isEmpty()) listOf(StateField(path, "{}")) else node.flatMap { (key, child) -> walk(child, if (path.isEmpty()) key else "$path.$key") }
            is JsonArray -> if (node.isEmpty()) listOf(StateField(path, "[]")) else node.flatMapIndexed { index, child -> walk(child, "$path[$index]") }
            else -> listOf(StateField(path, node.toString()))
        }
    return walk(value, "")
}

class HistoryEntry<S : Any>(
    val id: Long,
    val machine: HistoryMachine,
    val branch: Long,
    val at: Instant,
    val details: HistoryDetails,
    val snapshot: S,
    internal val discard: () -> Unit,
)

/** Bounded session-wide machine chronology with opaque snapshots supplied by the host. */
class HistoryTimeline<S : Any>(
    val capacity: Int = 500,
) {
    init {
        require(capacity > 0)
    }

    private val lock = Mutex()
    private var next = 0L
    private var nextMachine = 0L
    private val buffer = ArrayDeque<HistoryEntry<S>>()
    private val mutableEntries = MutableStateFlow<List<HistoryEntry<S>>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    fun machine(
        name: String,
        stableId: String = name,
    ): HistoryMachine =
        locked {
            HistoryMachine(++nextMachine, name, stableId)
        }

    fun append(
        machine: HistoryMachine,
        branch: Long,
        details: HistoryDetails,
        snapshot: S,
        at: Instant = Clock.System.now(),
        discard: () -> Unit = {},
    ) = locked {
        if (buffer.size == capacity) buffer.removeFirst().discard()
        buffer.addLast(HistoryEntry(++next, machine, branch, at, details, snapshot, discard))
        mutableEntries.value = buffer.toList()
    }

    /**
     * Removes the abandoned future, retaining the selected event and earlier entries.
     * [throughId] pins the old head so entries produced during successful restoration survive.
     * IDs are never reused. Prefix-eviction callbacks are not invoked for tail removal: they
     * could invalidate recorders still referenced by the retained earlier entries.
     */
    fun truncateAfter(
        entryId: Long,
        throughId: Long = Long.MAX_VALUE,
    ) = locked {
        require(entryId >= 0 && throughId >= entryId)
        val retained = buffer.filter { it.id <= entryId || it.id > throughId }
        buffer.clear()
        buffer.addAll(retained)
        mutableEntries.value = buffer.toList()
    }

    fun clear() =
        locked {
            buffer.forEach { it.discard() }
            buffer.clear()
            mutableEntries.value = emptyList()
        }

    companion object {
        /** Creates a detached timeline for inspection. Never connects to a machine or executor. */
        fun <S : Any> fromRecording(recording: HistoryRecording<S>): HistoryTimeline<S> {
            recording.validate()
            val timeline = HistoryTimeline<S>(maxOf(1, recording.entries.size))
            recording.entries.forEach { entry ->
                timeline.buffer.addLast(HistoryEntry(entry.id, entry.machine, entry.branch, Instant.parse(entry.at), entry.details, entry.snapshot, {}))
            }
            timeline.next = recording.entries.lastOrNull()?.id ?: 0L
            timeline.nextMachine = recording.entries.maxOfOrNull { it.machine.id } ?: 0L
            timeline.mutableEntries.value = timeline.buffer.toList()
            return timeline
        }
    }

    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) { }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
