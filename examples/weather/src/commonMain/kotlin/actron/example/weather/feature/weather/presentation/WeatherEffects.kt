package actron.example.weather.feature.weather.presentation

import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.commandHandlers

/** Both commands run independently in their region's activation and cancellation scope. */
fun cityWeatherHandler(
    current: WeatherRepository,
    hourly: HourlyForecastRepository,
): CommandHandler<WeatherCommand, WeatherAction> =
    commandHandlers {
        // Typed routing replaces command casts; the executor gives each region its own lifetime.
        handle<WeatherCommand.Current>(
            onFailure = { _, error -> WeatherAction.Unavailable(error.message ?: "Weather request failed") },
        ) { request -> WeatherAction.Loaded(current.load(request.city)) }
        handle<WeatherCommand.Hourly>(
            onFailure = { _, error -> WeatherAction.ForecastFailed(error.message ?: "Could not load hourly forecast") },
        ) { request -> WeatherAction.ForecastLoaded(hourly.load(request.city)) }
    }
