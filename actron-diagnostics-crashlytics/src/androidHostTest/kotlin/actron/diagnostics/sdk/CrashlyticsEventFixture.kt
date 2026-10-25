package actron.diagnostics.sdk

import actron.core.InputId
import actron.diagnostics.CausalTraceEvent
import actron.diagnostics.TraceAssessment
import actron.diagnostics.TraceFault
import actron.diagnostics.TraceLineage
import actron.diagnostics.TraceOrigin
import actron.diagnostics.TraceProvenance
import actron.observability.StoreInstanceId
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.MachineTime

internal fun crashlyticsEvent(
    faults: List<TraceFault> = emptyList(),
    outcome: String = "handled",
    store: String = "store",
    sequence: Long = 1,
): CausalTraceEvent {
    val origin = TraceOrigin(StoreInstanceId(store), InputId(sequence), sequence)
    return CausalTraceEvent(
        DefinitionId("workflow"),
        DefinitionVersion("1"),
        origin,
        TraceLineage.Root(origin),
        "dispatch",
        MachineTime.Zero,
        TraceProvenance.Local,
        emptyList(),
        emptyList(),
        emptyList(),
        TraceAssessment.Receipt(outcome, faults),
    )
}
