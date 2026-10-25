package actron.example.weather.app.debug

import actron.example.weather.app.DefaultCitySearchComponentFactory
import actron.example.weather.app.DefaultCityWeatherComponentFactory
import actron.example.weather.app.DefaultWeatherRootComponent
import actron.example.weather.app.WeatherRootComponent
import actron.example.weather.app.session.retainedWeatherSession
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.timetravel.HistoryConfiguration
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

internal fun retainedWeatherHistory(
    context: ComponentContext,
    configuration: HistoryConfiguration = HistoryConfiguration(),
): WeatherHistory =
    context.instanceKeeper.getOrCreate(key = "weather-debug-history") {
        WeatherHistory(capacity = configuration.capacity, session = retainedWeatherSession(context))
    }

/** Optional debug shell around the ordinary application root. */
class DebugWeatherRootComponent(
    componentContext: ComponentContext,
    api: WeatherRepository,
    citySearch: CitySearchRepository,
    hourlyApi: HourlyForecastRepository,
    coroutineContext: CoroutineContext = Dispatchers.Main,
    configuration: HistoryConfiguration = HistoryConfiguration(),
    history: WeatherHistory = retainedWeatherHistory(componentContext, configuration),
) : WeatherRootComponent {
    private val history = history
    private val settings = WeatherSettings()
    private val root =
        DefaultWeatherRootComponent(
            componentContext,
            api,
            citySearch,
            hourlyApi,
            coroutineContext,
            session = history.session,
            searchFactory = DefaultCitySearchComponentFactory(citySearch, coroutineContext, history.session, { WeatherSettingsButton(settings::open) }, history.recording()),
            weatherFactory = DefaultCityWeatherComponentFactory(api, hourlyApi, coroutineContext, history.session, history.recording()),
        )

    override fun getChildStack() = root.getChildStack()

    fun continueFrom(frame: WeatherHistoryFrame) = root.continueFrom(history.restoreDiagnostics(frame))

    @Composable
    override fun Render() {
        WeatherDebugLayout(history, ::continueFrom, settings = settings) { root.Render() }
    }
}
