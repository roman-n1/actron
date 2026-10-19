package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.statechart.machine.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Executor data for one invocation. The host captures both sides at the same decision boundary. */
@ExperimentalActronApi
data class InvocationCheckpoint<PC : Any, PCMD : Any, CC : Any, CCMD : Any>(
    val parent: ExecutorCheckpoint<PC, PCMD>,
    val child: ChildCheckpoint<CC, CCMD> = ChildCheckpoint.Dormant,
) {
    val snapshot: InvocationSnapshot<PC, CC>
        get() =
            InvocationSnapshot(
                parent.snapshot,
                when (val current = child) {
                    ChildCheckpoint.Dormant -> ChildInvocation.Dormant
                    is ChildCheckpoint.Active -> ChildInvocation.Active(current.owner, current.checkpoint.snapshot)
                },
            )
}

@ExperimentalActronApi
sealed interface ChildCheckpoint<out C : Any, out CMD : Any> {
    data object Dormant : ChildCheckpoint<Nothing, Nothing>

    data class Active<C : Any, CMD : Any>(
        val owner: ActivationId,
        val checkpoint: ExecutorCheckpoint<C, CMD>,
    ) : ChildCheckpoint<C, CMD>
}

/**
 * Persists a pure InvokedMachine composition, including ownership and executor registrations.
 * Restore returns data, not a running coroutine executor. The invocation host resumes commands
 * and timers from the checkpoints and routes their inputs through InvokedMachine.decide.
 * Do not start two independent stores: that would bypass atomic parent/child routing.
 */
@ExperimentalActronApi
class InvocationPersistence<PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event>(
    private val machine: InvokedMachine<PC, PA, PCMD, PE, CC, CA, CCMD, CE>,
    parentCodec: RecordingCodec<PC, PA, PCMD, PE>,
    childCodec: RecordingCodec<CC, CA, CCMD, CE>,
) {
    private val parent = MachinePersistence(machine.parent, parentCodec)
    private val child = MachinePersistence(machine.child, childCodec)
    internal val definitionKey: String get() = machine.parent.id.value

    fun save(checkpoint: InvocationCheckpoint<PC, PCMD, CC, CCMD>): String {
        validate(checkpoint)
        val savedChild =
            when (val current = checkpoint.child) {
                ChildCheckpoint.Dormant -> SavedChild.Dormant
                is ChildCheckpoint.Active -> SavedChild.Active(current.owner.value, child.save(current.checkpoint))
            }
        return Json.encodeToString(
            InvocationDocument.serializer(),
            InvocationDocument(
                ownerState = machine.node.value,
                childDefinition = machine.child.id.value,
                childVersion = machine.child.version.value,
                parent = parent.save(checkpoint.parent),
                child = savedChild,
            ),
        )
    }

    /** Validates both definitions and the owner activation before returning any restored state. */
    fun restore(saved: String): InvocationCheckpoint<PC, PCMD, CC, CCMD> {
        val document = Json.decodeFromString(InvocationDocument.serializer(), saved)
        require(document.format == 1) { "Unsupported invocation checkpoint format" }
        require(document.ownerState == machine.node.value) { "Invocation owner state differs" }
        require(document.childDefinition == machine.child.id.value && document.childVersion == machine.child.version.value) { "Invoked child definition or version differs" }
        val restoredChild =
            when (val current = document.child) {
                SavedChild.Dormant -> ChildCheckpoint.Dormant
                is SavedChild.Active -> ChildCheckpoint.Active(ActivationId(current.owner), child.restore(current.checkpoint))
            }
        return InvocationCheckpoint(parent.restore(document.parent), restoredChild).also(::validate)
    }

    private fun validate(checkpoint: InvocationCheckpoint<PC, PCMD, CC, CCMD>) {
        val snapshot = checkpoint.snapshot
        require(snapshot.child.matchesParent(snapshot.parent.activations, machine.node)) { "Invocation child does not match parent activation" }
        when (val current = checkpoint.child) {
            ChildCheckpoint.Dormant -> {
                Unit
            }

            is ChildCheckpoint.Active -> {
                require(current.checkpoint.snapshot.isStarted) { "Active invocation requires a started child" }
                require(current.checkpoint.now == checkpoint.parent.now) { "Invocation checkpoints must share a clock instant" }
            }
        }
    }
}

@ExperimentalActronApi
inline fun <reified PC : Any, reified PA : Action, reified PCMD : Any, reified PE : Event, reified CC : Any, reified CA : Action, reified CCMD : Any, reified CE : Event> invocationPersistence(
    machine: InvokedMachine<PC, PA, PCMD, PE, CC, CA, CCMD, CE>,
): InvocationPersistence<PC, PA, PCMD, PE, CC, CA, CCMD, CE> =
    InvocationPersistence(
        machine,
        RecordingCodec(kotlinx.serialization.serializer<PC>(), kotlinx.serialization.serializer<PA>(), kotlinx.serialization.serializer<PCMD>(), kotlinx.serialization.serializer<PE>()),
        RecordingCodec(kotlinx.serialization.serializer<CC>(), kotlinx.serialization.serializer<CA>(), kotlinx.serialization.serializer<CCMD>(), kotlinx.serialization.serializer<CE>()),
    )

@Serializable
private data class InvocationDocument(
    val format: Int = 1,
    val ownerState: String,
    val childDefinition: String,
    val childVersion: String,
    val parent: String,
    val child: SavedChild,
)

@Serializable
private sealed interface SavedChild {
    @Serializable
    @SerialName("dormant")
    data object Dormant : SavedChild

    @Serializable
    @SerialName("active")
    data class Active(
        val owner: Long,
        val checkpoint: String,
    ) : SavedChild
}
