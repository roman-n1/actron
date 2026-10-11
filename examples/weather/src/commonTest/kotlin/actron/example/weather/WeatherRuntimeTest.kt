@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.statechart.machine.MachineInput
import actron.timetravel.compose.BranchInput
import actron.timetravel.compose.GroupTravel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WeatherRuntimeTest {
    @Test
    fun recordedResponses_replayAndBranch_withoutRunningTheApiAgain() = runTest {
        val requests = mutableListOf<City>()
        val runtime = WeatherRuntime(WeatherApi { city -> requests += city; SampleWeatherApi.load(city) }, backgroundScope)
        runtime.start()
        runtime.weather.state.first { it.isActive(WeatherMachines.ready) }
        runtime.advice.state.first { it.context.reports.isNotEmpty() }
        val history = runtime.freeze()
        assertEquals(emptyList(), history.controls.replay.session.verify())
        val recording = history.controls.replay.session.recording
        val result = recording.order.indexOfFirst { step -> step.store == runtime.weatherId && recording.members.getValue(step.store).steps[step.step].input is MachineInput.CommandResult }
        assertTrue(result > 0)
        history.controls.replay.seek(result)
        history.controls.branchHere()
        val branch = (history.controls.travel as GroupTravel.Experiment).branch
        val command = branch.awaiting(runtime.weatherId).single()
        val reading = WeatherReading(demoCities.first(), 18.0, 14.0, 55.0, 3, "Experiment")
        branch.answer(BranchInput.Answer(runtime.weatherId, "Wind", WeatherAction.Loaded(reading)), command.id)
        assertEquals(reading, (branch.snapshot(runtime.weatherId).context as WeatherContext).readings.last())
        assertTrue(branch.snapshot(runtime.adviceId).isActive(actron.statechart.StateId("Windy")))
        history.controls.returnToReplay()
        assertEquals(result, history.controls.replay.position)
        assertTrue(history.controls.replay.snapshot(runtime.weatherId).isActive(WeatherMachines.loading))
        assertEquals(emptyList(), history.controls.replay.session.verify())
        assertEquals(listOf(demoCities.first()), requests)
        runtime.close()
    }

    @Test
    fun changingCity_cancelsTheOldRequest_andRecordsTheNewCityConsistently() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val runtime = WeatherRuntime(WeatherApi { city ->
            if (city == demoCities.first()) {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            SampleWeatherApi.load(city)
        }, backgroundScope)
        runtime.start()
        started.await()
        runtime.choose(demoCities[1])
        val ready = runtime.weather.state.first { it.isActive(WeatherMachines.ready) }
        cancelled.await()
        runtime.advice.state.first { it.context.city == demoCities[1] && it.context.reports.isNotEmpty() }
        assertEquals(demoCities[1], ready.context.readings.last().city)
        assertEquals(2, runtime.networkCalls.value)
        assertEquals(emptyList(), runtime.freeze().controls.replay.session.verify())
        runtime.close()
    }

    @Test
    fun aCapturedPendingRequest_canTimeOutOnTheVirtualClock_withoutTouchingLiveState() = runTest {
        val started = CompletableDeferred<Unit>()
        val runtime = WeatherRuntime(WeatherApi { started.complete(Unit); awaitCancellation() }, backgroundScope)
        runtime.start()
        started.await()
        val history = runtime.freeze()
        history.controls.branchHere()
        val branch = (history.controls.travel as GroupTravel.Experiment).branch
        assertEquals(1, branch.awaiting(runtime.weatherId).size)
        branch.advance(8.seconds)
        assertTrue(branch.snapshot(runtime.weatherId).isActive(WeatherMachines.timedOut))
        assertTrue(runtime.weather.currentState.isActive(WeatherMachines.loading))
        assertEquals(1, runtime.networkCalls.value)
        runtime.close()
        runCurrent()
    }
}
