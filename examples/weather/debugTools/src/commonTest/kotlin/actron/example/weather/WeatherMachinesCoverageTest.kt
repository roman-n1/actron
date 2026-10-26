package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.test.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WeatherMachinesCoverageTest {
    private enum class Phase { Idle, Debouncing, Loading, Success, Error, TimeoutError }

    @Test
    fun searchEveryDeclaredTransitionIsExecuted() =
        runTest {
            val reports = mutableListOf<MachineCoverage>()
            for (phase in Phase.entries) {
                for (query in listOf("", "New query")) {
                    reports +=
                        CitySearchMachine.create().test(this, CitySearchState()) {
                            if (phase != Phase.Idle) dispatch(CitySearchAction.ChangeQuery("London"))
                            if (phase != Phase.Idle && phase != Phase.Debouncing) advanceTimeBy(350.milliseconds)
                            when (phase) {
                                Phase.Success -> completeCommand { CitySearchAction.Found(it.revision, emptyList()) }
                                Phase.Error -> completeCommand { CitySearchAction.Failed(it.revision, "offline") }
                                Phase.TimeoutError -> advanceTimeBy(8.seconds)
                                else -> Unit
                            }
                            dispatch(CitySearchAction.ChangeQuery(query))
                            assertEquals(query, context.query)
                            if (query.isEmpty()) {
                                expectState<RequestState.Idle> { it.phase }
                            } else {
                                expectState<RequestState.Debouncing> { it.phase }
                            }
                            // Close this scenario through a real action, leaving no timers or commands.
                            dispatch(CitySearchAction.ChangeQuery(""))
                        }
                }
            }
            val coverage = reports.reduce { a, b -> a.merge(b) }
            coverage.assertTransitionsCovered()
            assertEquals(14, coverage.transitions.covered.size)
            println("City Search\n${coverage.describe()}")
        }

    @Test
    fun weatherEveryDeclaredTransitionIsExecuted() =
        runTest {
            val city = demoCities.first()
            val reading = WeatherReading(city, 20.0, 19.0, 3.0, 0, "12:00")
            val reports = mutableListOf<MachineCoverage>()
            for (phase in Phase.entries.filter { it != Phase.Debouncing }) {
                for (action in listOf(WeatherAction.Refresh, WeatherAction.RetryForecast)) {
                    reports +=
                        CityWeatherMachine.create().test(this, WeatherContext(city)) {
                            if (phase != Phase.Idle) dispatch(WeatherAction.Refresh)
                            when (phase) {
                                Phase.Success -> {
                                    completeCommand(matching = { it is WeatherCommand.Current }) { WeatherAction.Loaded(reading) }
                                    completeCommand(matching = { it is WeatherCommand.Hourly }) { WeatherAction.ForecastLoaded(HourlyForecast(city, emptyList())) }
                                }

                                Phase.Error -> {
                                    completeCommand(matching = { it is WeatherCommand.Current }) { WeatherAction.Unavailable("offline") }
                                    completeCommand(matching = { it is WeatherCommand.Hourly }) { WeatherAction.ForecastFailed("offline") }
                                }

                                Phase.TimeoutError -> {
                                    advanceTimeBy(8.seconds)
                                }

                                else -> {
                                    Unit
                                }
                            }
                            val currentBefore = context.current
                            dispatch(action)
                            expectState<RequestState.Loading> { it.hourly }
                            if (action == WeatherAction.Refresh) {
                                expectState<RequestState.Loading> { it.current }
                            } else {
                                assertEquals(currentBefore, context.current, "Forecast retry must not reset current weather")
                            }
                            advanceTimeBy(8.seconds)
                            expectState<RequestState.Error> { it.hourly }
                        }
                }
            }
            // Exercise every branch of the distinct advice lifecycle as well.
            for (result in listOf<WeatherAction>(WeatherAction.AdviceLoaded(city = city, items = emptyList()), WeatherAction.AdviceLoaded(city = city, items = listOf(actron.example.weather.feature.weather.domain.WeatherAdvice.Umbrella)), WeatherAction.AdviceFailed(reason = "offline"))) {
                reports +=
                    CityWeatherMachine.create().test(scope = this, initial = WeatherContext(city = city), scenario = {
                        dispatch(action = WeatherAction.Refresh)
                        completeCommand(matching = { it is WeatherCommand.Advice }, result = { result })
                        dispatch(action = WeatherAction.Refresh)
                        completeCommand(matching = { it is WeatherCommand.Advice }, result = { result })
                        dispatch(action = WeatherAction.DismissAdvice)
                        dispatch(action = WeatherAction.Refresh)
                        dispatch(action = WeatherAction.DismissAdvice)
                        dispatch(action = WeatherAction.Refresh)
                        advanceTimeBy(duration = 8.seconds)
                        dispatch(action = WeatherAction.RetryAdvice)
                        advanceTimeBy(duration = 8.seconds)
                    })
            }
            val coverage = reports.reduce { a, b -> a.merge(b) }
            coverage.assertTransitionsCovered()
            assertEquals(32, coverage.transitions.covered.size)
            println("City Weather\n${coverage.describe()}")
        }

    @Test
    fun searchRejectsStaleAnswersAndRestartsDebounceOnEveryEdit() =
        runTest {
            CitySearchMachine.create().test(this, CitySearchState()) {
                dispatch(CitySearchAction.ChangeQuery("L"))
                advanceTimeBy(349.milliseconds)
                dispatch(CitySearchAction.ChangeQuery("Lo"))
                advanceTimeBy(1.milliseconds)
                expectState<RequestState.Debouncing> { it.phase }
                assertTrue(driver.runningCommands.isEmpty())
                advanceTimeBy(349.milliseconds)
                expectState<RequestState.Loading> { it.phase }
                val revision = context.revision
                dispatch(CitySearchAction.Found(revision - 1, emptyList()))
                dispatch(CitySearchAction.Failed(revision - 1, "old failure"))
                expectState<RequestState.Loading> { it.phase }
                completeCommand { CitySearchAction.Found(it.revision, emptyList()) }
                assertEquals("Lo", context.query)
            }
        }

    @Test
    fun weatherRejectsOtherCityResultsAndRefreshCancelsBothOldCommands() =
        runTest {
            val city = demoCities.first()
            val other = demoCities[1]
            CityWeatherMachine.create().test(this, WeatherContext(city)) {
                dispatch(WeatherAction.Refresh)
                val old = driver.runningCommands.map { it.id }
                dispatch(WeatherAction.Loaded(WeatherReading(other, 1.0, 1.0, 1.0, 0, "12:00")))
                dispatch(WeatherAction.ForecastLoaded(HourlyForecast(other, emptyList())))
                expectState<RequestState.Loading> { it.current }
                expectState<RequestState.Loading> { it.hourly }
                dispatch(WeatherAction.Refresh)
                assertTrue(driver.handler.cancelled.containsAll(old))
                assertEquals(3, driver.runningCommands.size)
                assertTrue(driver.runningCommands.none { it.id in old })
                advanceTimeBy(8.seconds)
                expectState<RequestState.Error> { it.current }
                expectState<RequestState.Error> { it.hourly }
            }
        }
}
