package actron.example.weather.core.debug

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.machine.*
import actron.timetravel.*
import actron.timetravel.compose.HistoryGraph
import actron.timetravel.compose.HistoryGraphCoverage
import actron.timetravel.compose.HistoryGraphProfile
import actron.timetravel.compose.HistoryPanelState
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface WeatherRoute {
    data object Search : WeatherRoute

    data class Weather(
        val checkpoint: ExecutorCheckpoint<WeatherContext, WeatherCommand>,
    ) : WeatherRoute
}

data class WeatherFrame(
    val search: ExecutorCheckpoint<CitySearchState, SearchCitiesCommand> = ExecutorCheckpoint.initial(CitySearchMachine.create().initialSnapshot(CitySearchState())),
    val route: WeatherRoute = WeatherRoute.Search,
    val coverage: Map<DefinitionId, TransitionCoverage> = emptyMap(),
    val profiles: Map<DefinitionId, MachineProfileSnapshot> = emptyMap(),
) {
    fun at(time: MachineTime): WeatherFrame =
        copy(
            search = search.copy(now = maxOf(time, search.now)),
            route =
                when (val screen = route) {
                    WeatherRoute.Search -> WeatherRoute.Search
                    is WeatherRoute.Weather -> WeatherRoute.Weather(screen.checkpoint.copy(now = maxOf(time, screen.checkpoint.now)))
                },
        )

    fun canResume(): Boolean =
        search.ending.isEmpty() && search.effects.isEmpty() &&
            when (val screen = route) {
                WeatherRoute.Search -> true
                is WeatherRoute.Weather -> screen.checkpoint.ending.isEmpty() && screen.checkpoint.effects.isEmpty()
            }
}

/** App adapter: owns typed machine checkpoints and Decompose route; UI lives in the library. */
class WeatherHistory(
    val capacity: Int = 500,
    val exportSettings: HistoryExportSettings = HistoryExportSettings("Weather"),
    initialFrame: WeatherFrame = WeatherFrame(),
    initialBranch: Long = 0,
    private val clockFactory: () -> MachineClock = { MachineClock.monotonic() },
) : InstanceKeeper.Instance {
    var clock: MachineClock = MachineClock.monotonic()
        private set
    val timeline = HistoryTimeline<WeatherFrame>(capacity)
    val panel = HistoryPanelState<WeatherFrame>()
    val panelLayout = actron.timetravel.compose.HistoryPanelLayoutState()
    val panelVisible = MutableStateFlow(false)
    var frame = initialFrame
        private set
    var branch = initialBranch
        private set

    private var resumableFrame = initialFrame

    init {
        require(initialFrame.canResume())
        resetClock(initialFrame)
    }

    /** Pause logical time while the process is absent, preserving remaining timer delays. */
    fun persistenceFrame(): WeatherFrame = resumableFrame.at(clock.now())

    fun beginBranch(snapshot: WeatherFrame) {
        require(snapshot.canResume())
        branch++
        frame = snapshot
        resumableFrame = snapshot
        resetClock(snapshot)
    }

    private fun resetClock(snapshot: WeatherFrame) {
        val base = clockFactory()
        val baseStart = base.now()
        val origin = snapshot.search.now
        clock =
            object : MachineClock {
                override fun now(): MachineTime = origin + (base.now() - baseStart)

                override suspend fun delayUntil(deadline: MachineTime) = base.delayUntil(baseStart + (deadline - origin))
            }
    }

    private val searchChart = CitySearchMachine.create().chart
    private val weatherChart = CityWeatherMachine.create().chart

    private fun profile(entry: HistoryEntry<WeatherFrame>): HistoryGraphProfile = entry.snapshot.profiles[DefinitionId(entry.machine.stableId)]?.let { HistoryGraphProfile.Recorded(it) } ?: HistoryGraphProfile.Unavailable

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
                    profile(entry),
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
                            profile(entry),
                        )
                    }
                }
            }

            else -> {
                HistoryGraph.Unavailable
            }
        }

    fun searchCheckpoint() = frame.search

    fun weatherCheckpoint(city: City): ExecutorCheckpoint<WeatherContext, WeatherCommand> =
        when (val route = frame.route) {
            is WeatherRoute.Weather -> if (route.checkpoint.snapshot.context.city == city) route.checkpoint else freshWeather(city)
            WeatherRoute.Search -> freshWeather(city)
        }

    private fun freshWeather(city: City): ExecutorCheckpoint<WeatherContext, WeatherCommand> = ExecutorCheckpoint.initial(CityWeatherMachine.create().initialSnapshot(WeatherContext(city)))

    fun updateSearch(checkpoint: ExecutorCheckpoint<CitySearchState, SearchCitiesCommand>) {
        frame = frame.copy(search = checkpoint)
    }

    fun updateWeather(checkpoint: ExecutorCheckpoint<WeatherContext, WeatherCommand>) {
        frame = frame.copy(route = WeatherRoute.Weather(checkpoint))
    }

    fun openCity(city: City) {
        frame = frame.copy(route = WeatherRoute.Weather(freshWeather(city))).at(clock.now())
        if (frame.canResume()) resumableFrame = frame
    }

    fun back() {
        frame = frame.copy(route = WeatherRoute.Search).at(clock.now())
        if (frame.canResume()) resumableFrame = frame
    }

    fun <C : Any, A : Action, CMD : Any, E : Event> observer(
        machine: Machine<C, A, CMD, E>,
        checkpoint: ExecutorCheckpoint<C, CMD>,
        describe: (C) -> List<StateField>,
        update: (ExecutorCheckpoint<C, CMD>) -> Unit,
        profiler: MachineProfiler<C, A, CMD, E> = MachineProfiler(machine),
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
                // Freeze completed measurements before this event. They never follow the live store
                // when an old frame is selected; imported display recordings omit profiling.
                frame = frame.at(after.now).copy(coverage = frame.coverage + (machine.id to coverage), profiles = frame.profiles + (machine.id to profiler.snapshot()))
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
