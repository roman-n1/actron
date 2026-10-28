package actron.example.weather

import actron.example.weather.core.mvi.RequestState
import actron.example.weather.feature.cities.presentation.CitySearchAction
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.cities.presentation.CitySearchState
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CitySearchQueryChangedTest {
    @Test
    fun eachEditChoosesFromUpdatedQueryWithinOneDecision() {
        val machine = CitySearchMachine.create()
        var snapshot =
            machine
                .decide(
                    snapshot = machine.initialSnapshot(context = CitySearchState()),
                    input = MachineInput.Start(now = MachineTime.Zero),
                ).snapshot
        val edits =
            listOf(
                " London " to RequestState.Debouncing,
                " \t" to RequestState.Idle,
                "Tokyo" to RequestState.Debouncing,
                "" to RequestState.Idle,
            )
        for ((query, expected) in edits) {
            val decision =
                machine.decide(
                    snapshot = snapshot,
                    input = MachineInput.Dispatch(action = CitySearchAction.ChangeQuery(query = query), now = MachineTime.Zero),
                )
            assertIs<DecisionOutcome.Handled>(value = decision.outcome)
            assertEquals(expected = listOf("QueryChanged", expected.toString()), actual = decision.entered.map(transform = { it.node.value }))
            assertEquals(expected = expected, actual = decision.snapshot.context.phase)
            assertEquals(expected = query, actual = decision.snapshot.context.query)
            assertEquals(expected = snapshot.context.revision + 1, actual = decision.snapshot.context.revision)
            assertEquals(expected = snapshot.revision + 1, actual = decision.snapshot.revision)
            assertEquals(expected = 2, actual = decision.transitions.size)
            assertEquals(expected = if (expected == RequestState.Debouncing) 1 else 0, actual = decision.snapshot.timers.size)
            assertTrue(actual = decision.commands.isEmpty())
            snapshot = decision.snapshot
        }
    }
}
