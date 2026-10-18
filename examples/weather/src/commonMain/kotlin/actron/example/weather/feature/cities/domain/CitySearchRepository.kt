package actron.example.weather.feature.cities.domain

import actron.example.weather.core.domain.City
import kotlinx.serialization.Serializable

/** Region and country distinguish places with the same name without changing recorded cities. */
@Serializable
data class CityOption(
    val city: City,
    val region: String = "",
)

fun interface CitySearchRepository {
    suspend fun search(query: String): List<CityOption>
}
