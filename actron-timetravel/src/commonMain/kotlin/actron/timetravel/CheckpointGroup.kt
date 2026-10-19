package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.statechart.machine.ExecutorCheckpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Serializes an immutable host snapshot. It does not capture or start live executors. */
@ExperimentalActronApi
class CheckpointGroup<F : Any> internal constructor(
    private val initial: () -> F,
    private val bindings: List<CheckpointBinding<F>>,
) {
    fun save(frame: F): String = Json.encodeToString(GroupDocument.serializer(), GroupDocument(machines = bindings.associate { it.id to it.encode(frame) }))

    /** Validates every member before constructing the frame. No partial frame is returned. */
    fun restore(saved: String): F {
        val document = Json.decodeFromString(GroupDocument.serializer(), saved)
        require(document.format == 1) { "Unsupported checkpoint group format" }
        require(document.machines.keys == bindings.map { it.id }.toSet()) { "Checkpoint group members differ" }
        val updates = bindings.map { it.decode(document.machines.getValue(it.id)) }
        return updates.fold(initial()) { frame, update -> update(frame) }
    }
}

@ExperimentalActronApi
class CheckpointGroupBuilder<F : Any> internal constructor() {
    internal val bindings = mutableListOf<CheckpointBinding<F>>()

    fun <C : Any, A : Action, CMD : Any, E : Event> machine(
        persistence: MachinePersistence<C, A, CMD, E>,
        read: (F) -> ExecutorCheckpoint<C, CMD>,
        write: (F, ExecutorCheckpoint<C, CMD>) -> F,
    ) = add(persistence, { listOf(read(it)) }, { frame, values -> write(frame, values.single()) }, single = true)

    /** Zero or more lifetimes of one definition; list order is preserved. */
    fun <C : Any, A : Action, CMD : Any, E : Event> machines(
        persistence: MachinePersistence<C, A, CMD, E>,
        read: (F) -> List<ExecutorCheckpoint<C, CMD>>,
        write: (F, List<ExecutorCheckpoint<C, CMD>>) -> F,
    ) = add(persistence, read, write, single = false)

    private fun <C : Any, A : Action, CMD : Any, E : Event> add(
        persistence: MachinePersistence<C, A, CMD, E>,
        read: (F) -> List<ExecutorCheckpoint<C, CMD>>,
        write: (F, List<ExecutorCheckpoint<C, CMD>>) -> F,
        single: Boolean,
    ) {
        val id = persistence.definitionKey
        require(bindings.none { it.id == id }) { "Duplicate checkpoint group definition: $id" }
        bindings +=
            CheckpointBinding(id, { frame -> read(frame).map { persistence.save(it) } }, { values ->
                require(!single || values.size == 1) { "Expected one checkpoint for $id" }
                val checkpoints = values.map { persistence.restore(it) }
                val update: (F) -> F = { frame -> write(frame, checkpoints) }
                update
            })
    }
}

/** Definition IDs supply stable keys; callers need neither string keys nor action casts. */
@ExperimentalActronApi
fun <F : Any> checkpointGroup(
    initial: () -> F,
    configure: CheckpointGroupBuilder<F>.() -> Unit,
): CheckpointGroup<F> {
    val builder = CheckpointGroupBuilder<F>().apply(configure)
    require(builder.bindings.isNotEmpty()) { "Checkpoint group must contain a machine" }
    return CheckpointGroup(initial, builder.bindings.toList())
}

internal class CheckpointBinding<F : Any>(
    val id: String,
    val encode: (F) -> List<String>,
    val decode: (List<String>) -> (F) -> F,
)

@Serializable
private data class GroupDocument(
    val format: Int = 1,
    val machines: Map<String, List<String>>,
)
