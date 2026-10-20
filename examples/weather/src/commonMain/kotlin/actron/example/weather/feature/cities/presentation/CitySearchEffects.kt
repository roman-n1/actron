package actron.example.weather.feature.cities.presentation

import actron.example.weather.feature.cities.domain.SearchCitiesUseCase
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.commandHandler

/**
 * Execute the use case outside the pure decision and return a result through the Store queue.
 * The executor attaches the result to its command identity; commandHandler rethrows cancellation.
 * onFailure maps ordinary failures only. No handler writes screen state directly.
 */
fun citySearchHandler(
    searchCities: SearchCitiesUseCase,
): CommandHandler<SearchCitiesCommand, CitySearchAction> =
    commandHandler(
        onFailure = { request, _ -> CitySearchAction.Failed(request.revision, "Could not load cities. Try again.") },
    ) { request ->
        CitySearchAction.Found(request.revision, searchCities(request.query))
    }
