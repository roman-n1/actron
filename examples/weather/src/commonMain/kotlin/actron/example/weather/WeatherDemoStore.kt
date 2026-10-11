@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.weather

import actron.core.Action
import actron.core.State
import actron.core.Store
import actron.timetravel.compose.GroupTravel
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class WeatherPage { Cities, Weather }
enum class DemoStartup { Starting, Ready, Failed }
enum class CaptureStatus { Available, Capturing }
enum class ArticleStatus { Disabled, Running, Completed, Failed }

sealed interface DemoPlayback {
    data object Live : DemoPlayback
    data class History(val recording: WeatherRuntime.History) : DemoPlayback
}

/** Already formatted immutable data: Compose neither interprets weather nor reads live machines. */
data class WeatherView(
    val city: String = demoCities.first().name,
    val phase: String = "Idle",
    val temperature: String = "—",
    val detail: String = "Choose a city to see the weather",
    val advice: String = "Waiting for the weather result",
    val revisions: String = "Location · 0   Weather · 0   Advice · 0",
    val calls: Int = 0,
    val mode: String = "Live",
)

data class WeatherDemoState(
    val view: WeatherView = WeatherView(),
    val playback: DemoPlayback = DemoPlayback.Live,
    val startup: DemoStartup = DemoStartup.Starting,
    val capture: CaptureStatus = CaptureStatus.Available,
    val page: WeatherPage = WeatherPage.Cities,
    val placement: PanelPlacement = PanelPlacement.Bottom,
    val status: String = "Starting three machines…",
    val article: ArticleStatus = ArticleStatus.Disabled,
    val articleStage: ArticleStage = ArticleStage.FirstCity,
    val articleRequests: List<ArticleRequest> = emptyList(),
    val articleCheckpoints: List<ArticleCheckpoint> = emptyList(),
) : State

sealed interface DemoIntent : Action {
    data class Choose(val city: City) : DemoIntent
    data object Refresh : DemoIntent
    data object Capture : DemoIntent
    data object ReturnLive : DemoIntent
    data object ShowCities : DemoIntent
    data object RunChaos : DemoIntent
    data class PlacePanel(val placement: PanelPlacement) : DemoIntent
    data object Started : DemoIntent
    data class Failed(val message: String) : DemoIntent
    data class Captured(val history: WeatherRuntime.History) : DemoIntent
    data class LiveUpdated(val view: WeatherView) : DemoIntent
    data class PreviewUpdated(val history: WeatherRuntime.History, val view: WeatherView) : DemoIntent
    data object RunArticle : DemoIntent
    data class ArticleProgress(val stage: ArticleStage) : DemoIntent
    data class ArticleRequests(val requests: List<ArticleRequest>) : DemoIntent
    data class ArticleSeek(val checkpoint: ArticleCheckpoint) : DemoIntent
}

/** A real Actron MVI Store: serialized intents, pure reducer, lifecycle-owned effects. */
class WeatherDemoStore(api: WeatherApi, parentScope: CoroutineScope) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    val runtime = WeatherRuntime(api, scope)
    private val previewJobs = mutableListOf<Job>()
    val store: Store<WeatherDemoState, DemoIntent, NoEvent> = Store(WeatherDemoState(article = if (api is ArticleWeatherApi) ArticleStatus.Running else ArticleStatus.Disabled), context = scope.coroutineContext) {
        state<WeatherDemoState> {
            enter {
                if (api is ArticleWeatherApi) subscribe {
                    api.requests.collect { store.dispatch(DemoIntent.ArticleRequests(it)) }
                }
                subscribe {
                    combine(runtime.location.state, runtime.weather.state, runtime.advice.state, runtime.networkCalls) { location, weather, advice, calls ->
                        weatherView(location, weather, advice, calls, "Live")
                    }.collect { store.dispatch(DemoIntent.LiveUpdated(it)) }
                }
                launch {
                    try {
                        runtime.start()
                        store.dispatch(DemoIntent.Started)
                        if (api is ArticleWeatherApi) store.dispatch(DemoIntent.RunArticle)
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { store.dispatch(DemoIntent.Failed(error.message ?: "Could not start weather machines")) }
                }
            }
            action<DemoIntent> {
                val previous = state
                val intent = action
                nextState { reduceWeatherDemo(previous, intent) }
                when (intent) {
                    is DemoIntent.Choose -> if (interactive(previous)) runtime.choose(intent.city)
                    DemoIntent.Refresh -> if (interactive(previous)) runtime.refresh()
                    DemoIntent.Capture -> if (previous.article != ArticleStatus.Running && previous.startup == DemoStartup.Ready && previous.capture == CaptureStatus.Available) launch {
                        try { store.dispatch(DemoIntent.Captured(runtime.freeze())) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { store.dispatch(DemoIntent.Failed(error.message ?: "Could not capture history")) }
                    }
                    is DemoIntent.Captured -> if (previous.capture == CaptureStatus.Capturing) { cancelPreview(); observePreview(intent.history) }
                    DemoIntent.ReturnLive -> {
                        if (previous.article != ArticleStatus.Running) {
                            cancelPreview()
                            store.dispatch(DemoIntent.LiveUpdated(weatherView(runtime.location.currentState, runtime.weather.currentState, runtime.advice.currentState, runtime.networkCalls.value, "Live")))
                        }
                    }
                    DemoIntent.RunChaos -> if (interactive(previous)) launch {
                        for (city in demoCities.take(3)) { runtime.choose(city); delay(75) }
                    }
                    DemoIntent.RunArticle -> if (api is ArticleWeatherApi && previous.article == ArticleStatus.Running && previous.startup == DemoStartup.Ready && previous.capture == CaptureStatus.Available) launch {
                        try {
                            val history = withTimeout(20_000) { runArticleScenario(api, runtime) { store.dispatch(DemoIntent.ArticleProgress(it)) } }
                            store.dispatch(DemoIntent.Captured(history))
                        } catch (timeout: TimeoutCancellationException) { store.dispatch(DemoIntent.Failed("Article scenario timed out; start a fresh session"))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { store.dispatch(DemoIntent.Failed(error.message ?: "Article scenario failed")) }
                    }
                    is DemoIntent.ArticleSeek -> {
                        val playback = previous.playback
                        if (previous.article == ArticleStatus.Completed && playback is DemoPlayback.History && intent.checkpoint in previous.articleCheckpoints) {
                            playback.recording.controls.returnToReplay()
                            playback.recording.controls.replay.seek(intent.checkpoint.position)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
    val state get() = store.state
    fun dispatch(intent: DemoIntent) = store.dispatch(intent)
    fun close() { cancelPreview(); store.close(); runtime.close(); scope.cancel() }

    private fun cancelPreview() { previewJobs.forEach { it.cancel() }; previewJobs.clear() }
    private fun observePreview(history: WeatherRuntime.History) {
        previewJobs += scope.launch {
            snapshotFlow {
                val travel = history.controls.travel
                fun snapshot(id: actron.observability.StoreInstanceId) = if (travel is GroupTravel.Experiment) travel.branch.snapshot(id) else history.controls.replay.snapshot(id)
                weatherView(snapshot(runtime.locationId), snapshot(runtime.weatherId), snapshot(runtime.adviceId), runtime.networkCalls.value, if (travel is GroupTravel.Experiment) "Experiment" else "Recorded screen")
            }.collect { store.dispatch(DemoIntent.PreviewUpdated(history, it)) }
        }
    }
}

/** Reducer has no IO, delays, mutable state or clock reads. Stale preview results are ignored. */
fun reduceWeatherDemo(state: WeatherDemoState, intent: DemoIntent): WeatherDemoState = when (intent) {
    is DemoIntent.Choose -> if (interactive(state)) state.copy(page = WeatherPage.Weather) else state
    DemoIntent.Refresh -> state
    DemoIntent.Capture -> if (state.article != ArticleStatus.Running && state.startup == DemoStartup.Ready && state.capture == CaptureStatus.Available) state.copy(capture = CaptureStatus.Capturing) else state
    DemoIntent.ReturnLive -> if (state.article == ArticleStatus.Running) state else state.copy(playback = DemoPlayback.Live, capture = CaptureStatus.Available, status = "Back to live weather")
    DemoIntent.ShowCities -> if (state.article == ArticleStatus.Disabled) state.copy(page = WeatherPage.Cities) else state
    DemoIntent.RunChaos -> if (interactive(state)) state.copy(page = WeatherPage.Weather, status = "Rapid city changes: previous loads cancel; the last choice wins") else state
    is DemoIntent.PlacePanel -> state.copy(placement = intent.placement)
    DemoIntent.Started -> state.copy(startup = DemoStartup.Ready, status = "Choose a city, then capture its history")
    is DemoIntent.Failed -> state.copy(startup = if (state.startup == DemoStartup.Starting) DemoStartup.Failed else state.startup, capture = CaptureStatus.Available, status = intent.message, article = if (state.article == ArticleStatus.Running) ArticleStatus.Failed else state.article)
    is DemoIntent.Captured -> if (state.capture == CaptureStatus.Capturing) state.copy(playback = DemoPlayback.History(intent.history), capture = CaptureStatus.Available, page = WeatherPage.Weather,
        article = if (state.article == ArticleStatus.Running) ArticleStatus.Completed else state.article,
        articleCheckpoints = if (state.article == ArticleStatus.Running) articleCheckpoints(intent.history) else state.articleCheckpoints,
        status = if (state.article == ArticleStatus.Running) "Article history ready: five loads, two cancellations, one failure. Replay makes no new requests." else "History captured: replay is offline") else state
    is DemoIntent.LiveUpdated -> if (state.playback is DemoPlayback.Live) state.copy(view = intent.view) else state
    is DemoIntent.PreviewUpdated -> if (state.playback == DemoPlayback.History(intent.history)) state.copy(view = intent.view) else state
    DemoIntent.RunArticle -> if (state.article == ArticleStatus.Running && state.startup == DemoStartup.Ready && state.capture == CaptureStatus.Available) state.copy(page = WeatherPage.Weather, capture = CaptureStatus.Capturing, status = state.articleStage.description) else state
    is DemoIntent.ArticleProgress -> if (state.article == ArticleStatus.Running) state.copy(articleStage = intent.stage, status = intent.stage.description) else state
    is DemoIntent.ArticleRequests -> if (state.article != ArticleStatus.Disabled) state.copy(articleRequests = intent.requests) else state
    is DemoIntent.ArticleSeek -> state
}

private fun interactive(state: WeatherDemoState) = state.article == ArticleStatus.Disabled && state.playback is DemoPlayback.Live && state.startup == DemoStartup.Ready

private fun weatherView(location: actron.statechart.machine.MachineSnapshot<*>, weather: actron.statechart.machine.MachineSnapshot<*>, advice: actron.statechart.machine.MachineSnapshot<*>, calls: Int, mode: String): WeatherView {
    val city = (location.context as LocationContext).city
    val data = weather.context as WeatherContext
    val reports = (advice.context as AdviceContext).reports
    val phase = weather.configuration.active.map { it.value }.filter { it != "Weather" }.sorted().joinToString(", ").ifEmpty { "Starting" }
    var temperature = "—"
    val detail = when {
        weather.isActive(WeatherMachines.ready) && data.readings.isNotEmpty() -> {
            val reading = data.readings.last()
            temperature = "${kotlin.math.round(reading.temperature).toInt()}°"
            "Feels like ${kotlin.math.round(reading.feelsLike).toInt()}° · wind ${kotlin.math.round(reading.wind).toInt()} km/h\n${reading.city.name} · ${reading.time}"
        }
        weather.isActive(WeatherMachines.loading) -> "Loading weather…"
        weather.isActive(WeatherMachines.failed) -> data.failures.last()
        weather.isActive(WeatherMachines.timedOut) -> "The request timed out. Refresh to try again."
        else -> "Choose a city to see the weather"
    }
    val report = if (advice.isActive(actron.statechart.StateId("Waiting")) || reports.isEmpty()) "Waiting for the weather result" else reports.last()
    return WeatherView(city.name, phase, temperature, detail, report, "Location · ${location.revision}   Weather · ${weather.revision}   Advice · ${advice.revision}", calls, mode)
}
