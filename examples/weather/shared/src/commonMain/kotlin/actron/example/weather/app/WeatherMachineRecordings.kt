package actron.example.weather.app

import actron.core.Action
import actron.core.Event
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.example.weather.core.mvi.NoCommand
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherContext
import actron.example.weather.feature.currentweather.presentation.LoadCurrentWeather
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastContext
import actron.example.weather.feature.hourlyforecast.presentation.LoadHourlyForecast
import actron.example.weather.feature.weather.presentation.WeatherScreenAction
import actron.example.weather.feature.weather.presentation.WeatherScreenContext
import actron.example.weather.feature.weather.presentation.WeatherScreenEvent
import actron.example.weather.feature.weatheradvice.presentation.LoadWeatherAdvice
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceContext

class WeatherMachineRecordings(
    val screen: MachineRecording<WeatherScreenContext, WeatherScreenAction, NoCommand, WeatherScreenEvent> = noRecording(),
    val current: MachineRecording<CurrentWeatherContext, CurrentWeatherAction, LoadCurrentWeather, NoEvent> = noRecording(),
    val hourly: MachineRecording<HourlyForecastContext, HourlyForecastAction, LoadHourlyForecast, NoEvent> = noRecording(),
    val advice: MachineRecording<WeatherAdviceContext, WeatherAdviceAction, LoadWeatherAdvice, NoEvent> = noRecording(),
)

private fun <C : Any, A : Action, CMD : Any, E : Event> noRecording(): MachineRecording<C, A, CMD, E> = MachineRecording { _, _, handler -> MachineDiagnostics(handler = handler) }
