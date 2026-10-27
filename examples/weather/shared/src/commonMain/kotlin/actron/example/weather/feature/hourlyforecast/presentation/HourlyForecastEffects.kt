package actron.example.weather.feature.hourlyforecast.presentation

import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.commandHandlers

fun hourlyForecastHandler(repository: HourlyForecastRepository): CommandHandler<LoadHourlyForecast, HourlyForecastAction> =
    commandHandlers(build = {
        handle<LoadHourlyForecast>(
            onFailure = { _, error -> HourlyForecastAction.Failed(message = error.message ?: "Could not load hourly forecast") },
            execute = { request -> HourlyForecastAction.Loaded(forecast = repository.load(city = request.city)) },
        )
    })
