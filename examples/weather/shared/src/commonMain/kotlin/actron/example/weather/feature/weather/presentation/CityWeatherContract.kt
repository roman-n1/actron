package actron.example.weather.feature.weather.presentation

import actron.example.weather.core.presentation.WeatherText
import actron.example.weather.feature.weather.domain.ForecastHour
import actron.example.weather.resources.Res
import actron.example.weather.resources.choose_city
import actron.example.weather.resources.choose_weather_city
import actron.example.weather.resources.unknown_temperature

/** Already formatted immutable data: Compose neither interprets weather nor reads live machines. */
data class CityWeatherUiState(
    val city: String = "",
    val temperature: WeatherText = WeatherText(resource = Res.string.unknown_temperature),
    val detail: WeatherText = WeatherText(resource = Res.string.choose_weather_city),
    val updatedAt: String = "",
    val forecast: List<ForecastHour> = emptyList(),
    val forecastStatus: List<WeatherText> = listOf(WeatherText(resource = Res.string.choose_city)),
    val forecastFailed: Boolean = false,
    val enabled: Boolean = false,
    val advice: List<WeatherText> = emptyList(),
    val adviceAction: WeatherAdviceAction = WeatherAdviceAction.Dismiss,
)

enum class WeatherAdviceAction { Dismiss, Retry }

sealed interface CityWeatherIntent {
    data object DismissAdvice : CityWeatherIntent

    data object RetryAdvice : CityWeatherIntent

    data object Back : CityWeatherIntent

    data object Refresh : CityWeatherIntent

    data object RetryForecast : CityWeatherIntent
}
