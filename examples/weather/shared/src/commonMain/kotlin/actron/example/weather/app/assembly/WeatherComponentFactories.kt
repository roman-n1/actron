package actron.example.weather.app.assembly

import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.decompose.session.restorable
import actron.decompose.session.sessionMachine
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.domain.City
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.cities.presentation.CitySearchEvent
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.DefaultCitySearchComponent
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherComponent
import actron.example.weather.feature.weather.presentation.DefaultCityWeatherComponent
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlin.coroutines.CoroutineContext

/** Decompose retains each feature's machines; recording is optional. */
class DefaultCitySearchComponentFactory(
    private val repository: CitySearchRepository,
    private val uiContext: CoroutineContext,
    private val machineContext: CoroutineContext,
    private val settingsAction: @androidx.compose.runtime.Composable () -> Unit = {},
    private val recording: MachineRecording<CitySearchState, CitySearchAction, Unit, CitySearchEvent> = MachineRecording { _, _, handler -> MachineDiagnostics(handler) },
) : CitySearchComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        endpoint: BridgeEndpoint<Nothing, CitySearchComponent.News>,
    ): CitySearchComponent {
        val store =
            componentContext.sessionMachine(
                key = "city-search",
                definition = CitySearchMachine.create(repository).restorable(),
                initial = CitySearchState.Idle(),
                recording = recording,
                coroutineContext = machineContext,
            )
        return DefaultCitySearchComponent(componentContext, endpoint, uiContext, store, settingsAction)
    }
}

class DefaultCityWeatherComponentFactory(
    private val repository: WeatherRepository,
    private val hourlyRepository: HourlyForecastRepository,
    private val uiContext: CoroutineContext,
    private val machineContext: CoroutineContext,
    private val recordings: (City) -> WeatherMachineRecordings = { WeatherMachineRecordings() },
) : CityWeatherComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        city: City,
        endpoint: BridgeEndpoint<Nothing, CityWeatherComponent.News>,
    ): CityWeatherComponent {
        val machines =
            componentContext.instanceKeeper.getOrCreate(key = "city-weather") {
                WeatherMachines(
                    componentContext = componentContext,
                    city = city,
                    currentRepository = repository,
                    hourlyRepository = hourlyRepository,
                    coroutineContext = machineContext,
                    recordings = recordings(city),
                ).also { it.start() }
            }
        return DefaultCityWeatherComponent(componentContext = componentContext, endpoint = endpoint, coroutineContext = uiContext, machines = machines)
    }
}
