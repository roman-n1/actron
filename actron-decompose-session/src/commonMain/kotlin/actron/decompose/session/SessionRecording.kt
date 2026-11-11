package actron.decompose.session

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.observability.FailureDescriptor
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.timetravel.RecordedStep
import actron.timetravel.carriedPast

/** A historical position can be shown even when pending delivery prevents continuation. */
sealed interface SessionContinuation {
    data class Ready(
        val checkpoint: SessionCheckpoint,
    ) : SessionContinuation

    data class Unavailable(
        val reason: String,
    ) : SessionContinuation
}

internal class SessionRecording<C : Any, A : Action, CMD : Any, E : Event>(
    initial: ExecutorCheckpoint<C, CMD>,
    private val definition: SessionMachine<C, A, CMD, E>,
    private val committed: (MachineTime, Boolean) -> Unit,
) : DecisionObserver<C, A, CMD, E> {
    private var current = initial

    fun save(now: MachineTime): SavedMachine =
        SavedMachine(
            definition.machine.id.value,
            definition.persistence.save(current.copy(now = maxOf(now, current.now))),
        )

    override fun onCommitted(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        decision: Decision<C, CMD, E>,
    ) {
        current = current.carriedPast(RecordedStep.Committed(machineInput, decision))
        committed(current.now, decision.effects.isNotEmpty())
    }

    override fun onIgnored(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        reason: IgnoreReason,
    ) {
        current = current.carriedPast(RecordedStep.Ignored(machineInput, reason))
        committed(current.now, false)
    }

    override fun onFailed(
        input: InputAttribution,
        machineInput: MachineInput<A>,
        failure: FailureDescriptor,
    ) {
        current = current.carriedPast(RecordedStep.Failed(machineInput, failure))
        committed(current.now, false)
    }
}
