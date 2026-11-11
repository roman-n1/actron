package actron.example.weather.app.debug

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.decompose.session.RestorableComponent
import actron.decompose.session.SessionCheckpoint
import actron.decompose.session.SessionContinuation
import actron.example.weather.app.root.WeatherRootComponent
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.statechart.StateId
import actron.statechart.machine.*
import actron.timetravel.*
import actron.timetravel.compose.HistoryGraph
import actron.timetravel.compose.HistoryGraphCoverage
import actron.timetravel.compose.HistoryPanelState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow

/** Display projection plus the library's immutable continuation point, when delivery is settled. */
data class WeatherHistoryFrame(
    val screen: WeatherDisplay,
    val active: Set<StateId>,
    val coverage: Map<DefinitionId, TransitionCoverage> = emptyMap(),
    val continuation: SessionContinuation = SessionContinuation.Unavailable("Recording is view-only"),
)

/** Optional diagnostic history. Live application state belongs exclusively to the machines. */
class WeatherHistory(
    val capacity: Int = 500,
    val exportSettings: HistoryExportSettings = HistoryExportSettings("Weather"),
) : com.arkivanov.essenty.instancekeeper.InstanceKeeper.Instance {
    private val lock = SynchronizedObject()
    private var coverage: Map<DefinitionId, TransitionCoverage> = emptyMap()
    private var capture: () -> SessionContinuation = { SessionContinuation.Unavailable("Recording is view-only") }
    private var resume: suspend (SessionCheckpoint) -> Unit = { error("No restorable root is connected") }

    fun connect(root: RestorableComponent<WeatherRootComponent>) {
        capture = root::recordedCheckpoint
        resume = root::continueFrom
    }

    suspend fun continueFrom(frame: WeatherHistoryFrame) {
        val point = frame.continuation
        require(point is SessionContinuation.Ready) { "This history position cannot continue" }
        val previous = coverage
        coverage = frame.coverage
        runCatching { resume(point.checkpoint) }.onFailure { coverage = previous }.getOrThrow()
    }

    val timeline = HistoryTimeline<WeatherHistoryFrame>(capacity)
    val panel = HistoryPanelState<WeatherHistoryFrame>()
    val panelLayout = actron.timetravel.compose.HistoryPanelLayoutState()
    val panelVisible = MutableStateFlow(false)
    private val charts =
        mapOf(
            "city-search" to CitySearchMachine.create(LocalCitySearchRepository).chart,
            "city-weather" to CityWeatherMachine.create().chart,
            "current-weather" to CurrentWeatherMachine.create(SampleWeatherRepository).chart,
            "hourly-forecast" to HourlyForecastMachine.create(SampleHourlyRepository).chart,
            "weather-advice" to WeatherAdviceMachine.create(SampleWeatherRepository).chart,
        )

    fun graph(entry: HistoryEntry<WeatherHistoryFrame>): HistoryGraph =
        if (entry.machine.stableId in charts) {
            HistoryGraph.Machine(
                charts.getValue(entry.machine.stableId),
                entry.snapshot.active,
                HistoryGraphCoverage.Recorded(
                    entry.snapshot.coverage[DefinitionId(entry.machine.stableId)]
                        ?.hits
                        .orEmpty(),
                ),
            )
        } else {
            HistoryGraph.Unavailable
        }

    fun <C : Any, A : Action, CMD : Any, E : Event> observer(
        machine: Machine<C, A, CMD, E>,
        checkpoint: ExecutorCheckpoint<C, CMD>,
        describe: (C) -> List<StateField>,
        project: (C) -> WeatherDisplay,
    ): DecisionTraceObserver<C, A, CMD, E> {
        val identity =
            timeline.machine(
                when (machine.id.value) {
                    "city-search" -> "City Search"
                    "city-weather" -> "City Weather"
                    "current-weather" -> "Current Weather"
                    "hourly-forecast" -> "Hourly Forecast"
                    "weather-advice" -> "Weather Advice"
                    else -> machine.id.value
                },
                stableId = machine.id.value,
            )
        project(checkpoint.snapshot.context)
        // The per-instance ring buffer retains executable decisions; timeline stores host frames.
        val recorder = BoundedMachineRecorder(machine, checkpoint, capacity)
        var previous = checkpoint
        return object : DecisionTraceObserver<C, A, CMD, E> {
            // Keep a decision pending until the executor confirms processing.
            private var pending: () -> Unit = {}

            private fun committed() {
                val append = pending
                pending = {}
                synchronized(lock) { append() }
            }

            override fun onCommitted(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                decision: Decision<C, CMD, E>,
            ) = committed()

            override fun onIgnored(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                reason: IgnoreReason,
            ) = committed()

            override fun onFailed(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                failure: actron.observability.FailureDescriptor,
            ) = committed()

            override fun onDecided(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                explained: ExplainedDecision<C, CMD, E>,
            ) {
                pending = {
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
                    val nextCoverage = coverage.getOrElse(machine.id) { TransitionCoverage(machine.id, machine.version) }.record(decision)
                    coverage = coverage + (machine.id to nextCoverage)

                    fun fields(value: MachineSnapshot<C>) = listOf(StateField("active", value.configuration.active.joinToString()), StateField("revision", value.revision.toString())) + describe(value.context)
                    val details =
                        HistoryDetails(
                            input = machineInput.description(),
                            source = machineInput.source(),
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
                    val frame =
                        WeatherHistoryFrame(
                            screen = project(after.snapshot.context),
                            active =
                                after.snapshot.configuration.active
                                    .toSet(),
                            coverage = coverage,
                            continuation = capture(),
                        )
                    timeline.append(identity, 0, details, frame, discard = { recorder.discardBefore(index) })
                }
            }
        }
    }
}

private fun MachineInput<*>.description(): String =
    when (this) {
        is MachineInput.Dispatch -> action.toString()
        is MachineInput.CommandResult -> action.toString()
        is MachineInput.BridgeReceived -> action.toString()
        else -> toString()
    }

private fun MachineInput<*>.source(): String =
    when (this) {
        is MachineInput.Dispatch -> "Action"
        is MachineInput.TimerFired -> "Timer"
        is MachineInput.CommandResult -> "Command result"
        is MachineInput.BridgeReceived -> "Bridge"
        else -> "Runtime"
    }
