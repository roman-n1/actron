package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.core.InputId
import actron.observability.FailureDescriptor
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import kotlinx.coroutines.sync.Mutex

/** Whether the recorder observed the beginning it was configured to replay from. */
sealed interface RecordingOrigin {
    fun withProblem(accept: (String) -> Unit): Boolean

    data object InitialSnapshot : RecordingOrigin {
        override fun withProblem(accept: (String) -> Unit): Boolean = false
    }

    data class UnknownBeginning(
        val reason: String,
    ) : RecordingOrigin {
        override fun withProblem(accept: (String) -> Unit): Boolean {
            accept(reason)
            return true
        }
    }
}

/**
 * Records the run of a `MachineStore` as a [Recording]: register it as one of the store's
 * observers. It keeps the live objects (inputs, snapshots, commands, events); it is a debug
 * recorder, not a production journal, and its memory grows with the run.
 *
 * The callbacks come serialized from the store; [recording] may be read from any thread and
 * returns a snapshot of what was recorded so far. A run that did not start from
 * `machine.initialSnapshot(context)` (a restored snapshot that was started over, for example)
 * is noticed at its first step and reported through [origin]: such a recording cannot be
 * replayed from its initial snapshot.
 *
 * @param machine The machine of the store
 * @param context The context the store was created with
 */
class MachineRecorder<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    context: C,
) : DecisionObserver<C, A, CMD, E> {
    private val initial: MachineSnapshot<C> = machine.initialSnapshot(context)
    private val lock = Mutex()
    private val steps = ArrayList<RecordedStep<C, A, CMD, E>>()

    /**
     * Whether the first committed decision began at the configured initial snapshot: marks an unknown beginning when it did
     * not start from the initial snapshot the recorder was given.
     */
    var origin: RecordingOrigin = RecordingOrigin.InitialSnapshot
        private set

    override fun onCommitted(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        decision: Decision<C, CMD, E>,
    ) {
        locked {
            if (steps.isEmpty() && machineInput is MachineInput.Start) {
                val expected = machine.decide(initial, machineInput)
                if (expected.snapshot != decision.snapshot) {
                    origin = RecordingOrigin.UnknownBeginning("the run did not start from the recorder's initial snapshot (a restored snapshot started over?)")
                }
            }
            steps += RecordedStep.Committed(machineInput, decision)
        }
    }

    override fun onIgnored(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        reason: IgnoreReason,
    ) {
        locked { steps += RecordedStep.Ignored(machineInput, reason) }
    }

    override fun onFailed(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        failure: FailureDescriptor,
    ) {
        locked { steps += RecordedStep.Failed(machineInput, failure) }
    }

    /**
     * The recording so far.
     */
    fun recording(): Recording<C, A, CMD, E> = locked { Recording(machine.id, machine.version, initial, steps.toList()) }

    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder appends one step or copies the list.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
