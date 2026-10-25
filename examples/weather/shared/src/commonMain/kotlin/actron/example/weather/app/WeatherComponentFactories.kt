package actron.example.weather.app

import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.decompose.retainedMachine
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.app.session.searchRestoration
import actron.example.weather.app.session.weatherRestoration
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.DefaultCitySearchComponent
import actron.example.weather.feature.cities.presentation.SearchCitiesCommand
import actron.example.weather.feature.cities.presentation.citySearchHandler
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherComponent
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.example.weather.feature.weather.presentation.DefaultCityWeatherComponent
import actron.example.weather.feature.weather.presentation.WeatherAction
import actron.example.weather.feature.weather.presentation.WeatherCommand
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.cityWeatherHandler
import com.arkivanov.decompose.ComponentContext
import kotlin.coroutines.CoroutineContext

/** Application composition connects feature stores to current checkpoints and optional recording. */
class DefaultCitySearchComponentFactory(
    private val repository: CitySearchRepository,
    private val coroutineContext: CoroutineContext,
    private val session: WeatherSession,
    private val settingsAction: @androidx.compose.runtime.Composable () -> Unit = {},
    private val recording: MachineRecording<CitySearchState, CitySearchAction, SearchCitiesCommand, NoEvent> = MachineRecording { _, _, handler -> MachineDiagnostics(handler) },
) : CitySearchComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        endpoint: BridgeEndpoint<Nothing, CitySearchComponent.News>,
    ): CitySearchComponent {
        val store =
            componentContext.retainedMachine(
                key = "city-search",
                machine = CitySearchMachine.create(),
                initial = CitySearchState(),
                handler = citySearchHandler(repository),
                restoration = session.searchRestoration(),
                recording = recording,
                coroutineContext = coroutineContext,
            )
        return DefaultCitySearchComponent(componentContext, endpoint, coroutineContext, store, settingsAction)
    }
}

class DefaultCityWeatherComponentFactory(
    private val repository: WeatherRepository,
    private val hourlyRepository: HourlyForecastRepository,
    private val coroutineContext: CoroutineContext,
    private val session: WeatherSession,
    private val recording: MachineRecording<WeatherContext, WeatherAction, WeatherCommand, NoEvent> = MachineRecording { _, _, handler -> MachineDiagnostics(handler) },
) : CityWeatherComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        city: City,
        endpoint: BridgeEndpoint<Nothing, CityWeatherComponent.News>,
    ): CityWeatherComponent {
        val store =
            componentContext.retainedMachine(
                key = "city-weather",
                machine = CityWeatherMachine.create(),
                initial = WeatherContext(city),
                handler = cityWeatherHandler(repository, hourlyRepository),
                restoration = session.weatherRestoration(city),
                recording = recording,
                coroutineContext = coroutineContext,
                initialActions = listOf(WeatherAction.Refresh),
            )
        return DefaultCityWeatherComponent(componentContext, endpoint, coroutineContext, store)
    }
}
