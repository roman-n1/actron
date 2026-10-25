package actron.example.weather.app.session

import actron.decompose.MachineRestoration
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.cities.presentation.SearchCitiesCommand
import actron.example.weather.feature.weather.presentation.WeatherAction
import actron.example.weather.feature.weather.presentation.WeatherCommand
import actron.example.weather.feature.weather.presentation.WeatherContext

fun WeatherSession.searchRestoration(): MachineRestoration<CitySearchState, CitySearchAction, SearchCitiesCommand, NoEvent> = MachineRestoration(clock, observers = { machine, checkpoint -> listOf(observe(machine, checkpoint, ::updateSearch)) }) { _, _ -> searchCheckpoint }

fun WeatherSession.weatherRestoration(city: City): MachineRestoration<WeatherContext, WeatherAction, WeatherCommand, NoEvent> = MachineRestoration(clock, observers = { machine, checkpoint -> listOf(observe(machine, checkpoint, ::updateWeather)) }) { _, _ -> weatherCheckpoint(city) }
