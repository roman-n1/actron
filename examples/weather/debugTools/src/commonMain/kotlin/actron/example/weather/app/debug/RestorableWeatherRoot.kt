package actron.example.weather.app.debug

import actron.decompose.session.RestorableComponent
import actron.decompose.session.restorable
import actron.example.weather.app.assembly.DefaultCitySearchComponentFactory
import actron.example.weather.app.assembly.DefaultCityWeatherComponentFactory
import actron.example.weather.app.root.WeatherRootComponent
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/** Debug assembly only: Actron owns checkpoint tracking, navigation restoration and replacement. */
fun restorableWeatherRoot(
    context: ComponentContext,
    weather: WeatherRepository,
    cities: CitySearchRepository,
    hourly: HourlyForecastRepository,
    history: WeatherHistory,
    settings: WeatherSettings,
    uiContext: CoroutineContext = Dispatchers.Main,
): RestorableComponent<WeatherRootComponent> {
    val definitions =
        listOf(
            CitySearchMachine.create(cities).restorable(),
            CityWeatherMachine.create().restorable(),
            CurrentWeatherMachine.create(weather).restorable(),
            HourlyForecastMachine.create(hourly).restorable(),
            WeatherAdviceMachine.create(weather).restorable(),
        )
    return RestorableComponent(context, "weather", "1", definitions, uiContext) { child ->
        WeatherRootComponent(
            child,
            uiContext,
            DefaultCitySearchComponentFactory(cities, uiContext, uiContext, { WeatherSettingsButton(settings::open) }, history.recording()),
            DefaultCityWeatherComponentFactory(weather, hourly, uiContext, uiContext, history::weatherRecordings),
        )
    }.also(history::connect)
}

@Composable
fun RestorableComponent<WeatherRootComponent>.Render() {
    val root by component.collectAsState()
    root.Render()
}
