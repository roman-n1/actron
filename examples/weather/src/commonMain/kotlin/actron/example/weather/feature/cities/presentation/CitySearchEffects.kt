package actron.example.weather.feature.cities.presentation

import actron.example.weather.feature.cities.domain.SearchCitiesUseCase
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.commandHandler

/** Execute the use case outside the machine and return a recordable result action. */
fun citySearchHandler(
    searchCities: SearchCitiesUseCase,
): CommandHandler<SearchCitiesCommand, CitySearchAction> =
    commandHandler(
        onFailure = { request, _ -> CitySearchAction.Failed(request.revision, "Could not load cities. Try again.") },
    ) { request ->
        CitySearchAction.Found(request.revision, searchCities(request.query))
    }
