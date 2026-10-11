@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.example.weather

import actron.statechart.StateId
import actron.statechart.machine.MachineInput
import actron.timetravel.compose.BranchInput
import actron.timetravel.compose.GroupTravel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArticleScenarioTest {
    @Test
    fun closingTheSession_cancelsAHeldLoad_andDoesNotStartTheRestOfTheScript() = runTest {
        val api = ArticleWeatherApi()
        val demo = WeatherDemoStore(api, backgroundScope)
        demo.state.first { it.startup == DemoStartup.Ready }
        api.started(0)
        demo.close()
        runCurrent()
        assertEquals(ArticleRequestStatus.Cancelled, api.requests.value.first().status)
        assertTrue(api.requests.value.drop(1).all { it.status == ArticleRequestStatus.Waiting })
        assertEquals(1, demo.runtime.networkCalls.value)
    }

    @Test
    fun fixedStory_recordsCancellationFailureRecovery_andReplaysWithoutLoading() = runTest {
        val api = ArticleWeatherApi()
        val demo = WeatherDemoStore(api, backgroundScope)
        val state = demo.state.first { it.article == ArticleStatus.Completed || it.article == ArticleStatus.Failed }
        assertEquals(ArticleStatus.Completed, state.article, state.status)
        assertEquals(listOf(ArticleRequestStatus.Cancelled, ArticleRequestStatus.Cancelled, ArticleRequestStatus.Answered, ArticleRequestStatus.Failed, ArticleRequestStatus.Answered), api.requests.value.map { it.status })
        assertEquals(5, demo.runtime.networkCalls.value)
        assertTrue(demo.runtime.advice.currentState.isActive(StateId("Windy")))
        val history = (state.playback as DemoPlayback.History).recording
        val replay = history.controls.replay
        assertEquals(emptyList(), replay.session.verify())
        val weatherSteps = replay.session.recording.members.getValue(demo.runtime.weatherId).steps
        val responses = weatherSteps.map { it.input }.filterIsInstance<MachineInput.CommandResult<WeatherAction>>()
        assertEquals(listOf(12.0, 55.0), responses.map { it.action }.filterIsInstance<WeatherAction.Loaded>().map { it.reading.wind })
        assertEquals(1, responses.map { it.action }.filterIsInstance<WeatherAction.Unavailable>().size)
        assertEquals(2, weatherSteps.count { it.input is MachineInput.CommandAbandoned })
        val phases = mutableSetOf<StateId>()
        for (position in 0..replay.length) {
            replay.seek(position)
            phases += replay.snapshot(demo.runtime.weatherId).configuration.active
        }
        assertTrue(phases.containsAll(listOf(WeatherMachines.loading, WeatherMachines.ready, WeatherMachines.failed)))
        val recording = replay.session.recording
        val failure = recording.order.indexOfFirst { step ->
            val input = recording.members.getValue(step.store).steps[step.step].input
            step.store == demo.runtime.weatherId && input is MachineInput.CommandResult && input.action is WeatherAction.Unavailable
        }
        assertTrue(failure >= 0)
        replay.seek(failure)
        history.controls.branchHere()
        val branch = (history.controls.travel as GroupTravel.Experiment).branch
        branch.answer(BranchInput.Answer(demo.runtime.weatherId, "Alternative calm weather", WeatherAction.Loaded(WeatherReading(demoCities[2], 24.0, 22.0, 12.0, 2, "Experiment"))), branch.awaiting(demo.runtime.weatherId).single().id)
        assertTrue(branch.snapshot(demo.runtime.adviceId).isActive(StateId("Comfortable")))
        history.controls.returnToReplay()
        assertEquals(failure, replay.position)
        assertEquals(emptyList(), replay.session.verify())
        demo.dispatch(DemoIntent.ReturnLive)
        runCurrent()
        demo.dispatch(DemoIntent.Refresh)
        demo.dispatch(DemoIntent.RunChaos)
        demo.dispatch(DemoIntent.Choose(demoCities[1]))
        runCurrent()
        assertEquals(5, demo.runtime.networkCalls.value)
        assertEquals(demoCities[2], demo.runtime.location.currentState.context.city)
        demo.close()
    }

    @Test
    fun freshSessions_repeatTheSameWeatherDecisions_andIgnoreInteractiveIntentsDuringRun() = runTest {
        val traces = mutableListOf<List<String>>()
        repeat(2) {
            val demo = WeatherDemoStore(ArticleWeatherApi(), backgroundScope)
            demo.state.first { it.capture == CaptureStatus.Capturing }
            demo.dispatch(DemoIntent.RunArticle)
            demo.dispatch(DemoIntent.Choose(demoCities[3]))
            demo.dispatch(DemoIntent.Refresh)
            demo.dispatch(DemoIntent.RunChaos)
            demo.dispatch(DemoIntent.Capture)
            demo.dispatch(DemoIntent.ReturnLive)
            val state = demo.state.first { it.article == ArticleStatus.Completed || it.article == ArticleStatus.Failed }
            assertEquals(ArticleStatus.Completed, state.article, state.status)
            val replay = (state.playback as DemoPlayback.History).recording.controls.replay
            val trace = mutableListOf<String>()
            for (position in 0..replay.length) {
                replay.seek(position)
                val weather = replay.snapshot(demo.runtime.weatherId)
                val context = weather.context as WeatherContext
                trace += "${weather.revision}:${weather.configuration.active.sortedBy { it.value }}:${context.city.name}:${context.readings}:${context.failures}"
            }
            // Group cuts can interleave independent members. Compare the per-machine decisions,
            // not wall-clock timestamps or duplicate snapshots at another member's input.
            traces += trace.distinct()
            assertEquals(5, demo.runtime.networkCalls.value)
            demo.close()
        }
        assertEquals(traces[0], traces[1])
    }
}
