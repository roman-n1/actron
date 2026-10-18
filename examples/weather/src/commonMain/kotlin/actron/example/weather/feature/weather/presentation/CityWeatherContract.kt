package actron.example.weather.feature.weather.presentation

import actron.example.weather.feature.weather.domain.ForecastHour

/** Already formatted immutable data: Compose neither interprets weather nor reads live machines. */
data class CityWeatherUiState(
    val city: String = "",
    val temperature: String = "—",
    val detail: String = "Choose a city to see the weather",
    val updatedAt: String = "",
    val forecast: List<ForecastHour> = emptyList(),
    val forecastStatus: String = "Choose a city",
    val forecastFailed: Boolean = false,
    val enabled: Boolean = false,
)

sealed interface CityWeatherIntent {
    data object Back : CityWeatherIntent

    data object Refresh : CityWeatherIntent

    data object RetryForecast : CityWeatherIntent
}
