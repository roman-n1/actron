package actron.example.weather.feature.cities.presentation

import actron.example.weather.core.domain.City
import actron.example.weather.core.presentation.WeatherText
import actron.example.weather.resources.Res
import actron.example.weather.resources.debouncing
import actron.example.weather.resources.enter_city
import actron.example.weather.resources.no_cities
import actron.example.weather.resources.search_failed
import actron.example.weather.resources.searching
import actron.statechart.machine.MachineSnapshot

fun CitySearchState.toUiState(controls: CitySearchControls = CitySearchControls.Interactive): CitySearchUiState =
    CitySearchUiState(
        query = query,
        controls = controls,
        messages =
            when (this) {
                is CitySearchState.QueryChanged, is CitySearchState.Error -> emptyList()
                is CitySearchState.Idle -> listOf(CitySearchMessage(WeatherText(Res.string.enter_city), "city-search-hint"))
                is CitySearchState.Debouncing -> listOf(CitySearchMessage(WeatherText(Res.string.debouncing), "city-search-debounce"))
                is CitySearchState.Loading -> listOf(CitySearchMessage(WeatherText(Res.string.searching), "city-search-loading"))
                is CitySearchState.Success -> if (results.isEmpty()) listOf(CitySearchMessage(WeatherText(Res.string.no_cities), "city-search-empty")) else emptyList()
            },
        errors = if (this is CitySearchState.Error) listOf(WeatherText(Res.string.search_failed)) else emptyList(),
        actions = if (this is CitySearchState.Error) listOf(CitySearchIntent.Retry) else emptyList(),
        results =
            when (this) {
                is CitySearchState.Success -> {
                    results.map { option ->
                        CitySearchResultUiModel(
                            name = option.city.name,
                            details = listOf(option.region).filter(String::isNotBlank),
                            testTag = "city-${option.city.name}",
                            select = option.city.toSearchSelection(),
                        )
                    }
                }

                else -> {
                    emptyList()
                }
            },
    )

/** UI selection data is converted to a domain object before entering the machine. */
fun City.toSearchSelection(): CitySearchIntent.SelectCity = CitySearchIntent.SelectCity(name, latitude, longitude)

internal fun CitySearchIntent.toAction(
    state: CitySearchState,
    dispatch: (CitySearchAction) -> Unit,
) {
    dispatch(
        when (this) {
            is CitySearchIntent.QueryChanged -> CitySearchAction.ChangeQuery(query)
            CitySearchIntent.Retry -> CitySearchAction.ChangeQuery(state.query)
            is CitySearchIntent.SelectCity -> CitySearchAction.SelectCity(City(name, latitude, longitude))
        },
    )
}

internal fun MachineSnapshot<CitySearchState>.toUiState(): CitySearchUiState = context.toUiState(if (isStarted) CitySearchControls.Interactive else CitySearchControls.ReadOnly)
