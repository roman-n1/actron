package actron.example.weather

import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherContext
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastContext
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastMachine
import actron.example.weather.feature.weather.domain.*
import actron.example.weather.feature.weather.presentation.*
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceContext
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceMachine
import actron.statechart.machine.MachineInput
import actron.statechart.test.*

/**
 * Developer-authored finite data and properties for the pure graph explorer, not a test script.
 * actions/replies supply choices; rule checks every decision; goal requires a concrete path.
 * See src/commonTest/README.md for running, coverage limits and recording export.
 */
object GeneratedWeatherScenarios {
    val moscow = City("Москва", 55.75, 37.62)
    val kazan = City("Казань", 55.79, 49.12)

    fun search() =
        CitySearchMachine.create().scenarios(CitySearchState()) {
            actions { state ->
                // A finite domain/budget for edits, not an asserted sequence.
                if (state.context.revision < 2) listOf("", moscow.name, kazan.name).map(CitySearchAction::ChangeQuery) else emptyList()
            }
            replies(setOf(ReplyMode.Active, ReplyMode.LateCommand, ReplyMode.LateAction)) { command ->
                // Use the emitted command's revision; inventing a current revision would hide stale replies.
                val city = if (command.query == moscow.name) moscow else kazan
                listOf(
                    CitySearchAction.Found(command.revision, listOf(CityOption(city))),
                    CitySearchAction.Failed(command.revision, "offline"),
                )
            }
            rule("query-edit-immediately-cancels-previous-request") { step ->
                val input = step.input
                input !is MachineInput.Dispatch || input.action !is CitySearchAction.ChangeQuery ||
                    step.before.commands.all { (id, command) ->
                        id !in step.after.commands && command.scope in step.explained.decision.cancelledScopes
                    }
            }
            rule("old-search-answer-does-not-change-context") { step ->
                val action =
                    when (val input = step.input) {
                        is MachineInput.Dispatch -> input.action
                        is MachineInput.CommandResult -> input.action
                        else -> return@rule true
                    }
                val old =
                    when (action) {
                        is CitySearchAction.Found -> action.revision != step.before.context.revision
                        is CitySearchAction.Failed -> action.revision != step.before.context.revision
                        else -> false
                    }
                !old || step.after.context == step.before.context
            }
            rule("results-belong-to-current-query") { step ->
                val state = step.after.context
                val phase = state.phase
                phase !is RequestState.Success || phase.value.all { it.city.name == state.query }
            }
            goal("late-moscow-command") { steps ->
                // This goal prevents a vacuous pass when no stale reply was ever exercised.
                steps.last().let { step ->
                    val input = step.input
                    if (input !is MachineInput.CommandResult) {
                        false
                    } else {
                        val action = input.action
                        action is CitySearchAction.Found && action.results.any { it.city == moscow } &&
                            step.before.context.query == kazan.name && step.before.context.phase is RequestState.Loading && input.command !in step.before.commands
                    }
                }
            }
            goal("late-moscow-action") { steps ->
                steps.last().let { step ->
                    val input = step.input
                    if (input !is MachineInput.Dispatch) {
                        false
                    } else {
                        val action = input.action
                        action is CitySearchAction.Found && action.results.any { it.city == moscow } &&
                            step.before.context.query == kazan.name && step.before.context.phase is RequestState.Loading && action.revision != step.before.context.revision
                    }
                }
            }
            goal("kazan-success-after-late-moscow") { steps ->
                val last = steps.last().after.context
                last.query == kazan.name && last.phase is RequestState.Success &&
                    steps.any { step ->
                        val input = step.input
                        if (input !is MachineInput.CommandResult) {
                            false
                        } else {
                            val action = input.action
                            input.command !in step.before.commands && action is CitySearchAction.Found && action.results.any { it.city == moscow } &&
                                step.before.context.query == kazan.name && step.before.context.phase is RequestState.Loading
                        }
                    }
            }
        }

    fun weather() =
        CurrentWeatherMachine.create().scenarios(initial = CurrentWeatherContext(city = kazan), configure = {
            actions(CurrentWeatherAction.Refresh)
            goal(name = "current-success", reached = { steps ->
                steps
                    .last()
                    .after.context.state is RequestState.Success
            })
            replies(values = { command ->
                listOf(
                    CurrentWeatherAction.Loaded(reading = WeatherReading(city = command.city, temperature = 12.0, feelsLike = 10.0, wind = 3.0, code = 0, time = "12:00")),
                    CurrentWeatherAction.Failed(message = "offline"),
                )
            })
        })

    fun hourly() =
        HourlyForecastMachine.create().scenarios(initial = HourlyForecastContext(city = kazan), configure = {
            actions(HourlyForecastAction.Refresh, HourlyForecastAction.Retry)
            replies(values = { command ->
                listOf(
                    HourlyForecastAction.Loaded(forecast = HourlyForecast(city = command.city, hours = listOf(ForecastHour(time = "13:00", temperature = 13.0, wind = 3.0, rainChance = 10)))),
                    HourlyForecastAction.Failed(message = "offline"),
                )
            })
        })

    fun advice() =
        WeatherAdviceMachine.create().scenarios(initial = WeatherAdviceContext(city = kazan), configure = {
            actions(WeatherAdviceAction.Refresh, WeatherAdviceAction.Retry, WeatherAdviceAction.Dismiss)
            replies(values = { command ->
                listOf(
                    WeatherAdviceAction.Loaded(city = command.city, items = emptyList()),
                    WeatherAdviceAction.Loaded(city = command.city, items = listOf(WeatherAdvice.Umbrella)),
                    WeatherAdviceAction.Failed(message = "offline"),
                )
            })
        })
}
