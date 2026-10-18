package actron.example.weather.feature.cities.data

import actron.example.weather.core.domain.City
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.domain.CitySearchRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OpenMeteoCitySearchRepository(
    private val client: HttpClient,
) : CitySearchRepository {
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    override suspend fun search(query: String): List<CityOption> {
        val response =
            client.get("https://geocoding-api.open-meteo.com/v1/search") {
                parameter("name", query.trim())
                parameter("count", 30)
                parameter("language", "en")
                parameter("format", "json")
            }
        check(response.status.value in 200..299) { "City search returned HTTP ${response.status.value}" }
        return json
            .decodeFromString<Response>(response.bodyAsText())
            .results
            .filter { it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
            .map { CityOption(City(it.name, it.latitude, it.longitude), listOf(it.admin1, it.country).filter(String::isNotBlank).distinct().joinToString(", ")) }
            .distinctBy { it.city }
    }

    @Serializable private data class Response(
        val results: List<Location> = emptyList(),
    )

    @Serializable private data class Location(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val admin1: String = "",
        val country: String = "",
    )
}
