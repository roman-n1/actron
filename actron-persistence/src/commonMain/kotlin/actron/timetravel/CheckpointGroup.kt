package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.InvocationCheckpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Serializes an immutable host snapshot. It does not capture or start live executors. */
@ExperimentalActronApi
class CheckpointGroup<F : Any> internal constructor(
    private val initial: () -> F,
    private val bindings: List<CheckpointBinding<F>>,
) {
    fun save(frame: F): String = Json.encodeToString(GroupDocument.serializer(), GroupDocument(machines = bindings.associate { it.id to it.save(frame) }))

    /** Validates every member before constructing the frame. No partial frame is returned. */
    fun restore(saved: String): F {
        val document = Json.decodeFromString(GroupDocument.serializer(), saved)
        require(document.format == 1) { "Unsupported checkpoint group format" }
        require(document.machines.keys == bindings.map { it.id }.toSet()) { "Checkpoint group members differ" }
        val updates = bindings.map { it.prepareRestore(document.machines.getValue(it.id)) }
        return updates.fold(initial()) { frame, update -> update(frame) }
    }
}

@ExperimentalActronApi
class CheckpointGroupBuilder<F : Any> internal constructor() {
    private val bindings = mutableListOf<CheckpointBinding<F>>()

    fun <C : Any, A : Action, CMD : Any, E : Event> machine(
        persistence: MachinePersistence<C, A, CMD, E>,
        read: (F) -> ExecutorCheckpoint<C, CMD>,
        write: (F, ExecutorCheckpoint<C, CMD>) -> F,
    ) = register(
        CheckpointBinding.single(
            id = persistence.definitionKey,
            description = "checkpoint",
            encode = { frame -> persistence.save(read(frame)) },
            decode = { saved ->
                val checkpoint = persistence.restore(saved)
                val update: CheckpointFrameUpdate<F> = { frame -> write(frame, checkpoint) }
                update
            },
        ),
    )

    /** Zero or more lifetimes of one definition; list order is preserved. */
    fun <C : Any, A : Action, CMD : Any, E : Event> machines(
        persistence: MachinePersistence<C, A, CMD, E>,
        read: (F) -> List<ExecutorCheckpoint<C, CMD>>,
        write: (F, List<ExecutorCheckpoint<C, CMD>>) -> F,
    ) = register(
        CheckpointBinding(
            id = persistence.definitionKey,
            encode = { frame -> read(frame).map { persistence.save(it) } },
            decode = { values ->
                val checkpoints = values.map { persistence.restore(it) }
                val update: CheckpointFrameUpdate<F> = { frame -> write(frame, checkpoints) }
                update
            },
        ),
    )

    /** One parent/child invocation, validated and restored as a single member. */
    fun <PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event> invocation(
        persistence: InvocationPersistence<PC, PA, PCMD, PE, CC, CA, CCMD, CE>,
        read: (F) -> InvocationCheckpoint<PC, PCMD, CC, CCMD>,
        write: (F, InvocationCheckpoint<PC, PCMD, CC, CCMD>) -> F,
    ) = register(
        CheckpointBinding.single(
            id = persistence.definitionKey,
            description = "invocation checkpoint",
            encode = { frame -> persistence.save(read(frame)) },
            decode = { saved ->
                val checkpoint = persistence.restore(saved)
                val update: CheckpointFrameUpdate<F> = { frame -> write(frame, checkpoint) }
                update
            },
        ),
    )

    private fun register(binding: CheckpointBinding<F>) {
        require(bindings.none { it.id == binding.id }) { "Duplicate checkpoint group definition: ${binding.id}" }
        bindings += binding
    }

    internal fun build(initial: () -> F): CheckpointGroup<F> {
        require(bindings.isNotEmpty()) { "Checkpoint group must contain a machine" }
        return CheckpointGroup(initial, bindings.toList())
    }
}

/** Definition IDs supply stable keys; callers need neither string keys nor action casts. */
@ExperimentalActronApi
fun <F : Any> checkpointGroup(
    initial: () -> F,
    configure: CheckpointGroupBuilder<F>.() -> Unit,
): CheckpointGroup<F> = CheckpointGroupBuilder<F>().apply(configure).build(initial)

@Serializable
private data class GroupDocument(
    val format: Int = 1,
    val machines: Map<String, List<String>>,
)
