package actron.diagnostics.sdk

import actron.core.InputId
import actron.diagnostics.CausalTraceEvent
import actron.diagnostics.FailureSource
import actron.diagnostics.TraceAssessment
import actron.diagnostics.TraceFault
import actron.diagnostics.TraceId
import actron.diagnostics.TraceLineage
import actron.diagnostics.TraceOrigin
import actron.diagnostics.TraceProvenance
import actron.observability.StoreInstanceId
import actron.statechart.machine.CommandId
import actron.statechart.machine.DecisionExplanation
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TransitionId

internal fun diagnosticEvent(
    parentage: (TraceOrigin) -> TraceLineage = { TraceLineage.Root(it, TraceId("trace")) },
    faults: List<TraceFault> = emptyList(),
    sequence: Long = 1,
): CausalTraceEvent {
    val origin = TraceOrigin(StoreInstanceId("s"), InputId(sequence), sequence)
    return CausalTraceEvent(
        DefinitionId("workflow"),
        DefinitionVersion("1"),
        origin,
        parentage(origin),
        "dispatch",
        MachineTime.Zero,
        TraceProvenance.Local,
        listOf(TransitionId(0)),
        listOf(CommandId(1)),
        emptyList(),
        TraceAssessment.Decided(DecisionExplanation(emptySet(), emptyList(), emptyList()), if (faults.isEmpty()) "handled" else "failed", faults),
    )
}

internal fun diagnosticChild(
    parent: TraceOrigin,
    faults: List<TraceFault> = emptyList(),
) = diagnosticEvent({ TraceLineage.Linked(TraceId("trace"), parent, parent, false) }, faults, sequence = 2)

internal fun diagnosticFailure(type: String) = listOf(TraceFault.of(FailureSource.Decision, actron.observability.FailureDescriptor(type)))

internal fun diagnosticRedactedFailure(): CausalTraceEvent {
    val faults = listOf(TraceFault.of(FailureSource.Command, actron.observability.FailureDescriptor.Unavailable))
    return diagnosticEvent(faults = faults).copy(assessment = TraceAssessment.Receipt("handled", faults))
}
