package actron.example.weather.app.debug

import actron.example.weather.app.DefaultWeatherRootComponent
import actron.example.weather.app.session.retainWeatherSession
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.timetravel.HistoryConfiguration
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

internal fun retainedWeatherHistory(
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
): DefaultWeatherRootComponent = DefaultWeatherRootComponent(componentContext, api, citySearch, hourlyApi, coroutineContext, history)
