package actron.example.weather.core.debug

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.statechart.machine.*
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Session-only, shared across feature instances. UI visibility never controls recording. */
class WeatherHistory(
    val capacity: Int = 500,
) : InstanceKeeper.Instance {
    init {
        require(capacity > 0)
    }

    private val lock = Mutex()
    private val buffer = ArrayDeque<Entry>()
    private var nextEntry = 0L
    private var nextMachine = 0L
    private val mutableEntries = MutableStateFlow<List<Entry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    val panelVisible = MutableStateFlow(false)

    class Entry(
        val id: Long,
        val machine: String,
        val summary: String,
        val details: () -> String,
    )

    fun <C : Any, A : Action, CMD : Any, E : Event> observer(
        machine: Machine<C, A, CMD, E>,
        initial: C,
    ): DecisionTraceObserver<C, A, CMD, E> {
        val name = locked { "${machine.id.value} #${++nextMachine}" }
        return object : DecisionTraceObserver<C, A, CMD, E> {
            private var previous = machine.initialSnapshot(initial)

            override fun onDecided(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                explained: ExplainedDecision<C, CMD, E>,
            ) {
                val before = previous
                val decision = explained.decision
                previous = decision.snapshot
                append(name, "${decision.snapshot.configuration.active.joinToString()} · ${decision.outcome}") {
                    "Input: $machineInput\n\nBefore: $before\n\nAfter: ${decision.snapshot}\n\nDecision: $decision\n\nExplanation: ${explained.explanation.describe()}"
                }
            }

            override fun onRejected(
                action: A,
                rejection: Admission.Rejected,
            ) {
                append(name, "Rejected") { "Action: $action\n$rejection" }
            }

            override fun onClosed(
                queued: List<CommandId>,
                running: List<CommandId>,
            ) {
                append(name, "Cancelled on close") { "Queued: $queued\nRunning: $running" }
            }
        }
    }

    private fun append(
        machine: String,
        summary: String,
        details: () -> String,
    ) = locked {
        if (buffer.size == capacity) buffer.removeFirst()
        buffer.addLast(Entry(++nextEntry, machine, summary, details))
        mutableEntries.value = buffer.toList()
    }

    fun clear() =
        locked {
            buffer.clear()
            mutableEntries.value = emptyList()
        }

    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Only bounded buffer updates run under this lock, never formatting or IO.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
