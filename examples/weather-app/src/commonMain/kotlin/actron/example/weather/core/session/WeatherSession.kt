package actron.example.weather.core.session

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.example.weather.core.domain.City
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.SearchCitiesCommand
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.example.weather.feature.weather.presentation.WeatherCommand
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.observability.FailureDescriptor
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineProfileSnapshot
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TransitionCoverage
import actron.statechart.machine.resumeMachineStore
import actron.timetravel.RecordedStep
import actron.timetravel.carriedPast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.CoroutineContext

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

/** A current checkpoint per active machine. No event history or debugger dependency. */
open class WeatherSession(
    initialFrame: WeatherFrame = WeatherFrame(),
    initialBranch: Long = 0,
    private val clockFactory: () -> MachineClock = { MachineClock.monotonic() },
) : InstanceKeeper.Instance {
    var clock: MachineClock = MachineClock.monotonic()
        private set
    var frame = initialFrame
        protected set
    var branch = initialBranch
        private set
    protected var resumableFrame = initialFrame

    init {
        require(initialFrame.canResume())
        clock = weatherClock(initialFrame, clockFactory)
    }

    /** Pause logical time while the process is absent, preserving remaining timer delays. */
    fun persistenceFrame(): WeatherFrame = resumableFrame.at(clock.now())

    fun beginBranch(snapshot: WeatherFrame) {
        require(snapshot.canResume())
        branch++
        frame = snapshot
        resumableFrame = snapshot
        clock = weatherClock(snapshot, clockFactory)
    }

    fun searchCheckpoint() = frame.search

    fun weatherCheckpoint(city: City): ExecutorCheckpoint<WeatherContext, WeatherCommand> =
        when (val route = frame.route) {
            is WeatherRoute.Weather -> if (route.checkpoint.snapshot.context.city == city) route.checkpoint else freshWeather(city)
            WeatherRoute.Search -> freshWeather(city)
        }

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

    @OptIn(actron.core.ExperimentalActronApi::class)
    open fun <C : Any, A : Action, CMD : Any, E : Event> createStore(
        machine: Machine<C, A, CMD, E>,
        checkpoint: ExecutorCheckpoint<C, CMD>,
        handler: CommandHandler<CMD, A>,
        scope: CoroutineScope,
        context: CoroutineContext,
        clock: MachineClock,
        describe: (C) -> JsonElement,
        update: (ExecutorCheckpoint<C, CMD>) -> Unit,
    ): MachineStore<C, A, CMD, E> {
        val observer = observe(machine, checkpoint, describe, update)
        return if (checkpoint.snapshot.isStarted) {
            resumeMachineStore(machine, checkpoint, handler, scope, context, clock, listOf(observer))
        } else {
            MachineStore(machine, checkpoint.snapshot.context, handler, scope, coroutineContext = context, observers = listOf(observer), clock = clock)
        }
    }

    open fun <C : Any, A : Action, CMD : Any, E : Event> observe(
        machine: Machine<C, A, CMD, E>,
        checkpoint: ExecutorCheckpoint<C, CMD>,
        describe: (C) -> JsonElement,
        update: (ExecutorCheckpoint<C, CMD>) -> Unit,
    ): DecisionObserver<C, A, CMD, E> {
        val generation = branch
        var current = checkpoint
        return object : DecisionObserver<C, A, CMD, E> {
            private fun apply(step: RecordedStep<C, A, CMD, E>) {
                if (generation != branch) return
                current = current.carriedPast(step)
                update(current)
                frame = frame.at(current.now)
                if (frame.canResume()) resumableFrame = frame
            }

            override fun onCommitted(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                decision: Decision<C, CMD, E>,
            ) = apply(RecordedStep.Committed(machineInput, decision))

            override fun onIgnored(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                reason: IgnoreReason,
            ) = apply(RecordedStep.Ignored(machineInput, reason))

            override fun onFailed(
                input: InputAttribution,
                machineInput: MachineInput<A>,
                failure: FailureDescriptor,
            ) = apply(RecordedStep.Failed(machineInput, failure))
        }
    }

    @Composable
    open fun Render(
        onContinue: (WeatherFrame) -> Unit,
        content: @Composable () -> Unit,
    ) {
        content()
    }
}

/** Optional host UI extension; the production application supplies no extra controls. */
val LocalWeatherActions = staticCompositionLocalOf<@Composable () -> Unit> { {} }

private fun freshWeather(city: City): ExecutorCheckpoint<WeatherContext, WeatherCommand> = ExecutorCheckpoint.initial(CityWeatherMachine.create().initialSnapshot(WeatherContext(city)))

private fun weatherClock(
    snapshot: WeatherFrame,
    clockFactory: () -> MachineClock,
): MachineClock {
    val base = clockFactory()
    val baseStart = base.now()
    val origin = snapshot.search.now
    return object : MachineClock {
        override fun now(): MachineTime = origin + (base.now() - baseStart)

        override suspend fun delayUntil(deadline: MachineTime) = base.delayUntil(baseStart + (deadline - origin))
    }
}
