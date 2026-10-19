package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.statechart.machine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/** Persists and resumes a complete parent/child composition, including both executors. */
@ExperimentalActronApi
class InvocationPersistence<PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event>(
    private val machine: InvokedMachine<PC, PA, PCMD, PE, CC, CA, CCMD, CE>,
    parentCodec: RecordingCodec<PC, PA, PCMD, PE>,
    childCodec: RecordingCodec<CC, CA, CCMD, CE>,
) {
    private val parent = MachinePersistence(machine.parent, parentCodec)
    private val child = MachinePersistence(machine.child, childCodec)
    internal val definitionKey: String get() = machine.parent.id.value

    /** Captures both sides through the composition's ordered mailbox. */
    suspend fun save(store: InvokedMachineStore<PC, PA, PCMD, PE, CC, CA, CCMD, CE>): String = save(store.checkpoint())

    /** Restores an unstarted executor. Close the previous instance before replacing it. */
    fun resume(
        saved: String,
        parentHandler: CommandHandler<PCMD, PA>,
        childHandler: CommandHandler<CCMD, CA>,
        scope: CoroutineScope,
        coroutineContext: CoroutineContext = scope.coroutineContext,
        clock: MachineClock = MachineClock.monotonic(),
        parentRecovery: CommandRecoveryStrategy<PCMD, PA> = CommandRecoveryStrategy { CommandRecoveryDecision.Restart },
        childRecovery: CommandRecoveryStrategy<CCMD, CA> = CommandRecoveryStrategy { CommandRecoveryDecision.Restart },
        mailbox: MailboxConfig<PE> = MailboxConfig(),
        onError: (Throwable) -> Unit = { error -> scope.launch { throw error } },
    ): InvokedMachineStore<PC, PA, PCMD, PE, CC, CA, CCMD, CE> =
        resumeInvokedMachineStore(
            machine,
            restore(saved),
            parentHandler,
            childHandler,
            scope,
            coroutineContext,
            clock,
            parentRecovery,
            childRecovery,
            mailbox,
            onError,
        )

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
