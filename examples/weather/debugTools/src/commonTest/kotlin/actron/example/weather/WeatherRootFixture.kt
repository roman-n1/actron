package actron.example.weather

import actron.example.weather.app.assembly.DefaultCitySearchComponentFactory
import actron.example.weather.app.assembly.DefaultCityWeatherComponentFactory
import actron.example.weather.app.debug.WeatherHistory
import actron.example.weather.app.debug.WeatherSettings
import actron.example.weather.app.debug.WeatherSettingsButton
import actron.example.weather.app.debug.recording
import actron.example.weather.app.debug.retainedWeatherHistory
import actron.example.weather.app.debug.weatherRecordings
import actron.example.weather.app.root.WeatherRootComponent
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.timetravel.HistoryConfiguration
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/** Test fixture for exercising the root with recording enabled. */
internal fun weatherRootFixture(
    componentContext: ComponentContext,
    api: WeatherRepository,
    citySearch: CitySearchRepository,
    hourlyApi: HourlyForecastRepository,
    uiContext: CoroutineContext = Dispatchers.Main,
    machineContext: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    settings: WeatherSettings = WeatherSettings(),
    configuration: HistoryConfiguration = HistoryConfiguration(),
    history: WeatherHistory = retainedWeatherHistory(componentContext, configuration),
): WeatherRootComponent =
    WeatherRootComponent(
        componentContext,
        uiContext,
        searchFactory =
            DefaultCitySearchComponentFactory(
                repository = citySearch,
                uiContext = uiContext,
                machineContext = machineContext,
                settingsAction = { WeatherSettingsButton(settings::open) },
                recording = history.recording(),
            ),
        weatherFactory =
            DefaultCityWeatherComponentFactory(
                repository = api,
                hourlyRepository = hourlyApi,
                uiContext = uiContext,
                machineContext = machineContext,
                recordings = history::weatherRecordings,
            ),
    )
