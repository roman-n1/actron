package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.CommandRecoveryDecision
import actron.statechart.machine.CommandRecoveryStrategy
import actron.statechart.machine.InvocationCheckpoint
import actron.statechart.machine.InvokedMachine
import actron.statechart.machine.InvokedMachineStore
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MailboxConfig
import actron.statechart.machine.resumeInvokedMachineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Persists and resumes a complete parent/child composition, including both executors. */
@ExperimentalActronApi
class InvocationPersistence<PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event>(
    private val machine: InvokedMachine<PC, PA, PCMD, PE, CC, CA, CCMD, CE>,
    parentCodec: RecordingCodec<PC, PA, PCMD, PE>,
    childCodec: RecordingCodec<CC, CA, CCMD, CE>,
) {
    private val checkpoints = InvocationCheckpointCodec(machine, parentCodec, childCodec)
    internal val definitionKey: String get() = checkpoints.definitionKey

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

    fun save(checkpoint: InvocationCheckpoint<PC, PCMD, CC, CCMD>): String = checkpoints.encode(checkpoint)

    /** Validates both definitions and the owner activation before returning any restored state. */
    fun restore(saved: String): InvocationCheckpoint<PC, PCMD, CC, CCMD> = checkpoints.decode(saved)
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
