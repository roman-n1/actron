package actron.example.weather.feature.cities.presentation

import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.core.presentation.WeatherText
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.resources.Res
import actron.example.weather.resources.search_failed

data class CitySearchUiState internal constructor(
    val query: String,
    val phase: RequestState<List<CityOption>>,
    val enabled: Boolean,
    internal val queryReadOnly: Boolean,
    internal val errorMessage: WeatherText,
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

fun CitySearchState.toUiState(enabled: Boolean) =
    CitySearchUiState(
        query = query,
        phase = phase,
        enabled = enabled,
        queryReadOnly = !enabled,
        errorMessage =
            when (val current = phase) {
                is RequestState.Error -> WeatherText(resource = Res.string.search_failed)
                else -> WeatherText(resource = Res.string.search_failed)
            },
    )
