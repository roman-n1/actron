package actron.example.weather.feature.weather.data

import actron.example.weather.core.domain.City
import actron.example.weather.feature.weather.domain.ForecastHour
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.statechart.*
import actron.statechart.machine.*
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OpenMeteoHourlyRepository(
    private val client: HttpClient,
) : HourlyForecastRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(city: City): HourlyForecast {
        val response =
            client.get("https://api.open-meteo.com/v1/forecast") {
                parameter("latitude", city.latitude)
                parameter("longitude", city.longitude)
                parameter("hourly", "temperature_2m,wind_speed_10m,precipitation_probability")
                parameter("forecast_hours", 6)
                parameter("timezone", "auto")
                parameter("temperature_unit", "celsius")
                parameter("wind_speed_unit", "kmh")
            }
        check(response.status.value in 200..299) { "Could not load hourly forecast (HTTP ${response.status.value})" }
        val data = json.decodeFromString<Response>(response.bodyAsText()).hourly
        require(data.time.isNotEmpty() && data.time.size == data.temperature.size && data.time.size == data.wind.size && data.time.size == data.rain.size) { "Incomplete hourly forecast" }
        val hours =
            data.time.indices.take(6).map { index ->
                require(data.temperature[index].isFinite() && data.wind[index].isFinite() && data.wind[index] >= 0 && data.rain[index] in 0..100) { "Invalid hourly forecast" }
                ForecastHour(data.time[index], data.temperature[index], data.wind[index], data.rain[index])
            }
        return HourlyForecast(city, hours)
    }

    @Serializable private data class Response(
        val hourly: Hours,
    )

    @Serializable private data class Hours(
        val time: List<String>,
        @SerialName("temperature_2m") val temperature: List<Double>,
        @SerialName("wind_speed_10m") val wind: List<Double>,
        @SerialName("precipitation_probability") val rain: List<Int>,
    )
}
