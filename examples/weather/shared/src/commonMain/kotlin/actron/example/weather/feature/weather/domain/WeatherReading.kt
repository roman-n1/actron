package actron.example.weather.feature.weather.domain

import actron.example.weather.core.domain.City
import kotlinx.serialization.Serializable

@Serializable
data class WeatherReading(
    val city: City,
    val temperature: Double,
    val feelsLike: Double,
    val wind: Double,
    val code: Int,
    val time: String,
    val receivedAt: String = "",
)
