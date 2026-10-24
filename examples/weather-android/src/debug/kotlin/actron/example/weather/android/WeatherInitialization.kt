package actron.example.weather.android

import actron.example.weather.app.WeatherRootComponent
import actron.example.weather.core.debug.DebugWeatherRootComponent
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.timetravel.HistoryConfiguration
import com.arkivanov.decompose.ComponentContext

internal fun createWeatherRoot(
    context: ComponentContext,
    weather: WeatherRepository,
    cities: CitySearchRepository,
    hourly: HourlyForecastRepository,
): WeatherRootComponent = DebugWeatherRootComponent(context, weather, cities, hourly, configuration = HistoryConfiguration(capacity = 500))
