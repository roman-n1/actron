package actron.example.weather.feature.cities.data

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.domain.CitySearchRepository

object LocalCitySearchRepository : CitySearchRepository {
    // Fixtures for the explicit offline/article modes; never used as initial suggestions.
    override suspend fun search(query: String) =
        demoCities.map(::CityOption).filter {
            it.city.name.contains(query, ignoreCase = true) || it.region.contains(query, ignoreCase = true)
        }
}
