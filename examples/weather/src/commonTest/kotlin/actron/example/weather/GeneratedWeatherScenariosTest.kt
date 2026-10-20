package actron.example.weather

import actron.example.weather.core.debug.*
import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.example.weather.feature.weather.presentation.WeatherContext
import actron.example.weather.feature.weather.presentation.toUiState
import actron.statechart.machine.*
import actron.statechart.test.*
import actron.timetravel.HistoryExportSettings
import actron.timetravel.test.recording
import kotlinx.serialization.json.Json
import kotlin.test.*

class GeneratedWeatherScenariosTest {
    @Test fun discoversAllSearchTransitionsAndBothLateMoscowDeliveryPaths() {
        val model = GeneratedWeatherScenarios.search()
        val generated = model.generate(maxDepth = 6, maxDecisions = 100_000)
        generated.assertSuccess()
        val coverage = generated.scenarios.map { model.record(it).also { trace -> trace.assertSuccess() }.coverage }.reduce { a, b -> a.merge(b) }
        coverage.assertTransitionsCovered()
        println("Generated City Search: ${generated.exploration.decisions} decisions, ${generated.scenarios.size} scenarios\n${coverage.describe(model.machine.chart)}")
        for (name in listOf("late-moscow-command", "late-moscow-action")) {
            val trace = model.record(generated.witnesses.getValue(name))
            trace.assertSuccess()
            val last = trace.steps.last()
            assertEquals("Казань", last.before.context.query)
            assertIs<RequestState.Loading>(last.before.context.phase)
            assertEquals(last.before, last.after)
            val outcome = assertIs<DecisionOutcome.Ignored>(last.explained.decision.outcome)
            assertEquals(if (name == "late-moscow-command") IgnoreReason.StaleCommand else IgnoreReason.NoTransition, outcome.reason)
            assertTrue(trace.steps.flatMap { it.explained.decision.commands }.any { it.command.query == "Москва" })
            val edit =
                trace.steps.single { step ->
                    val input = step.input
                    input is MachineInput.Dispatch && input.action == CitySearchAction.ChangeQuery("Казань")
                }
            assertIs<RequestState.Loading>(edit.before.context.phase)
            assertIs<RequestState.Debouncing>(edit.after.context.phase)
            assertTrue(edit.before.commands.isNotEmpty())
            assertTrue(edit.after.commands.isEmpty())
            assertTrue(
                edit.explained.decision.cancelledScopes
                    .containsAll(
                        edit.before.commands.values
                            .map { it.scope },
                    ),
            )
        }
        val recording = searchRecording(model.record(generated.witnesses.getValue("kazan-success-after-late-moscow")))
        val imported = WeatherRecording.import(WeatherRecording.codec.encode(recording))
        val screen =
            assertIs<WeatherDisplay.Search>(
                imported.entries
                    .last()
                    .snapshot.screen,
            )
        assertEquals("Казань", screen.state.query)
        assertEquals(
            "Казань",
            assertIs<RequestState.Success<List<CityOption>>>(screen.state.phase)
                .value
                .single()
                .city.name,
        )
        assertTrue(imported.entries.any { it.details.outcome.contains("StaleCommand") && it.details.before == it.details.after })
    }

    @Test fun exploresParallelResponseOrdersErrorsRetriesAndTimeouts() {
        val model = GeneratedWeatherScenarios.weather()
        val generated = model.generate(maxDepth = 5, maxDecisions = 50_000)
        generated.assertSuccess()
        val coverage = generated.scenarios.map { model.record(it).also { trace -> trace.assertSuccess() }.coverage }.reduce { a, b -> a.merge(b) }
        coverage.assertTransitionsCovered()
        assertEquals(21, coverage.transitions.covered.size)
        assertEquals(setOf("forecast-before-current", "current-before-forecast"), generated.witnesses.keys)
        println("Generated City Weather: ${generated.exploration.decisions} decisions, ${generated.scenarios.size} scenarios\n${coverage.describe(model.machine.chart)}")
        val trace = model.record(generated.witnesses.getValue("forecast-before-current"))
        val imported = WeatherRecording.import(WeatherRecording.codec.encode(weatherRecording(trace)))
        val screen =
            assertIs<WeatherDisplay.Weather>(
                imported.entries
                    .last()
                    .snapshot.screen,
            )
        assertIs<RequestState.Success<*>>(screen.state.hourly)
        assertIs<RequestState.Loading>(screen.state.current)
        assertEquals(
            2,
            screen.state
                .toUiState(enabled = false)
                .forecast.size,
        )
    }
}

fun <CMD : Any, E : actron.core.Event> searchRecording(trace: ScenarioTrace<CitySearchState, CitySearchAction, CMD, E>) =
    trace.recording(
        WeatherRecording.codec.schema,
        WeatherRecording.definitions,
        describe = { Json.encodeToJsonElement(CitySearchState.serializer(), it) },
        snapshot = { step, coverage ->
            WeatherRecordingFrame(
                WeatherDisplay.Search(step.after.context),
                step.after.configuration.active
                    .map { it.value },
                coverage.hits.mapKeys { it.key.index },
            )
        },
        settings = HistoryExportSettings("Weather generated tests"),
        machineName = "City Search",
    )

fun <A : actron.core.Action, CMD : Any, E : actron.core.Event> weatherRecording(trace: ScenarioTrace<WeatherContext, A, CMD, E>) =
    trace.recording(
        WeatherRecording.codec.schema,
        WeatherRecording.definitions,
        describe = { Json.encodeToJsonElement(WeatherContext.serializer(), it) },
        snapshot = { step, coverage ->
            WeatherRecordingFrame(
                WeatherDisplay.Weather(step.after.context),
                step.after.configuration.active
                    .map { it.value },
                coverage.hits.mapKeys { it.key.index },
            )
        },
        settings = HistoryExportSettings("Weather generated tests"),
        machineName = "City Weather",
    )
