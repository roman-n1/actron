package actron.example.weather.feature.weather.data

import actron.example.weather.core.domain.City
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.domain.WeatherRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The demo uses public model-based current conditions; no key or account is required. */
class OpenMeteoWeatherRepository(
    private val client: HttpClient,
) : WeatherRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(city: City): WeatherReading {
        val response =
            client.get("https://api.open-meteo.com/v1/forecast") {
                parameter("latitude", city.latitude)
                parameter("longitude", city.longitude)
                parameter("current", "temperature_2m,apparent_temperature,wind_speed_10m,weather_code")
                parameter("timezone", "auto")
                parameter("forecast_days", 1)
                parameter("temperature_unit", "celsius")
                parameter("wind_speed_unit", "kmh")
            }
        check(response.status.value in 200..299) { "Open-Meteo returned HTTP ${response.status.value}" }
        val current = json.decodeFromString<Response>(response.bodyAsText()).current
        require(current.temperature.isFinite() && current.feelsLike.isFinite() && current.wind.isFinite() && current.wind >= 0) { "Open-Meteo returned invalid weather measurements" }
        return WeatherReading(
            city,
            current.temperature,
            current.feelsLike,
            current.wind,
            current.code,
            current.time,
            kotlin.time.Clock.System
                .now()
                .toString(),
        )
    }

    @Serializable private data class Response(
        val current: Current,
    )

    @Serializable private data class Current(
        @SerialName("temperature_2m") val temperature: Double,
        @SerialName("apparent_temperature") val feelsLike: Double,
        @SerialName("wind_speed_10m") val wind: Double,
        @SerialName("weather_code") val code: Int,
        val time: String,
    )
}
