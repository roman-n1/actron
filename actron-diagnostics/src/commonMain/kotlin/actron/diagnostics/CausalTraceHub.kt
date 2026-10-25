package actron.diagnostics

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.observability.StoreInstanceId
import actron.statechart.machine.CommandId
import actron.statechart.machine.DecisionTraceObserver
import actron.statechart.machine.DurableCommit
import actron.statechart.machine.ExplainedDecision
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput

/**
 * Shared across Store observers to correlate command, timer and bridge effect inputs. Bounded
 * metadata only, safe across Store threads. Eviction/unknown ids are explicit incomplete traces.
 * It observes the actual decision once and never evaluates guards or executable rules.
 * Recorded selections are detached snapshots with metadata-only guard failures.
 *
 * History updates are serialized; calls to [sink] follow the [DiagnosticSink] contract and are
 * not serialized across Store threads. Sink failures leave history intact and propagate to the
 * recording caller.
 */
class CausalTraceHub(
    private val capacity: Int = 100,
    private val sink: DiagnosticSink = DiagnosticSink { },
) {
    init {
        require(capacity > 0) { "[Actron] Trace capacity must be positive" }
    }

    private val history = CausalTraceHistory(capacity)

    fun snapshot(): List<CausalTraceEvent> = history.snapshot()

    /** Observes an authentic commit after CAS succeeds, without inventing guard observations. */
    fun <C : Any, A : Action, CMD : Any, E : Event> recordPersisted(
        machine: Machine<C, A, CMD, E>,
        store: StoreInstanceId,
        input: InputAttribution,
        commit: DurableCommit<C, CMD, E>,
    ): CausalTraceEvent = persisted(machine, store, input, commit, TraceProvenance.Local)

    /** Correlates an outbox receipt with its real registering command. */
    fun <C : Any, A : Action, CMD : Any, E : Event> recordPersisted(
        machine: Machine<C, A, CMD, E>,
        store: StoreInstanceId,
        input: InputAttribution,
        commit: DurableCommit<C, CMD, E>,
        parentCommand: CommandId,
    ): CausalTraceEvent = persisted(machine, store, input, commit, TraceProvenance.Command(parentCommand))

    private fun <C : Any, A : Action, CMD : Any, E : Event> persisted(
        machine: Machine<C, A, CMD, E>,
        store: StoreInstanceId,
        input: InputAttribution,
        commit: DurableCommit<C, CMD, E>,
        provenance: TraceProvenance,
    ): CausalTraceEvent {
        val events = CausalTraceEvents(machine)
        events.validatePersisted(commit)
        val event =
            history.record(store, input, provenance, sequenceFailureMessage = "Check failed.") { origin, lineage ->
                events.persisted(commit, origin, lineage, provenance)
            }
        return publish(event)
    }

    fun <C : Any, A : Action, CMD : Any, E : Event> observer(
        machine: Machine<C, A, CMD, E>,
        store: StoreInstanceId,
    ): DecisionTraceObserver<C, A, CMD, E> {
        val events = CausalTraceEvents(machine)
        return object : DecisionTraceObserver<C, A, CMD, E> {
            override fun onDecided(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                explained: ExplainedDecision<C, CMD, E>,
            ) {
                events.validateObserved(explained)
                val provenance = machineInput.traceProvenance()
                val event =
                    history.record(store, input, provenance) { origin, lineage ->
                        events.observed(machineInput, explained, origin, lineage, provenance)
                    }
                publish(event)
            }
        }
    }

    private fun publish(event: CausalTraceEvent): CausalTraceEvent {
        // Synchronous delivery follows retention, outside the history lock. A sink failure
        // propagates to the caller and does not roll back the event or its cause indexes.
        sink.emit(event)
        return event
    }
}
