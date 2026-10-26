package actron.example.weather.feature.weather.presentation

import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.domain.weatherAdvice
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.commandHandlers

/** The same current-weather use case serves two independently owned requests and interpretations. */
fun cityWeatherHandler(
    current: WeatherRepository,
    hourly: HourlyForecastRepository,
): CommandHandler<WeatherCommand, WeatherAction> =
    commandHandlers(build = {
        handle<WeatherCommand.Current>(
            onFailure = { _, error -> WeatherAction.Unavailable(reason = error.message ?: "Weather request failed") },
            execute = { request -> WeatherAction.Loaded(reading = current.load(city = request.city)) },
        )
        handle<WeatherCommand.Hourly>(
            onFailure = { _, error -> WeatherAction.ForecastFailed(message = error.message ?: "Could not load hourly forecast") },
            execute = { request -> WeatherAction.ForecastLoaded(forecast = hourly.load(city = request.city)) },
        )
        handle<WeatherCommand.Advice>(
            onFailure = { _, error -> WeatherAction.AdviceFailed(reason = error.message ?: "Advice request failed") },
            execute = { request ->
                val reading = current.load(city = request.city)
                WeatherAction.AdviceLoaded(city = reading.city, items = weatherAdvice(reading = reading))
            },
        )
    })
