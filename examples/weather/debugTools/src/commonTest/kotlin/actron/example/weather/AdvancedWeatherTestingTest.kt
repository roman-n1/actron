package actron.example.weather

import actron.example.weather.core.mvi.*
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.presentation.*
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherAction
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherContext
import actron.example.weather.feature.currentweather.presentation.CurrentWeatherMachine
import actron.example.weather.feature.weather.domain.*
import actron.example.weather.feature.weather.presentation.*
import actron.statechart.Trigger
import actron.statechart.machine.*
import actron.statechart.test.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Developer-facing examples: finite data, temporal obligations, real executor schedules and mutant oracles. */
class AdvancedWeatherTestingTest {
    private val moscow = GeneratedWeatherScenarios.moscow
    private val kazan = GeneratedWeatherScenarios.kazan

    private fun reading(city: actron.example.weather.core.domain.City) = WeatherReading(city, 12.0, 10.0, 3.0, 0, "2026-10-20T12:00")

    @Test fun searchLeavesLoadingWithinItsTimeoutIncludingCancellation() {
        val rules =
            TemporalRules<CitySearchState, CitySearchAction, SearchCitiesCommand, NoEvent>().apply {
                respondsWithin(
                    "each-search-finishes-or-is-cancelled",
                    8.seconds,
                    20,
                    triggered = {
                        it.explained.decision.commands
                            .isNotEmpty()
                    },
                    // Each obligation belongs to the generation that started it.
                    satisfied = { trigger, later -> later.after.context.revision != trigger.after.context.revision || later.after.context.phase !is RequestState.Loading },
                )
                always("cancelled-generation-cannot-replace-results") { steps ->
                    val step = steps.last()
                    when (val input = step.input) {
                        is MachineInput.CommandResult -> input.command in step.before.commands || step.before.context == step.after.context
                        else -> true
                    }
                }
            }
        val model = GeneratedWeatherScenarios.search()
        val generated = model.generate(maxDepth = 6, maxDecisions = 100_000)
        generated.assertSuccess()
        val completed = model.record(generated.witnesses.getValue("kazan-success-after-late-moscow"))
        rules.check(completed).assertSatisfied()
        val loading = completed.copy(steps = completed.steps.takeWhile { it.after.context.query != kazan.name })
        assertTrue(rules.check(loading).pending.isNotEmpty())
    }

    @Test fun actualCurrentExecutorExploresResultFailureAndTimeout() =
        runTest(testBody = {
            val report =
                CurrentWeatherMachine.create().exploreSchedules(
                    initial = CurrentWeatherContext(city = kazan),
                    scope = this,
                    maxDepth = 1,
                    maxRuns = 20,
                    setup = { send(action = CurrentWeatherAction.Refresh) },
                    choices = {
                        if (runningCommands.isEmpty()) {
                            emptyList()
                        } else {
                            runningCommands.flatMap { command ->
                                listOf(
                                    ScheduleOperation.Answer(command = command.id, action = CurrentWeatherAction.Loaded(reading = reading(city = kazan))),
                                    ScheduleOperation.Answer(command = command.id, action = CurrentWeatherAction.Failed(message = "offline")),
                                )
                            } + ScheduleOperation.Advance(duration = 8.seconds)
                        }
                    },
                    check = {
                        val state = snapshot.context.state
                        if (state is RequestState.Success) assertEquals(expected = kazan, actual = state.value.city)
                    },
                )
            report.assertSuccess()
            assertEquals(expected = 3, actual = report.completed.size)
        })

    @Test fun actualSearchExecutorExploresAnswerBeforeAndAfterQueryReplacement() =
        runTest {
            val report =
                CitySearchMachine.create().exploreSchedules(
                    CitySearchState(),
                    this,
                    maxDepth = 4,
                    maxRuns = 20,
                    setup = {
                        send(CitySearchAction.ChangeQuery(moscow.name))
                        advanceBy(350.milliseconds)
                    },
                    choices = {
                        val state = snapshot.context
                        buildList {
                            if (state.query == moscow.name) add(ScheduleOperation.Send(CitySearchAction.ChangeQuery(kazan.name)))
                            if (state.phase is RequestState.Debouncing) add(ScheduleOperation.Advance(350.milliseconds))
                            for (command in runningCommands) {
                                val city = if (command.command.query == moscow.name) moscow else kazan
                                add(ScheduleOperation.Answer(command.id, CitySearchAction.Found(command.command.revision, listOf(CityOption(city)))))
                            }
                        }
                    },
                    check = {
                        val state = snapshot.context
                        if (state.query == kazan.name) assertTrue(handler.started.filter { it.command.query == moscow.name }.all { it.id in handler.cancelled })
                        val phase = state.phase
                        if (phase is RequestState.Success) assertTrue(phase.value.all { it.city.name == state.query })
                    },
                )
            report.assertSuccess()
            assertEquals(2, report.completed.size)
        }

    @Test fun weatherTestsDetectRemovalOfCityGuard() =
        runTest {
            val machine = CurrentWeatherMachine.create()
            val loaded = CurrentWeatherAction.Loaded(reading = reading(city = moscow))
            val index = machine.chart.transitions.indexOfFirst { (it.trigger as? Trigger.OnAction)?.matcher?.matches(loaded) == true }
            assertTrue(index >= 0)
            val report =
                machine.testMutations(listOf(TransitionMutation(TransitionId(index), MutationKind.RemoveGuard))) { variant ->
                    val driver = MachineTestDriver(machine = variant, context = CurrentWeatherContext(city = kazan), scope = this)
                    try {
                        driver.start()
                        driver.send(action = CurrentWeatherAction.Refresh)
                        driver.send(loaded)
                        assertIs<RequestState.Loading>(driver.snapshot.context.state, "Another city's answer must be ignored")
                    } finally {
                        driver.close()
                    }
                }
            report.assertAllKilled()
        }

    @Test fun executorExploresRestartDuringDebounceAndDuringHttp() =
        runTest {
            val report =
                CitySearchMachine.create().exploreSchedules(
                    CitySearchState(),
                    this,
                    maxDepth = 4,
                    maxRuns = 40,
                    setup = { send(CitySearchAction.ChangeQuery(kazan.name)) },
                    choices = { path ->
                        buildList {
                            if (snapshot.context.phase is RequestState.Debouncing) add(ScheduleOperation.Advance(350.milliseconds))
                            for (command in runningCommands) add(ScheduleOperation.Answer(command.id, CitySearchAction.Found(command.command.revision, listOf(CityOption(kazan)))))
                            if (snapshot.context.phase !is RequestState.Success && ScheduleOperation.Restart !in path) add(ScheduleOperation.Restart)
                        }
                    },
                    check = { assertEquals(kazan.name, snapshot.context.query) },
                )
            report.assertSuccess()
            assertTrue(report.completed.any { it.first() == ScheduleOperation.Restart })
            assertTrue(report.completed.any { it.take(2) == listOf(ScheduleOperation.Advance(350.milliseconds), ScheduleOperation.Restart) })
        }
}
