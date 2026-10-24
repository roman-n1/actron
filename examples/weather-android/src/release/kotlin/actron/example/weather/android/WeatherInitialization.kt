package actron.example.weather.android

import actron.example.weather.app.DefaultWeatherRootComponent
import actron.example.weather.app.WeatherRootComponent
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import com.arkivanov.decompose.ComponentContext

internal fun createWeatherRoot(
    context: ComponentContext,
    weather: WeatherRepository,
    cities: CitySearchRepository,
    hourly: HourlyForecastRepository,
): WeatherRootComponent = DefaultWeatherRootComponent(context, weather, cities, hourly)
