package actron.example.weather

import actron.statechart.machine.MachineInput
import actron.timetravel.compose.BranchInput
import actron.timetravel.compose.GroupTravel

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/** `--offline` selects sample data; `--article` runs the fixed five-request story. */
fun main(args: Array<String>) {
    val client = weatherHttpClient()
    val check = "--check" in args || "--check-live" in args
    val offline = "--offline" in args || "--check" in args
    val article = "--article" in args || "--check-article" in args
    val api = if (article) ArticleWeatherApi() else if (offline) SampleWeatherApi else OpenMeteoApi(client)
    try {
        if ("--check-article" in args) {
            runBlocking { checkArticle(api as ArticleWeatherApi) }
            return
        }
        if (check) {
            runBlocking { checkWeather(api) }
            return
        }
        application {
            Window(onCloseRequest = ::exitApplication, title = "Actron · Weather Time Travel", state = rememberWindowState(size = DpSize(1180.dp, 900.dp))) {
                MaterialTheme { WeatherApp(api, if (article) "Article scenario · fixed responses · no network" else if (offline) "Offline sample · no network" else "Open-Meteo · live public API") }
            }
        }
    } finally { client.close() }
}

@OptIn(actron.core.ExperimentalActronApi::class)
private suspend fun checkArticle(api: ArticleWeatherApi) {
    val scope = CoroutineScope(kotlin.coroutines.coroutineContext + SupervisorJob())
    val demo = WeatherDemoStore(api, scope)
    try {
        val state = withTimeout(20.seconds) { demo.state.first { it.article == ArticleStatus.Completed || it.article == ArticleStatus.Failed } }
        check(state.article == ArticleStatus.Completed) { state.status }
        val history = (state.playback as DemoPlayback.History).recording
        check(history.controls.replay.session.verify().isEmpty())
        for (position in 0..history.controls.replay.length) history.controls.replay.seek(position)
        check(demo.runtime.networkCalls.value == 5)
        println("Article scenario: ${api.requests.value.map { it.status }}; five loads; replay verified without further API calls.")
    } finally { demo.close(); scope.cancel() }
}

@OptIn(actron.core.ExperimentalActronApi::class)
private suspend fun checkWeather(api: WeatherApi) {
    val scope = CoroutineScope(kotlin.coroutines.coroutineContext + SupervisorJob())
    val runtime = WeatherRuntime(api, scope)
    try {
        runtime.start()
        val loaded = withTimeout(20.seconds) { runtime.weather.state.first { it.isActive(WeatherMachines.ready) || it.isActive(WeatherMachines.failed) || it.isActive(WeatherMachines.timedOut) } }
        check(loaded.isActive(WeatherMachines.ready)) { "Weather load failed: ${loaded.context.failures}" }
        withTimeout(5.seconds) { runtime.advice.state.first { it.context.reports.isNotEmpty() } }
        val history = runtime.freeze()
        check(history.controls.replay.session.verify().isEmpty())
        val recording = history.controls.replay.session.recording
        val result = recording.order.indexOfFirst { step -> step.store == runtime.weatherId && recording.members.getValue(step.store).steps[step.step].input is MachineInput.CommandResult }
        check(result >= 0)
        history.controls.replay.seek(result)
        history.controls.branchHere()
        val branch = (history.controls.travel as GroupTravel.Experiment).branch
        val reading = loaded.context.readings.last().copy(wind = 55.0, time = "Experiment")
        branch.answer(BranchInput.Answer(runtime.weatherId, "Wind", WeatherAction.Loaded(reading)), branch.awaiting(runtime.weatherId).single().id)
        check(branch.snapshot(runtime.adviceId).isActive(actron.statechart.StateId("Windy")))
        history.controls.returnToReplay()
        check(history.controls.replay.position == result)
        check(runtime.networkCalls.value == 1)
        println("${loaded.context.readings.last()}")
        println("${recording.length} recorded inputs across three machines; replay verified; branch changed advice; load calls: 1.")
    } finally { runtime.close(); scope.cancel() }
}
