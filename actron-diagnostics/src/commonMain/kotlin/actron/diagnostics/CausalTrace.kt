package actron.diagnostics

import actron.core.InputId
import actron.statechart.machine.CommandId
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.EffectId
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TransitionId

/** Metadata-only decision receipt; selection is an actual observation, not inferred work. */
data class CausalTraceEvent(
    val definition: DefinitionId,
    val version: DefinitionVersion,
    val origin: TraceOrigin,
    val lineage: TraceLineage,
    val inputKind: String,
    val now: MachineTime,
    val provenance: TraceProvenance,
    val transitions: List<TransitionId>,
    val commands: List<CommandId>,
    val effects: List<EffectId>,
    val assessment: TraceAssessment,
) {
    val trace: TraceId get() = lineage.trace
    val root: TraceOrigin get() = lineage.root
    val parent: TraceParent get() = lineage.parent
    val outcome: String get() = assessment.outcome
    val incomplete: Boolean get() = origin.input !is InputId || lineage.incomplete

    fun withFailureType(accept: (String) -> Unit): Boolean {
        var reported = false
        for (fault in assessment.faults) {
            fault.withType {
                reported = true
                accept(it)
            }
            if (reported) break
        }
        return reported
    }
}

/**
 * Synchronous bounded callback on the recording caller's thread, after history retention and
 * release of the history lock. Calls from different Store threads may overlap and reach the sink
 * out of [TraceOrigin.sequence] order; a sink shared across those threads must be thread-safe.
 *
 * An exception propagates to the caller of the observer callback or `recordPersisted` without
 * rolling back the retained event or its cause indexes. SDK/network exporters must enqueue,
 * never call a Store back. The application owns exporter queues and their lifetime.
 */
fun interface DiagnosticSink {
    fun emit(event: CausalTraceEvent)
}

/** Deliberately excludes payloads, exception messages and business idempotency keys. */
fun CausalTraceEvent.attributes(): Map<String, String> =
    linkedMapOf(
        "actron.definition" to definition.value,
        "actron.version" to version.value,
        "actron.store" to origin.store.value,
        "actron.trace" to trace.value,
        "actron.sequence" to origin.sequence.toString(),
        "actron.input.kind" to inputKind,
        "actron.outcome" to outcome,
        "actron.incomplete" to incomplete.toString(),
        "actron.time" to now.toString(),
        "actron.transitions" to transitions.joinToString { it.index.toString() },
        "actron.commands" to commands.joinToString { it.value.toString() },
        "actron.effects" to effects.joinToString { it.value.toString() },
        "actron.root.store" to root.store.value,
        "actron.root.sequence" to root.sequence.toString(),
    ).also { values ->
        origin.input.correlate { values["actron.input"] = it.value.toString() }
        parent.withParent {
            values["actron.parent.store"] = it.store.value
            values["actron.parent.sequence"] = it.sequence.toString()
            it.input.correlate { id -> values["actron.parent.input"] = id.value.toString() }
        }
        root.input.correlate { values["actron.root.input"] = it.value.toString() }
        provenance.attributes(values)
        withFailureType { values["actron.failure.type"] = it }
        if (assessment.faults.isNotEmpty()) {
            values["actron.failure.source"] =
                assessment.faults
                    .first()
                    .source.name
        }
    }
