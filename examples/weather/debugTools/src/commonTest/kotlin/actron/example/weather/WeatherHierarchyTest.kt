package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.example.weather.feature.weather.presentation.WeatherAction
import actron.example.weather.feature.weather.presentation.WeatherAdviceState
import actron.example.weather.feature.weather.presentation.WeatherCommand
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.WeatherScreenState
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.test.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WeatherHierarchyTest {
    @Test
    fun refreshSelectsOneParentTransitionAndRestartsAllThreeChildRequests() {
        val machine = CityWeatherMachine.create()
        val started =
            machine
                .decide(
                    snapshot = machine.initialSnapshot(context = WeatherContext(city = demoCities.first())),
                    input = MachineInput.Start(now = MachineTime.Zero),
                ).snapshot
        assertTrue(actual = started.commands.isEmpty())
        assertEquals(expected = WeatherScreenState.Idle, actual = started.context.screen)
        val active = machine.decide(snapshot = started, input = MachineInput.Dispatch(action = WeatherAction.Refresh, now = MachineTime.Zero)).snapshot
        val refreshed = machine.decide(snapshot = active, input = MachineInput.Dispatch(action = WeatherAction.Refresh, now = MachineTime.Zero))
        assertEquals(expected = 1, actual = refreshed.transitions.size)
        assertEquals(expected = 3, actual = refreshed.commands.size)
        assertEquals(expected = 3, actual = refreshed.timersScheduled.size)
        assertTrue(actual = refreshed.snapshot.context.current is RequestState.Loading)
        assertTrue(actual = refreshed.snapshot.context.hourly is RequestState.Loading)
        assertEquals(expected = WeatherAdviceState.Loading, actual = refreshed.snapshot.context.advice)
        assertTrue(actual = active.commands.keys.none { it in refreshed.snapshot.commands })
        assertTrue(actual = refreshed.cancelledScopes.containsAll(active.commands.values.map { it.scope }))
    }

    @Test
    fun adviceHasItsOwnResultAndStaysDismissedUntilParentRefresh() =
        runTest(testBody = {
            val city = demoCities.first()
            CityWeatherMachine.create().test(scope = this, initial = WeatherContext(city = city), scenario = {
                dispatch(action = WeatherAction.Refresh)
                completeCommand(matching = { it is WeatherCommand.Current }, result = {
                    WeatherAction.Loaded(reading = WeatherReading(city = city, temperature = 15.0, feelsLike = 15.0, wind = 50.0, code = 0, time = "12:00"))
                })
                assertEquals(expected = WeatherAdviceState.Loading, actual = context.advice)
                completeCommand(matching = { it is WeatherCommand.Advice }, result = {
                    WeatherAction.AdviceLoaded(city = city, items = listOf(WeatherAdvice.Umbrella))
                })
                assertEquals(expected = WeatherAdviceState.Visible(items = listOf(WeatherAdvice.Umbrella)), actual = context.advice)
                dispatch(action = WeatherAction.DismissAdvice)
                completeCommand(matching = { it is WeatherCommand.Hourly }, result = {
                    WeatherAction.ForecastLoaded(forecast = HourlyForecast(city = city, hours = emptyList()))
                })
                assertEquals(expected = WeatherAdviceState.Dismissed, actual = context.advice)
                dispatch(action = WeatherAction.RetryForecast)
                assertEquals(expected = WeatherAdviceState.Dismissed, actual = context.advice)
                dispatch(action = WeatherAction.Refresh)
                assertEquals(expected = WeatherAdviceState.Loading, actual = context.advice)
                expectState<RequestState.Loading>(select = { it.current })
                expectState<RequestState.Loading>(select = { it.hourly })
                advanceTimeBy(duration = 8.seconds)
            })
        })

    @Test
    fun dismissDuringLoadingCancelsOnlyAdviceAndRejectsItsLateAnswer() {
        val machine = CityWeatherMachine.create()
        val start = machine.decide(snapshot = machine.initialSnapshot(context = WeatherContext(city = demoCities.first())), input = MachineInput.Start(now = MachineTime.Zero))
        val active = machine.decide(snapshot = start.snapshot, input = MachineInput.Dispatch(action = WeatherAction.Refresh, now = MachineTime.Zero))
        val started = active.snapshot
        val command = active.commands.single { it.command is WeatherCommand.Advice }
        val dismissed = machine.decide(snapshot = started, input = MachineInput.Dispatch(action = WeatherAction.DismissAdvice, now = MachineTime.Zero))
        assertEquals(expected = WeatherAdviceState.Dismissed, actual = dismissed.snapshot.context.advice)
        assertEquals(expected = started.commands.filterKeys { it != command.id }, actual = dismissed.snapshot.commands)
        val late = machine.decide(snapshot = dismissed.snapshot, input = MachineInput.CommandResult(command = command.id, action = WeatherAction.AdviceLoaded(city = started.context.city, items = listOf(WeatherAdvice.StrongWind)), now = MachineTime.Zero))
        assertEquals(expected = dismissed.snapshot, actual = late.snapshot)
    }

    @Test
    fun adviceTimeoutAndLocalRetryPreserveTheOtherRequests() =
        runTest(testBody = {
            CityWeatherMachine.create().test(scope = this, initial = WeatherContext(city = demoCities.first()), scenario = {
                dispatch(action = WeatherAction.Refresh)
                completeCommand(matching = { it is WeatherCommand.Current }, result = { WeatherAction.Unavailable(reason = "offline") })
                completeCommand(matching = { it is WeatherCommand.Hourly }, result = { WeatherAction.ForecastFailed(message = "offline") })
                advanceTimeBy(duration = 8.seconds)
                assertTrue(actual = context.advice is WeatherAdviceState.Error)
                val current = context.current
                val hourly = context.hourly
                dispatch(action = WeatherAction.RetryAdvice)
                assertEquals(expected = WeatherAdviceState.Loading, actual = context.advice)
                assertEquals(expected = current, actual = context.current)
                assertEquals(expected = hourly, actual = context.hourly)
                assertEquals(expected = 1, actual = driver.runningCommands.size)
                advanceTimeBy(duration = 8.seconds)
            })
        })
}
