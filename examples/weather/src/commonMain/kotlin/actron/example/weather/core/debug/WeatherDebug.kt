package actron.example.weather.core.debug

import actron.example.weather.app.DefaultWeatherRootComponent
import actron.example.weather.core.session.retainWeatherSession
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.cities.presentation.DefaultCitySearchComponentFactory
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherComponent
import actron.example.weather.feature.weather.presentation.DefaultCityWeatherComponentFactory
import actron.timetravel.HistoryConfiguration
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/** Install only from a debug source set. Omit this artifact entirely from release dependencies. */
fun retainedWeatherHistory(
    context: ComponentContext,
    configuration: HistoryConfiguration = HistoryConfiguration(),
): WeatherHistory = retainWeatherSession(context) { frame, branch -> WeatherHistory(capacity = configuration.capacity, initialFrame = frame, initialBranch = branch) }

fun DebugWeatherRootComponent(
    componentContext: ComponentContext,
    api: WeatherRepository,
    citySearch: CitySearchRepository,
    hourlyApi: HourlyForecastRepository,
    coroutineContext: CoroutineContext = Dispatchers.Main,
    configuration: HistoryConfiguration = HistoryConfiguration(),
    history: WeatherHistory = retainedWeatherHistory(componentContext, configuration),
    searchFactory: CitySearchComponent.Factory = DefaultCitySearchComponentFactory(citySearch, coroutineContext, history),
    weatherFactory: CityWeatherComponent.Factory = DefaultCityWeatherComponentFactory(api, hourlyApi, coroutineContext, history),
): DefaultWeatherRootComponent = DefaultWeatherRootComponent(componentContext, api, citySearch, hourlyApi, coroutineContext, history, searchFactory, weatherFactory)
