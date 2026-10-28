package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.ActivationId
import actron.statechart.machine.ChildCheckpoint
import actron.statechart.machine.InvocationCheckpoint
import actron.statechart.machine.InvokedMachine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The composition document, child/parent payloads and their shared checkpoint invariants. */
internal class InvocationCheckpointCodec<PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event>(
    private val machine: InvokedMachine<PC, PA, PCMD, PE, CC, CA, CCMD, CE>,
    parentCodec: RecordingCodec<PC, PA, PCMD, PE>,
    childCodec: RecordingCodec<CC, CA, CCMD, CE>,
) {
    private val parent = MachineCheckpointCodec(machine.parent, parentCodec)
    private val child = MachineCheckpointCodec(machine.child, childCodec)
    internal val definitionKey: String get() = machine.parent.id.value

    fun encode(checkpoint: InvocationCheckpoint<PC, PCMD, CC, CCMD>): String {
        validate(checkpoint)
        val savedChild =
            when (val current = checkpoint.child) {
                ChildCheckpoint.Dormant -> SavedChild.Dormant
                is ChildCheckpoint.Active -> SavedChild.Active(current.owner.value, child.encode(current.checkpoint))
            }
        return Json.encodeToString(
            InvocationDocument.serializer(),
            InvocationDocument(
                ownerState = machine.node.value,
                childDefinition = machine.child.id.value,
                childVersion = machine.child.version.value,
                parent = parent.encode(checkpoint.parent),
                child = savedChild,
            ),
        )
    }

    /** Validates both definitions and the owner activation before returning any restored state. */
    fun decode(saved: String): InvocationCheckpoint<PC, PCMD, CC, CCMD> {
        val document = Json.decodeFromString(InvocationDocument.serializer(), saved)
        require(document.format == 1) { "Unsupported invocation checkpoint format" }
        require(document.ownerState == machine.node.value) { "Invocation owner state differs" }
        require(document.childDefinition == machine.child.id.value && document.childVersion == machine.child.version.value) { "Invoked child definition or version differs" }
        val restoredChild =
            when (val current = document.child) {
                SavedChild.Dormant -> ChildCheckpoint.Dormant
                is SavedChild.Active -> ChildCheckpoint.Active(ActivationId(current.owner), child.decode(current.checkpoint))
            }
        return InvocationCheckpoint(parent.decode(document.parent), restoredChild).also(::validate)
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
