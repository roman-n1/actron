package actron.example.weather.android

import actron.example.weather.app.debug.Render
import actron.example.weather.app.debug.WeatherDebugLayout
import actron.example.weather.app.debug.WeatherSettings
import actron.example.weather.app.debug.restorableWeatherRoot
import actron.example.weather.app.debug.retainedWeatherHistory
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers

@Composable
internal fun WeatherContent(
    context: ComponentContext,
    weather: WeatherRepository,
    cities: CitySearchRepository,
    hourly: HourlyForecastRepository,
) {
    val history = remember(context) { retainedWeatherHistory(context) }
    val settings = remember(context) { WeatherSettings() }
    val root =
        remember(context, weather, cities, hourly) {
            val uiContext = Dispatchers.Main
            restorableWeatherRoot(context = context, weather = weather, cities = cities, hourly = hourly, history = history, settings = settings, uiContext = uiContext)
        }
    WeatherDebugLayout(
        history = history,
        settings = settings,
    ) { root.Render() }
}
