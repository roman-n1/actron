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
) : InstanceKeeper.Instance {
    var clock: MachineClock = MachineClock.monotonic()
        private set
    val timeline = HistoryTimeline<WeatherFrame>(capacity)
    val panel = HistoryPanelState<WeatherFrame>()
    val panelLayout = actron.timetravel.compose.HistoryPanelLayoutState()
    val panelVisible = MutableStateFlow(false)
    var frame = WeatherFrame()
        private set
    var branch = 0L
        private set
    private val navigation = timeline.machine("Navigation", HistorySourceKind.Navigation)

    fun beginBranch(snapshot: WeatherFrame) {
        require(snapshot.canResume())
        branch++
        frame = snapshot
        val base = MachineClock.monotonic()
        val origin = snapshot.search.now
        clock =
            object : MachineClock {
                override fun now(): MachineTime = origin + (base.now() - MachineTime.Zero)

                override suspend fun delayUntil(deadline: MachineTime) = base.delayUntil(MachineTime.Zero + (deadline - origin))
            }
        timeline.append(navigation, branch, HistoryDetails("ContinueHere", "Time travel", "Handled", emptyList(), routeFields(snapshot.route)), frame)
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

    fun openCity(city: City) = navigate(WeatherRoute.Weather(freshWeather(city)), "CitySelected(${city.name})")

    fun back() = navigate(WeatherRoute.Search, "BackClicked")

    private fun navigate(
        route: WeatherRoute,
        input: String,
    ) {
        val before = frame.route
        frame = frame.copy(route = route).at(clock.now())
        timeline.append(navigation, branch, HistoryDetails(input, "Navigation", "Handled", routeFields(before), routeFields(route)), frame)
    }

    private fun routeFields(route: WeatherRoute): List<StateField> =
        when (route) {
            WeatherRoute.Search -> listOf(StateField("screen", "Search"))
            is WeatherRoute.Weather -> listOf(StateField("screen", "Weather"), StateField("city", route.checkpoint.snapshot.context.city.name))
        }

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
                val after = previous.carriedPast(step)
                update(after)
                frame = frame.at(after.now)

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
                timeline.append(identity, generation, details, frame, discard = { recorder.discardBefore(index) })
            }
        }
    }
}
