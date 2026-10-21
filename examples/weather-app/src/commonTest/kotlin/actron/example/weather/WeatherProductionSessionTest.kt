package actron.example.weather

import actron.core.InputAttribution
import actron.example.weather.core.session.WeatherSavedStateCodec
import actron.example.weather.core.session.WeatherSession
import actron.example.weather.feature.cities.presentation.*
import actron.statechart.machine.*
import kotlin.test.*

class WeatherProductionSessionTest {
    @Test
    fun currentStateUpdatesWithoutExplainingOrSerializingHistory() {
        val session = WeatherSession()
        val machine = CitySearchMachine.create()
        val checkpoint = session.searchCheckpoint()
        val observer = session.observe(machine, checkpoint, { error("Production must not describe history fields") }, session::updateSearch)
        assertFalse(observer is DecisionTraceObserver<*, *, *, *>)
        var snapshot = checkpoint.snapshot

        fun decide(input: MachineInput<CitySearchAction>) {
            val decision = machine.decide(snapshot, input)
            observer.onCommitted(InputAttribution.Unattributed, input, decision)
            snapshot = decision.snapshot
        }
        decide(MachineInput.Start(MachineTime.Zero))
        repeat(1000) { decide(MachineInput.Dispatch(CitySearchAction.ChangeQuery("City $it"), MachineTime.Zero)) }
        assertEquals("City 999", session.frame.search.snapshot.context.query)
        assertTrue(session.frame.coverage.isEmpty())
        val restored = WeatherSavedStateCodec.decode(WeatherSavedStateCodec.encode(session))
        assertEquals(session.frame.search.snapshot, restored.frame.search.snapshot)
    }
}
