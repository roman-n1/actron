@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.weather

import actron.observability.MachineGroupId
import actron.observability.RecordingSession
import actron.observability.StoreInstanceId
import actron.observability.recordTo
import actron.statechart.recordTo
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.GroupCut
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineStore
import actron.statechart.machine.decisionsOf
import actron.timetravel.GroupBranch
import actron.timetravel.GroupRecorder
import actron.timetravel.GroupReplaySession
import actron.timetravel.compose.BranchInput
import actron.timetravel.compose.GroupReplayControls
import actron.timetravel.compose.GroupTimeTravelControls
import actron.timetravel.compose.InspectorState
import actron.timetravel.inspect.Inspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/** Owns live machines. Recorders exist before a panel opens, and the panel never owns a handler. */
class WeatherRuntime(private val api: WeatherApi, scope: CoroutineScope) {
    val locationId = StoreInstanceId("location")
    val weatherId = StoreInstanceId("weather")
    val adviceId = StoreInstanceId("advice")
    val journal = RecordingSession(scope, group = MachineGroupId("weather-demo"))
    val group = MachineGroup(journal)
    val recorder = GroupRecorder(group)
    val locationMachine = WeatherMachines.location()
    val weatherMachine = WeatherMachines.weather()
    val adviceMachine = WeatherMachines.advice()
    private val counts = MutableStateFlow(0)
    val networkCalls = counts.asStateFlow()

    private val locationMember = group.member<LocationContext, ChooseCity, NoCommand, CityChosen>(locationId)
    private val weatherMember = group.member<WeatherContext, WeatherAction, FetchWeather, WeatherUpdated>(weatherId)
    private val adviceMember = group.member<AdviceContext, AdviceAction, NoCommand, NoEvent>(adviceId)
    val location: MachineStore<LocationContext, ChooseCity, NoCommand, CityChosen> = MachineStore(locationMachine, LocationContext(), CommandHandler<NoCommand, ChooseCity> { _, _ -> error("Location has no commands") }, scope, coroutineContext = scope.coroutineContext,
        observers = listOf(locationMember, journal.decisionsOf(locationId), recorder.member(locationId, locationMachine, LocationContext()))) { recordTo(journal, locationId) }
    val weather: MachineStore<WeatherContext, WeatherAction, FetchWeather, WeatherUpdated> = MachineStore(weatherMachine, WeatherContext(), CommandHandler<FetchWeather, WeatherAction> { command, results ->
        counts.update { it + 1 }
        try { results.result(WeatherAction.Loaded(api.load(command.command.city))) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { results.result(WeatherAction.Unavailable(error.message ?: "Weather request failed")) }
    }, scope, coroutineContext = scope.coroutineContext, observers = listOf(weatherMember, journal.decisionsOf(weatherId), recorder.member(weatherId, weatherMachine, WeatherContext()))) { recordTo(journal, weatherId) }
    val advice: MachineStore<AdviceContext, AdviceAction, NoCommand, NoEvent> = MachineStore(adviceMachine, AdviceContext(), CommandHandler<NoCommand, AdviceAction> { _, _ -> error("Advice has no commands") }, scope, coroutineContext = scope.coroutineContext,
        observers = listOf(adviceMember, journal.decisionsOf(adviceId), recorder.member(adviceId, adviceMachine, AdviceContext()))) { recordTo(journal, adviceId) }

    val branchRoutes = listOf(
        GroupBranch.Route(locationId, weatherId) { event, carry -> if (event is CityChosen) carry(WeatherAction.Select(event.city)) },
        GroupBranch.Route(locationId, adviceId) { event, carry -> if (event is CityChosen) carry(AdviceAction.Select(event.city)) },
        GroupBranch.Route(weatherId, adviceId) { event, carry -> if (event is WeatherUpdated) carry(AdviceAction.Evaluate(event.reading)) },
    )

    init {
        group.route<CityChosen, WeatherAction>(locationId, weatherId) { event, carry -> carry(WeatherAction.Select(event.city)) }
        group.route<CityChosen, AdviceAction>(locationId, adviceId) { event, carry -> carry(AdviceAction.Select(event.city)) }
        group.route<WeatherUpdated, AdviceAction>(weatherId, adviceId) { event, carry -> carry(AdviceAction.Evaluate(event.reading)) }
        locationMember.attach(location)
        weatherMember.attach(weather)
        adviceMember.attach(advice)
    }

    suspend fun start() = withTimeout(5.seconds) {
        weather.start()
        advice.start()
        weather.state.first { it.isStarted }
        advice.state.first { it.isStarted }
        location.start()
        location.state.first { it.isStarted }
        check(group.checkpoint(5.seconds) is GroupCut.Ready) { "Weather machines did not start" }
    }

    fun choose(city: City) = location.dispatch(ChooseCity(city))
    fun refresh() = weather.dispatch(WeatherAction.Refresh)

    data class History(val inspector: InspectorState, val controls: GroupTimeTravelControls)

    /** Capture inside the cut: no member can append between copying the recording and journal. */
    suspend fun freeze(): History {
        val captured = CompletableDeferred<History>()
        val listener = actron.statechart.machine.CutListener {
            val recording = recorder.recording()
            val replay = GroupReplayControls(GroupReplaySession(mapOf(locationId to locationMachine, weatherId to weatherMachine, adviceId to adviceMachine), recording))
            replay.seek(replay.length)
            val inputs = buildList {
                for (city in demoCities) add(BranchInput.Dispatch(locationId, "Choose ${city.name}", ChooseCity(city)))
                add(BranchInput.Dispatch(weatherId, "Refresh", WeatherAction.Refresh))
                for (step in recording.members.getValue(weatherId).steps) {
                    val input = step.input
                    if (input is actron.statechart.machine.MachineInput.CommandResult && input.action is WeatherAction.Loaded) {
                        val loaded = input.action as WeatherAction.Loaded
                        add(BranchInput.Answer(weatherId, "Recorded ${loaded.reading.city.name} · ${loaded.reading.time}", loaded))
                    }
                }
                for (city in demoCities) {
                    add(BranchInput.Answer(weatherId, "${city.name}: strong wind", WeatherAction.Loaded(WeatherReading(city, 18.0, 14.0, 55.0, 3, "Experiment"))))
                    add(BranchInput.Answer(weatherId, "${city.name}: heat", WeatherAction.Loaded(WeatherReading(city, 36.0, 39.0, 5.0, 0, "Experiment"))))
                }
                add(BranchInput.Answer(weatherId, "Connection unavailable", WeatherAction.Unavailable("Scripted connection failure")))
            }.distinct()
            captured.complete(History(InspectorState(Inspector.of(journal, recording.members)), GroupTimeTravelControls(replay, branchRoutes, inputs)))
        }
        group.onCut(listener)
        try {
            check(group.checkpoint(5.seconds) is GroupCut.Ready) { "Could not capture a consistent history" }
            return captured.await()
        } finally { group.removeCutListener(listener) }
    }

    fun close() { location.close(); weather.close(); advice.close() }
}
