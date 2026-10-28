package actron.example.weather.feature.weatheradvice.presentation

import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.domain.weatherAdvice
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.commandHandlers

fun weatherAdviceHandler(repository: WeatherRepository): CommandHandler<LoadWeatherAdvice, WeatherAdviceAction> =
    commandHandlers(build = {
        handle<LoadWeatherAdvice>(
            onFailure = { _, error -> WeatherAdviceAction.Failed(message = error.message ?: "Advice request failed") },
            execute = { request ->
                val reading = repository.load(city = request.city)
                WeatherAdviceAction.Loaded(city = reading.city, items = weatherAdvice(reading = reading))
            },
        )
    })
