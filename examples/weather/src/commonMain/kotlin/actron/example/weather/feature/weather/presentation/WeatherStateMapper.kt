package actron.example.weather.feature.weather.presentation

import actron.example.weather.core.mvi.RequestState

fun WeatherContext.toUiState(enabled: Boolean): CityWeatherUiState {
    val data = this
    var temperature = "—"
    var updatedAt = ""
    val detail =
        when (val current = data.current) {
            is RequestState.Success -> {
                val reading = current.value
                temperature = "${kotlin.math.round(reading.temperature).toInt()}°"
                updatedAt =
                    if (reading.receivedAt.isNotEmpty()) {
                        reading.receivedAt
                    } else {
                        reading.time.replace('T', ' ')
                    }
                "Feels like ${kotlin.math.round(reading.feelsLike).toInt()}° · wind ${kotlin.math.round(reading.wind).toInt()} km/h"
            }

            RequestState.Debouncing, RequestState.Loading -> {
                "Loading weather…"
            }

            is RequestState.Error -> {
                current.message
            }

            RequestState.TimedOut -> {
                "The request timed out. Refresh to try again."
            }

            RequestState.Idle -> {
                "Choose a city to see the weather"
            }
        }
    val forecastStatus =
        when (val hourly = data.hourly) {
            is RequestState.Success -> ""
            RequestState.Debouncing, RequestState.Loading -> "Loading forecast…"
            is RequestState.Error -> hourly.message
            RequestState.TimedOut -> "Forecast request timed out"
            RequestState.Idle -> "Choose a city"
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
        forecastFailed = hourly is RequestState.Error || hourly == RequestState.TimedOut,
        enabled = enabled,
    )
}
