package actron.example.weather

import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.domain.*
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.machine.MachineInput
import actron.statechart.test.*

/** The developer supplies data and properties, never an ordered list of test steps. */
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
        CityWeatherMachine.create().scenarios(WeatherContext(kazan)) {
            actions(WeatherAction.Refresh, WeatherAction.RetryForecast)
            replies { command ->
                when (command) {
                    is WeatherCommand.Current -> {
                        listOf(
                            WeatherAction.Loaded(WeatherReading(command.city, 12.0, 10.0, 3.0, 0, "2026-10-20T12:00")),
                            WeatherAction.Unavailable("offline"),
                        )
                    }

                    is WeatherCommand.Hourly -> {
                        listOf(
                            WeatherAction.ForecastLoaded(
                                HourlyForecast(
                                    command.city,
                                    listOf(
                                        ForecastHour("2026-10-20T13:00", 13.0, 3.0, 10),
                                        ForecastHour("2026-10-20T14:00", 14.0, 4.0, 20),
                                    ),
                                ),
                            ),
                            WeatherAction.ForecastFailed("offline"),
                        )
                    }
                }
            }
            rule("forecast-result-preserves-current-weather") { step ->
                val input = step.input
                val forecast =
                    input is MachineInput.CommandResult &&
                        (input.action is WeatherAction.ForecastLoaded || input.action is WeatherAction.ForecastFailed)
                !forecast || step.before.context.current == step.after.context.current
            }
            rule("forecast-retry-preserves-current-weather") { step ->
                val input = step.input
                input !is MachineInput.Dispatch || input.action != WeatherAction.RetryForecast ||
                    step.before.context.current == step.after.context.current
            }
            goal("forecast-before-current") { steps ->
                val state = steps.last().after.context
                state.hourly is RequestState.Success && state.current is RequestState.Loading
            }
            goal("current-before-forecast") { steps ->
                val state = steps.last().after.context
                state.current is RequestState.Success && state.hourly is RequestState.Loading
            }
        }
}
