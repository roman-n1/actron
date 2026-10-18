package actron.example.weather.feature.weather.presentation

import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.statechart.machine.CommandHandler
import kotlinx.coroutines.CancellationException

/** Both commands run independently in their region's activation and cancellation scope. */
fun cityWeatherHandler(
    current: WeatherRepository,
    hourly: HourlyForecastRepository,
): CommandHandler<WeatherCommand, WeatherAction> =
    CommandHandler { command, results ->
        when (val request = command.command) {
            is WeatherCommand.Current -> {
                try {
                    results.result(WeatherAction.Loaded(current.load(request.city)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    results.result(WeatherAction.Unavailable(error.message ?: "Weather request failed"))
                }
            }

            is WeatherCommand.Hourly -> {
                try {
                    results.result(WeatherAction.ForecastLoaded(hourly.load(request.city)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    results.result(WeatherAction.ForecastFailed(error.message ?: "Could not load hourly forecast"))
                }
            }
        }
    }
