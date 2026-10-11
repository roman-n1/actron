@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.weather

import actron.statechart.StateId
import actron.statechart.machine.MachineInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ArticleStage(val description: String) {
    FirstCity("1/6 · Hold the Novosibirsk request"),
    SecondCity("2/6 · Choose Lisbon; cancel Novosibirsk"),
    LastCity("3/6 · Choose Tokyo; cancel Lisbon; return calm weather"),
    ConnectionFailure("4/6 · Refresh Tokyo; return a connection failure"),
    Recovery("5/6 · Retry Tokyo; return strong wind"),
    Capture("6/6 · Capture all three machines for offline Time Travel"),
}

enum class ArticleRequestStatus { Waiting, Loading, Cancelled, Answered, Failed }
data class ArticleRequest(val city: City, val delayMillis: Long, val outcome: String, val status: ArticleRequestStatus = ArticleRequestStatus.Waiting)
data class ArticleCheckpoint(val label: String, val position: Int)

/** Named positions refer to the immutable original recording, never to a branch. */
internal fun articleCheckpoints(history: WeatherRuntime.History): List<ArticleCheckpoint> {
    val recording = history.controls.replay.session.recording
    val answers = buildList {
        for ((index, step) in recording.order.withIndex()) {
            val input = recording.members.getValue(step.store).steps[step.step].input
            if (input is MachineInput.CommandResult && input.action is WeatherAction) add(index to input.action)
        }
    }
    val calm = answers.first { it.second is WeatherAction.Loaded }.first
    val failure = answers.first { it.second is WeatherAction.Unavailable }.first
    return listOf(ArticleCheckpoint("Calm weather", calm + 1), ArticleCheckpoint("Before failure", failure), ArticleCheckpoint("Connection failed", failure + 1), ArticleCheckpoint("Recovered · wind", recording.length))
}

/** A fresh instance is one finite article run. Gates control order; delay controls presentation pace.
 * The first two responses are held until cancellation instead of racing two loading delays.
 * Nothing here runs during replay or in a branch. */
class ArticleWeatherApi : WeatherApi {
    private val tokyo = demoCities[2]
    private val plans = listOf(
        ArticleRequest(demoCities[0], 3_000, "Held → cancelled"),
        ArticleRequest(demoCities[1], 2_000, "Held → cancelled"),
        ArticleRequest(tokyo, 600, "24°C · wind 12 km/h"),
        ArticleRequest(tokyo, 800, "Scripted connection failure"),
        ArticleRequest(tokyo, 800, "24°C · wind 55 km/h"),
    )
    private val gates = List(plans.size) { CompletableDeferred<Unit>() }
    private val lock = Mutex()
    private var next = 0
    private val progress = MutableStateFlow(plans)
    val requests = progress.asStateFlow()

    override suspend fun load(city: City): WeatherReading {
        val index = lock.withLock {
            check(next < plans.size) { "Article scenario finished; start a fresh session to run it again" }
            check(city == plans[next].city) { "Unexpected article request: ${city.name}" }
            next++
            next - 1
        }
        mark(index, ArticleRequestStatus.Loading)
        try {
            gates[index].await()
            delay(plans[index].delayMillis)
            if (index == 3) {
                mark(index, ArticleRequestStatus.Failed)
                throw ArticleConnectionFailure()
            }
            mark(index, ArticleRequestStatus.Answered)
            return WeatherReading(city, 24.0, 22.0, if (index == 4) 55.0 else 12.0, 2, "Article fixture · 2026-01-15T12:00")
        } catch (cancelled: CancellationException) {
            mark(index, ArticleRequestStatus.Cancelled)
            throw cancelled
        }
    }

    internal suspend fun started(index: Int) { requests.first { it[index].status != ArticleRequestStatus.Waiting } }
    internal suspend fun cancelled(index: Int) { requests.first { it[index].status == ArticleRequestStatus.Cancelled } }
    internal fun release(index: Int) {
        check(index >= 2 && requests.value[index].status == ArticleRequestStatus.Loading)
        gates[index].complete(Unit)
    }
    private fun mark(index: Int, status: ArticleRequestStatus) {
        progress.update { values -> values.mapIndexed { position, request -> if (position == index) request.copy(status = status) else request } }
    }
    private class ArticleConnectionFailure : Exception("Article fixture: connection unavailable")
}

/** Wait for actual command admission, cancellation and bridged advice before moving on.
 * These barriers make the story independent of network and coroutine scheduling speed. */
internal suspend fun runArticleScenario(api: ArticleWeatherApi, runtime: WeatherRuntime, stage: (ArticleStage) -> Unit): WeatherRuntime.History {
    stage(ArticleStage.FirstCity)
    api.started(0)
    delay(700)
    stage(ArticleStage.SecondCity)
    runtime.choose(demoCities[1])
    api.started(1)
    api.cancelled(0)
    delay(700)
    stage(ArticleStage.LastCity)
    runtime.choose(demoCities[2])
    api.started(2)
    api.cancelled(1)
    api.release(2)
    runtime.weather.state.first { it.isActive(WeatherMachines.ready) }
    runtime.advice.state.first { it.isActive(StateId("Comfortable")) }
    delay(600)
    stage(ArticleStage.ConnectionFailure)
    runtime.refresh()
    api.started(3)
    api.release(3)
    runtime.weather.state.first { it.isActive(WeatherMachines.failed) }
    delay(600)
    stage(ArticleStage.Recovery)
    runtime.refresh()
    api.started(4)
    api.release(4)
    runtime.weather.state.first { it.isActive(WeatherMachines.ready) && it.context.readings.last().wind == 55.0 }
    runtime.advice.state.first { it.isActive(StateId("Windy")) }
    stage(ArticleStage.Capture)
    return runtime.freeze()
}
