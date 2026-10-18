package actron.example.weather.feature.cities.presentation

import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.CityOption

data class CitySearchUiState(
    val query: String,
    val phase: RequestState<List<CityOption>>,
    val enabled: Boolean,
)

sealed interface CitySearchIntent {
    data class QueryChanged(
        val query: String,
    ) : CitySearchIntent

    data object Retry : CitySearchIntent

    data class SelectCity(
        val city: City,
    ) : CitySearchIntent
}

fun CitySearchState.toUiState(enabled: Boolean) = CitySearchUiState(query, phase, enabled)
