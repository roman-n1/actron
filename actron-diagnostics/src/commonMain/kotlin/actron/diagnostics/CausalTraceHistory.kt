package actron.diagnostics

import actron.core.InputAttribution
import actron.observability.StoreInstanceId
import actron.statechart.machine.CommandId
import actron.statechart.machine.MessageId
import actron.statechart.machine.TimerId
import kotlinx.coroutines.sync.Mutex

/** Owns the sequence and all bounded cause indexes under a single lock. Never calls a sink. */
internal class CausalTraceHistory(
    private val capacity: Int,
) {
    private val lock = Mutex()
    private var sequence = 0L
    private val events = linkedMapOf<TraceOrigin, CausalTraceEvent>()
    private val commands = linkedMapOf<Pair<StoreInstanceId, CommandId>, CausalTraceEvent>()
    private val timers = linkedMapOf<Pair<StoreInstanceId, TimerId>, CausalTraceEvent>()
    private val effects = linkedMapOf<MessageId, CausalTraceEvent>()

    fun snapshot(): List<CausalTraceEvent> = locked { events.values.toList() }

    fun record(
        store: StoreInstanceId,
        input: InputAttribution,
        provenance: TraceProvenance,
        sequenceFailureMessage: String = "[Actron] Trace sequence exhausted",
        create: (TraceOrigin, TraceLineage) -> RecordedTrace,
    ): CausalTraceEvent =
        locked {
            check(sequence < Long.MAX_VALUE) { sequenceFailureMessage }
            val origin = TraceOrigin(store, input, ++sequence)
            val recorded = create(origin, ancestry(origin, provenance))
            val event = recorded.event
            events[origin] = event
            event.commands.forEach { commands[store to it] = event }
            recorded.timers.forEach { timers[store to it] = event }
            event.effects.forEach { effects[MessageId(store, it)] = event }
            // Each index has its own budget. A retained registration may outlive its event,
            // in which case ancestry remains linked but explicitly incomplete.
            trim(events)
            trim(commands)
            trim(timers)
            trim(effects)
            event
        }

    private fun ancestry(
        origin: TraceOrigin,
        provenance: TraceProvenance,
    ): TraceLineage {
        fun linked(parent: CausalTraceEvent): TraceLineage =
            TraceLineage.Linked(
                parent.trace,
                parent.root,
                parent.origin,
                parent.incomplete || parent.origin !in events || parent.root !in events,
            )
        return when (provenance) {
            TraceProvenance.Local -> {
                TraceLineage.Root(origin)
            }

            is TraceProvenance.Command -> {
                val key = origin.store to provenance.id
                if (key in commands) linked(commands.getValue(key)) else TraceLineage.Unresolved(origin)
            }

            is TraceProvenance.Timer -> {
                val key = origin.store to provenance.id
                if (key in timers) linked(timers.getValue(key)) else TraceLineage.Unresolved(origin)
            }

            is TraceProvenance.Bridge -> {
                if (provenance.message in effects) linked(effects.getValue(provenance.message)) else TraceLineage.Unresolved(origin)
            }
        }
    }

    private fun <K : Any, V : Any> trim(map: MutableMap<K, V>) {
        while (map.size > capacity) map.remove(map.keys.first())
    }

    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) { /* bounded metadata updates only */ }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/** Transient registration metadata: only the event and timer identities reach history. */
internal class RecordedTrace(
    val event: CausalTraceEvent,
    val timers: List<TimerId>,
)
