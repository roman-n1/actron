package actron.example.weather.feature.weather.domain

import actron.example.weather.core.domain.City

fun interface WeatherRepository {
    suspend fun load(city: City): WeatherReading
}
