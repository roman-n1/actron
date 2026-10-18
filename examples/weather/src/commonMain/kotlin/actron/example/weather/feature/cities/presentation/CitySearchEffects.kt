package actron.example.weather.feature.cities.presentation

import actron.example.weather.feature.cities.domain.SearchCitiesUseCase
import actron.statechart.machine.CommandHandler
import kotlinx.coroutines.CancellationException

/** Execute the use case outside the machine and return a recordable result action. */
fun citySearchHandler(
    searchCities: SearchCitiesUseCase,
): CommandHandler<SearchCitiesCommand, CitySearchAction> =
    CommandHandler { command, results ->
        val request = command.command
        try {
            val cities = searchCities(request.query)
            results.result(CitySearchAction.Found(request.revision, cities))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            results.result(CitySearchAction.Failed(request.revision, "Could not load cities. Try again."))
        }
    }
