package actron.example.weather.feature.currentweather.presentation

import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.commandHandlers

fun currentWeatherHandler(repository: WeatherRepository): CommandHandler<LoadCurrentWeather, CurrentWeatherAction> =
    commandHandlers(build = {
        handle<LoadCurrentWeather>(
            onFailure = { _, error -> CurrentWeatherAction.Failed(message = error.message ?: "Weather request failed") },
            execute = { request -> CurrentWeatherAction.Loaded(reading = repository.load(city = request.city)) },
        )
    })
