package actron.example.weather.core.debug

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.core.session.*
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.machine.*
import actron.timetravel.*
import actron.timetravel.compose.HistoryGraph
import actron.timetravel.compose.HistoryGraphCoverage
import actron.timetravel.compose.HistoryPanelState
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.flow.MutableStateFlow

/** App adapter: owns typed machine checkpoints and Decompose route; UI lives in the library. */
class WeatherHistory(
    val capacity: Int = 500,
    val exportSettings: HistoryExportSettings = HistoryExportSettings("Weather"),
    initialFrame: WeatherFrame = WeatherFrame(),
    initialBranch: Long = 0,
    clockFactory: () -> MachineClock = { MachineClock.monotonic() },
) : WeatherSession(initialFrame, initialBranch, clockFactory) {
    val timeline = HistoryTimeline<WeatherFrame>(capacity)
    val panel = HistoryPanelState<WeatherFrame>()
    val panelLayout = actron.timetravel.compose.HistoryPanelLayoutState()
    val panelVisible = MutableStateFlow(false)
    private val searchChart = CitySearchMachine.create().chart
    private val weatherChart = CityWeatherMachine.create().chart

    fun graph(entry: HistoryEntry<WeatherFrame>): HistoryGraph =
        when (entry.machine.stableId) {
            "city-search" -> {
                HistoryGraph.Machine(
                    searchChart,
                    entry.snapshot.search.snapshot.configuration.active
                        .toSet(),
                    HistoryGraphCoverage.Recorded(
                        entry.snapshot.coverage[DefinitionId("city-search")]
                            ?.hits
                            .orEmpty(),
                    ),
                )
            }

            "city-weather" -> {
                when (val route = entry.snapshot.route) {
                    WeatherRoute.Search -> {
                        HistoryGraph.Unavailable
                    }

                    is WeatherRoute.Weather -> {
                        HistoryGraph.Machine(
                            weatherChart,
                            route.checkpoint.snapshot.configuration.active
                                .toSet(),
                            HistoryGraphCoverage.Recorded(
                                entry.snapshot.coverage[DefinitionId("city-weather")]
                                    ?.hits
                                    .orEmpty(),
                            ),
                        )
                    }
                }
            }

            else -> {
                HistoryGraph.Unavailable
            }
        }

    @androidx.compose.runtime.Composable
    override fun Render(
        onContinue: (WeatherFrame) -> Unit,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        WeatherDebugLayout(this, onContinue, content = content)
    }

    override fun <C : Any, A : Action, CMD : Any, E : Event> observe(
        machine: Machine<C, A, CMD, E>,
        checkpoint: ExecutorCheckpoint<C, CMD>,
        describe: (C) -> kotlinx.serialization.json.JsonElement,
        update: (ExecutorCheckpoint<C, CMD>) -> Unit,
    ): DecisionObserver<C, A, CMD, E> = observer(machine, checkpoint, { historyFields(describe(it)) }, update)

    fun <C : Any, A : Action, CMD : Any, E : Event> observer(
        machine: Machine<C, A, CMD, E>,
        checkpoint: ExecutorCheckpoint<C, CMD>,
        describe: (C) -> List<StateField>,
        update: (ExecutorCheckpoint<C, CMD>) -> Unit,
    ): DecisionTraceObserver<C, A, CMD, E> {
        val identity =
            timeline.machine(
                when (machine.id.value) {
                    "city-search" -> "City Search"
                    "city-weather" -> "City Weather"
                    else -> machine.id.value
                },
                stableId = machine.id.value,
            )
        val generation = branch
        // The per-instance ring buffer retains executable decisions; timeline stores host frames.
        val recorder = BoundedMachineRecorder(machine, checkpoint, capacity)
        var previous = checkpoint
        return object : DecisionTraceObserver<C, A, CMD, E> {
            override fun onDecided(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                explained: ExplainedDecision<C, CMD, E>,
            ) {
                if (generation != branch) return
                val decision = explained.decision
                val step: RecordedStep<C, A, CMD, E> =
                    when (val outcome = decision.outcome) {
                        DecisionOutcome.Handled -> {
                            recorder.onCommitted(input, machineInput, decision)
                            RecordedStep.Committed(machineInput, decision)
                        }

                        is DecisionOutcome.Ignored -> {
                            recorder.onIgnored(input, machineInput, outcome.reason)
                            RecordedStep.Ignored(machineInput, outcome.reason)
                        }

                        is DecisionOutcome.Failed -> {
                            recorder.onFailed(input, machineInput, outcome.failure)
                            RecordedStep.Failed(machineInput, outcome.failure)
                        }
                    }
                // Apply the recorded outcome to checkpoint bookkeeping without executing IO again.
                val after = previous.carriedPast(step)
                update(after)
                val coverage = frame.coverage.getOrElse(machine.id) { TransitionCoverage(machine.id, machine.version) }.record(decision)
                frame = frame.at(after.now).copy(coverage = frame.coverage + (machine.id to coverage))
                if (frame.canResume()) resumableFrame = frame

                fun fields(value: MachineSnapshot<C>) = listOf(StateField("active", value.configuration.active.joinToString()), StateField("revision", value.revision.toString())) + describe(value.context)
                val details =
                    HistoryDetails(
                        input =
                            when (machineInput) {
                                is MachineInput.Dispatch -> machineInput.action.toString()
                                is MachineInput.CommandResult -> machineInput.action.toString()
                                is MachineInput.BridgeReceived -> machineInput.action.toString()
                                else -> machineInput.toString()
                            },
                        source =
                            when (machineInput) {
                                is MachineInput.Dispatch -> "Action"
                                is MachineInput.TimerFired -> "Timer"
                                is MachineInput.CommandResult -> "Command result"
                                else -> "Runtime"
                            },
                        outcome = decision.outcome.toString(),
                        before = fields(previous.snapshot),
                        after = fields(after.snapshot),
                        operations =
                            decision.commands.map { "Start ${it.command}" } + decision.timersScheduled.map { "Schedule $it" } +
                                decision.timersCancelled.map { "Cancel timer $it" } + decision.cancelledScopes.map { "Cancel scope $it" },
                        explanation = explained.explanation.describe(),
                    )
                previous = after
                val index = recorder.nextStep
                // Global eviction trims the corresponding recorder too, bounding both histories.
                timeline.append(identity, generation, details, frame, discard = { recorder.discardBefore(index) })
            }
        }
    }
}
