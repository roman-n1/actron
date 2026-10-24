package actron.example.weather.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class City(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)
