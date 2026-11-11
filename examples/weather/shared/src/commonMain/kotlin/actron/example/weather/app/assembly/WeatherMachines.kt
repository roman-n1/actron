package actron.example.weather.app.assembly

import actron.core.Action
import actron.core.Event
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.decompose.session.restorable
import actron.decompose.session.sessionMachine
import actron.decompose.session.trackSessionGroup
import actron.decompose.session.whenSessionReady
import actron.example.weather.core.domain.City
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherState
import actron.example.weather.feature.currentweather.presentation.toRequestState
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastState
import actron.example.weather.feature.hourlyforecast.presentation.toRequestState
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.WeatherScreenAction
import actron.example.weather.feature.weather.presentation.WeatherScreenEvent
import actron.example.weather.feature.weather.presentation.WeatherScreenState
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceState
import actron.example.weather.feature.weatheradvice.presentation.toAdvicePhase
import actron.observability.StoreInstanceId
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineStore
import actron.statechart.machine.ScreenMachine
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.CoroutineContext

/** The retained screen owns four stores and their routes; each child retains its own contract. */
class WeatherMachines(
    private val componentContext: ComponentContext,
    city: City,
    currentRepository: WeatherRepository,
    hourlyRepository: HourlyForecastRepository,
    private val coroutineContext: CoroutineContext,
    recordings: WeatherMachineRecordings = WeatherMachineRecordings(),
    private val clock: MachineClock = MachineClock.monotonic(),
) : InstanceKeeper.Instance {
    private val scope = CoroutineScope(context = coroutineContext + SupervisorJob())
    private val group = MachineGroup().also(componentContext::trackSessionGroup)
    private val closeStores = mutableListOf<() -> Unit>()
    private val screenMember = group.member<WeatherScreenState, WeatherScreenAction, Unit, WeatherScreenEvent>(id = StoreInstanceId(value = "weather-screen"))
    private val currentMember = group.member<CurrentWeatherState, CurrentWeatherAction, Unit, actron.example.weather.core.mvi.NoEvent>(id = StoreInstanceId(value = "weather-current"))
    private val hourlyMember = group.member<HourlyForecastState, HourlyForecastAction, Unit, actron.example.weather.core.mvi.NoEvent>(id = StoreInstanceId(value = "weather-hourly"))
    private val adviceMember = group.member<WeatherAdviceState, WeatherAdviceAction, Unit, actron.example.weather.core.mvi.NoEvent>(id = StoreInstanceId(value = "weather-advice"))
    val current = createStore(CurrentWeatherMachine.create(currentRepository), CurrentWeatherState.Ready(city), currentMember, recordings.current)
    val hourly = createStore(HourlyForecastMachine.create(hourlyRepository), HourlyForecastState.Ready(city), hourlyMember, recordings.hourly)
    val advice = createStore(WeatherAdviceMachine.create(currentRepository), WeatherAdviceState.Ready(city), adviceMember, recordings.advice)
    val screen = createStore(CityWeatherMachine.create(), WeatherScreenState(city), screenMember, recordings.screen)

    val state =
        combine(flow = screen.state, flow2 = current.state, flow3 = hourly.state, flow4 = advice.state, transform = { parent, current, hourly, advice ->
            WeatherContext(city = parent.context.city, current = current.context.toRequestState(), hourly = hourly.context.toRequestState(), advice = advice.context.toAdvicePhase())
        }).stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = WeatherContext(city))

    init {
        group.route(from = screenMember, to = currentMember, map = { event: WeatherScreenEvent, carry ->
            when (event) {
                WeatherScreenEvent.RefreshChildren -> carry(CurrentWeatherAction.Refresh)
                else -> Unit
            }
        })
        group.route(from = screenMember, to = hourlyMember, map = { event: WeatherScreenEvent, carry ->
            when (event) {
                WeatherScreenEvent.RefreshChildren -> carry(HourlyForecastAction.Refresh)
                WeatherScreenEvent.RetryForecastRequested -> carry(HourlyForecastAction.Retry)
                else -> Unit
            }
        })
        group.route(from = screenMember, to = adviceMember, map = { event: WeatherScreenEvent, carry ->
            when (event) {
                WeatherScreenEvent.RefreshChildren -> carry(WeatherAdviceAction.Refresh)
                WeatherScreenEvent.RetryAdviceRequested -> carry(WeatherAdviceAction.Retry)
                WeatherScreenEvent.DismissAdviceRequested -> carry(WeatherAdviceAction.Dismiss)
                else -> Unit
            }
        })
    }

    fun start() =
        componentContext.whenSessionReady {
            current.start()
            hourly.start()
            advice.start()
            screen.start()
        }

    override fun onDestroy() {
        closeStores.asReversed().forEach { close -> close() }
        scope.cancel()
    }

    private inline fun <reified C : Any, reified A : Action, reified E : Event> createStore(
        machine: ScreenMachine<C, A, E>,
        initial: C,
        member: MachineGroup.Member<C, A, Unit, E>,
        recording: MachineRecording<C, A, Unit, E>,
    ): MachineStore<C, A, Unit, E> =
        runCatching {
            val combinedRecording =
                MachineRecording<C, A, Unit, E> { definition, checkpoint, handler ->
                    val setup = recording.prepare(definition, checkpoint, handler)
                    MachineDiagnostics(setup.handler, listOf(member) + setup.observers, setup.configure)
                }
            val store =
                componentContext.sessionMachine(
                    key = "machine:${machine.id.value}",
                    definition = machine.restorable(),
                    initial = initial,
                    recording = combinedRecording,
                    coroutineContext = coroutineContext,
                    clock = clock,
                    start = false,
                )
            closeStores.add { store.close() }
            member.attach(store)
            store
        }.getOrElse { error ->
            onDestroy()
            throw error
        }
}
