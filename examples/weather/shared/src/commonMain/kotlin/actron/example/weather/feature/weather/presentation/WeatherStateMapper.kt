package actron.example.weather.feature.weather.presentation

import actron.example.weather.core.mvi.RequestState
import actron.example.weather.core.presentation.WeatherText
import actron.example.weather.resources.Res
import actron.example.weather.resources.choose_city
import actron.example.weather.resources.choose_weather_city
import actron.example.weather.resources.feels_like
import actron.example.weather.resources.forecast_failed
import actron.example.weather.resources.loading_forecast
import actron.example.weather.resources.loading_weather
import actron.example.weather.resources.temperature
import actron.example.weather.resources.unknown_temperature
import actron.example.weather.resources.weather_failed

fun WeatherContext.toUiState(enabled: Boolean): CityWeatherUiState {
    val data = this
    var temperature = WeatherText(resource = Res.string.unknown_temperature)
    var updatedAt = ""
    val detail =
        when (val current = data.current) {
            is RequestState.Success -> {
                val reading = current.value
                temperature = WeatherText(resource = Res.string.temperature, arguments = listOf(kotlin.math.round(x = reading.temperature).toInt()))
                updatedAt =
                    if (reading.receivedAt.isNotEmpty()) {
                        reading.receivedAt
                    } else {
                        reading.time.replace('T', ' ')
                    }
                WeatherText(resource = Res.string.feels_like, arguments = listOf(kotlin.math.round(x = reading.feelsLike).toInt(), kotlin.math.round(x = reading.wind).toInt()))
            }

            RequestState.Debouncing, RequestState.Loading -> {
                WeatherText(resource = Res.string.loading_weather)
            }

            is RequestState.Error -> {
                WeatherText(resource = Res.string.weather_failed)
            }

            RequestState.Idle -> {
                WeatherText(resource = Res.string.choose_weather_city)
            }
        }
    val forecastStatus =
        when (val hourly = data.hourly) {
            is RequestState.Success -> emptyList()
            RequestState.Debouncing, RequestState.Loading -> listOf(WeatherText(resource = Res.string.loading_forecast))
            is RequestState.Error -> listOf(WeatherText(resource = Res.string.forecast_failed))
            RequestState.Idle -> listOf(WeatherText(resource = Res.string.choose_city))
        }
    val hourly = data.hourly
    val forecast = if (hourly is RequestState.Success) hourly.value.hours else emptyList()
    return CityWeatherUiState(
        city = city.name,
        temperature = temperature,
        detail = detail,
        updatedAt = updatedAt,
        forecast = forecast,
        forecastStatus = forecastStatus,
        forecastFailed = hourly is RequestState.Error,
        enabled = enabled,
    )
}
