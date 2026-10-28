package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Decision
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot

/**
 * The first point where a replay differs from its recording.
 *
 * @property position The step that differed (0-based)
 * @property input The input of that step
 * @property expected What the recording says happened
 * @property actual What the machine decided now, on the snapshot the replay had at [position]
 * @property differences One line per field that differs, for the inspector
 */
data class ReplayMismatch<C : Any, A : Action, CMD : Any, E : Event>(
    val position: Int,
    val input: MachineInput<A>,
    val expected: RecordedStep<C, A, CMD, E>,
    val actual: Decision<C, CMD, E>,
    val differences: List<String>,
) {
    fun describe(): String = "Replay diverged at step $position on $input:\n" + differences.joinToString("\n") { "  - $it" }
}

/**
 * The result of one forward step of a [ReplaySession].
 */
sealed interface ReplayStep<C : Any, A : Action, CMD : Any, E : Event> {
    /** The cursor reached the checkpoint after the final recorded input. */
    data class Finished<C : Any, A : Action, CMD : Any, E : Event>(
        val position: Int,
    ) : ReplayStep<C, A, CMD, E>

    /** The machine decided the recorded input as recorded; [decision] is what it decided. */
    data class Matched<C : Any, A : Action, CMD : Any, E : Event>(
        val position: Int,
        val decision: Decision<C, CMD, E>,
    ) : ReplayStep<C, A, CMD, E>

    /** The machine decided differently; the session stays at the position. */
    data class Diverged<C : Any, A : Action, CMD : Any, E : Event>(
        val mismatch: ReplayMismatch<C, A, CMD, E>,
    ) : ReplayStep<C, A, CMD, E>
}

/**
 * Replays a [Recording] with [machine]: a cursor over the recorded steps with the snapshot at
 * each position, forward steps that decide the recorded input again and compare, and a
 * [branch] that continues from any position with new inputs.
 *
 * Nothing here executes: no command handler, no clock, no store. A recorded run is decided
 * again by the pure machine; a branch feeds the machine only what the caller gives it. The
 * recorded ids stay the recording's; a branch continues the counters of the snapshot it starts
 * from, so its ids follow the recorded ones without colliding.
 *
 * Positions are checkpoints: [seek] and [stepBackward] take the recorded snapshot and the
 * executor's state there and cost nothing; [stepForward] and [verify] are what compares.
 *
 * @throws IllegalArgumentException if [recording] is not [Compatibility.Replayable] by [machine]
 */
class ReplaySession<C : Any, A : Action, CMD : Any, E : Event>(
    val machine: Machine<C, A, CMD, E>,
    val recording: Recording<C, A, CMD, E>,
) {
    init {
        val compatibility = recording.compatibilityWith(machine)
        require(compatibility == Compatibility.Replayable) { "[Actron] The recording cannot be replayed: $compatibility" }
    }

    /** The number of steps. */
    val length: Int get() = recording.length

    /** The current position, 0 (before the first step) to [length]. */
    var position: Int = 0
        private set

    /** The snapshot at [position]. */
    val snapshot: MachineSnapshot<C> get() = recording.snapshotAt(position)

    /** Reports the next recorded step once; returns false at the end. */
    fun withNext(accept: (RecordedStep<C, A, CMD, E>) -> Unit): Boolean {
        if (position >= length) return false
        accept(recording.steps[position])
        return true
    }

    /**
     * Decides the next recorded input on the current snapshot and compares with the recording.
     * Advances on a match; stays on a divergence, and returns it again until [seek] moves on.
     * [ReplayStep.Finished] at the final checkpoint.
     */
    fun stepForward(): ReplayStep<C, A, CMD, E> {
        if (position >= length) return ReplayStep.Finished(position)
        val step = recording.steps[position]
        val decision = machine.decide(snapshot, step.input)
        val differences = ReplayComparison.differences(snapshot, step, decision)
        return if (differences.isEmpty()) {
            position++
            ReplayStep.Matched(position - 1, decision)
        } else {
            ReplayStep.Diverged(ReplayMismatch(position, step.input, step, decision, differences))
        }
    }

    /**
     * Moves one step back, to the recorded snapshot; `false` at the start.
     */
    fun stepBackward(): Boolean {
        if (position == 0) return false
        position--
        return true
    }

    /**
     * Moves to [position], a recorded checkpoint, without deciding anything.
     *
     * @throws IllegalArgumentException if [position] is outside 0..[length]
     */
    fun seek(position: Int) {
        require(position in 0..length) { "[Actron] Position $position is outside 0..$length" }
        this.position = position
    }

    /**
     * Decides the recorded steps [from] until [to] again, from the recorded snapshot at [from],
     * and reports the first divergence through [onMismatch]. Returns true when reported; does not move.
     */
    fun verify(
        from: Int = 0,
        to: Int = length,
        onMismatch: (ReplayMismatch<C, A, CMD, E>) -> Unit,
    ): Boolean =
        when (val result = verification(from, to)) {
            ReplayVerification.Passed -> {
                false
            }

            is ReplayVerification.Diverged -> {
                onMismatch(result.mismatch)
                true
            }
        }

    /** Verifies [from] until [to] and returns an explicit result; stops at the first divergence and does not move. */
    fun verification(
        from: Int = 0,
        to: Int = length,
    ): ReplayVerification<C, A, CMD, E> {
        require(from in 0..to && to <= length) { "[Actron] Range $from..$to is outside 0..$length" }
        var current = recording.snapshotAt(from)
        for (index in from until to) {
            val step = recording.steps[index]
            val actual = machine.decide(current, step.input)
            val differences = ReplayComparison.differences(current, step, actual)
            if (differences.isNotEmpty()) {
                return ReplayVerification.Diverged(ReplayMismatch(index, step.input, step, actual, differences))
            }
            if (step is RecordedStep.Committed) current = step.decision.snapshot
        }
        return ReplayVerification.Passed
    }

    /** Invariant violations at the selected checkpoint, usable by an inspector without replay. */
    fun checkInvariants(): List<actron.statechart.machine.InvariantViolation> = machine.checkInvariants(snapshot)

    /** Explains the next recorded input through one pure decision; does not move the cursor. */
    fun explainNext(accept: (actron.statechart.machine.ExplainedDecision<C, CMD, E>) -> Unit): Boolean = withNext { accept(machine.decideExplained(snapshot, it.input)) }

    /** The executor's state at [position]; see [Recording.checkpointAt]. */
    val checkpoint: ExecutorCheckpoint<C, CMD> get() = recording.checkpointAt(position)

    /**
     * A new branch from the current position: the checkpoint there (the recorded snapshot, the
     * clock of the last recorded input, the commands running and queued), ready for inputs of
     * the caller's choosing.
     */
    fun branch(): Branch<C, A, CMD, E> = Branch(machine, checkpoint, recording)
}
