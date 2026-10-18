package actron.example.weather.core.debug

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.statechart.machine.*
import actron.timetravel.BoundedMachineRecorder
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
        val replay: () -> HistoryReplay,
        val discard: () -> Unit,
    )

    fun <C : Any, A : Action, CMD : Any, E : Event> observer(
        machine: Machine<C, A, CMD, E>,
        initial: C,
    ): DecisionTraceObserver<C, A, CMD, E> {
        val name = locked { "${machine.id.value} #${++nextMachine}" }
        val recorder = BoundedMachineRecorder(machine, initial, capacity)
        return object : DecisionTraceObserver<C, A, CMD, E> {
            override fun onDecided(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                explained: ExplainedDecision<C, CMD, E>,
            ) {
                val decision = explained.decision
                when (val outcome = decision.outcome) {
                    DecisionOutcome.Handled -> recorder.onCommitted(input, machineInput, decision)
                    is DecisionOutcome.Ignored -> recorder.onIgnored(input, machineInput, outcome.reason)
                    is DecisionOutcome.Failed -> recorder.onFailed(input, machineInput, outcome.failure)
                }
                val index = recorder.nextStep - 1
                val explanation = explained.explanation.describe()
                append(
                    name,
                    "${decision.snapshot.configuration.active.joinToString()} · ${decision.outcome}",
                    details = {
                        val window = recorder.capture()
                        val position = (index - window.firstStep).toInt()
                        if (position !in window.recording.steps.indices) {
                            "This entry has been evicted"
                        } else {
                            "Input: ${window.recording.steps[position].input}\n\nBefore: ${window.recording.snapshotAt(position)}\n\nAfter: ${window.recording.snapshotAt(position + 1)}\n\nStep: ${window.recording.steps[position]}\n\nExplanation: $explanation"
                        }
                    },
                    replay = { RecordedHistoryReplay(machine, recorder.capture(), index) },
                    discard = { recorder.discardBefore(index + 1) },
                )
            }
        }
    }

    private fun append(
        machine: String,
        summary: String,
        details: () -> String,
        replay: () -> HistoryReplay,
        discard: () -> Unit,
    ) = locked {
        if (buffer.size == capacity) buffer.removeFirst().discard()
        buffer.addLast(Entry(++nextEntry, machine, summary, details, replay, discard))
        mutableEntries.value = buffer.toList()
    }

    fun clear() =
        locked {
            buffer.forEach { it.discard() }
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
