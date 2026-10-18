package actron.example.weather.feature.weather.domain

import actron.example.weather.core.domain.City
import kotlinx.serialization.Serializable

@Serializable data class ForecastHour(
    val time: String,
    val temperature: Double,
    val wind: Double,
    val rainChance: Int,
)

@Serializable data class HourlyForecast(
    val city: City,
    val hours: List<ForecastHour>,
)

fun interface HourlyForecastRepository {
    suspend fun load(city: City): HourlyForecast
}
