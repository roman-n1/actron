package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.observability.FailureDescriptor
import actron.statechart.machine.*
import kotlinx.coroutines.sync.Mutex

/** An immutable, atomically captured recording window. Indices are absolute within one recorder. */
data class RecordingWindow<C : Any, A : Action, CMD : Any, E : Event>(
    val firstStep: Long,
    val recording: Recording<C, A, CMD, E>,
    val origin: RecordingOrigin,
) {
    val nextStep: Long get() = firstStep + recording.length
}

/**
 * A bounded in-memory recording, including the executor checkpoint before the oldest step.
 * Eviction carries that checkpoint through discarded inputs using the same lane bookkeeping
 * as Recording and file segments. Retained windows can be replayed and branched without IO.
 * Capacity bounds steps, not payload bytes; callers retaining captures own those extra copies.
 * Attach before start, or supply an exact executor checkpoint before attaching mid-run.
 */
class BoundedMachineRecorder<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    checkpoint: ExecutorCheckpoint<C, CMD>,
    val capacity: Int = 500,
) : DecisionObserver<C, A, CMD, E> {
    constructor(machine: Machine<C, A, CMD, E>, context: C, capacity: Int = 500) :
        this(machine, ExecutorCheckpoint.initial<C, CMD>(machine.initialSnapshot(context)), capacity)

    constructor(machine: Machine<C, A, CMD, E>, checkpoint: ExecutorCheckpoint<C, CMD>, configuration: HistoryConfiguration) : this(machine, checkpoint, configuration.capacity)

    constructor(machine: Machine<C, A, CMD, E>, context: C, configuration: HistoryConfiguration) : this(machine, context, configuration.capacity)

    init {
        require(capacity > 0) { "[Actron] Recording capacity must be positive" }
        require(checkpoint.snapshot.definition == machine.id && checkpoint.snapshot.version == machine.version) { "[Actron] Checkpoint belongs to another machine" }
    }

    private val lock = Mutex()
    private val steps = ArrayDeque<RecordedStep<C, A, CMD, E>>()
    private var start = checkpoint
    private var first = 0L
    private var observed = false
    private var origin: RecordingOrigin = RecordingOrigin.InitialSnapshot

    val nextStep: Long get() = locked { first + steps.size }

    override fun onCommitted(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        decision: Decision<C, CMD, E>,
    ) = locked {
        if (!observed && machineInput is MachineInput.Start) {
            if (machine.decide(start.snapshot, machineInput).snapshot != decision.snapshot) {
                origin = RecordingOrigin.UnknownBeginning("the run did not start from the recorder's checkpoint")
            }
        }
        append(RecordedStep.Committed(machineInput, decision))
    }

    override fun onIgnored(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        reason: IgnoreReason,
    ) = locked {
        append(RecordedStep.Ignored(machineInput, reason))
    }

    override fun onFailed(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        failure: FailureDescriptor,
    ) = locked {
        append(RecordedStep.Failed(machineInput, failure))
    }

    /** Captures the boundary and steps under one lock; later eviction cannot change this value. */
    fun capture(): RecordingWindow<C, A, CMD, E> =
        locked {
            RecordingWindow(first, Recording(machine.id, machine.version, start, steps.toList()), origin)
        }

    fun recording(): Recording<C, A, CMD, E> = capture().recording

    /** Drop a prefix by absolute index, preserving replay and continuing the same index sequence. */
    fun discardBefore(index: Long) =
        locked {
            require(index in 0..(first + steps.size)) { "[Actron] Cannot discard inputs not yet recorded" }
            while (steps.isNotEmpty() && first < index) evict()
        }

    /** Clear retained steps, retaining only the checkpoint needed to record the next input. */
    fun clear() =
        locked {
            while (steps.isNotEmpty()) evict()
        }

    private fun append(step: RecordedStep<C, A, CMD, E>) {
        observed = true
        if (steps.size == capacity) evict()
        steps.addLast(step)
    }

    private fun evict() {
        val next = start.carriedPast(steps.first())
        steps.removeFirst()
        start = next
        first++
    }

    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // The lock protects the checkpoint boundary and bounded step window.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
