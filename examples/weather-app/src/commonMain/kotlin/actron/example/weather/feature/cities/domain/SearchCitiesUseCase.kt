package actron.example.weather.feature.cities.domain

/** Search operation, independent of UI state, machine commands and the HTTP implementation. */
class SearchCitiesUseCase(
    private val repository: CitySearchRepository,
) {
    suspend operator fun invoke(query: String): List<CityOption> = repository.search(query)
}
