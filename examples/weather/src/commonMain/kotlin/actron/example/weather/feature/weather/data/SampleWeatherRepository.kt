package actron.example.weather.feature.weather.data

import actron.example.weather.core.domain.City
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.domain.WeatherRepository

/** Explicit offline scenario, also used by tests; it never pretends to be a live API response. */
object SampleWeatherRepository : WeatherRepository {
    override suspend fun load(city: City): WeatherReading {
        // Latency belongs to the command handler, never to the pure machine or its replay.
        kotlinx.coroutines.delay(1_200)
        val temperature =
            when (city.name) {
                "Lisbon" -> 18.0
                "Tokyo" -> 24.0
                "Reykjavik" -> 6.0
                else -> 7.0
            }
        return WeatherReading(city, temperature, temperature - 2, 12.0, 2, "Sample data")
    }
}
