@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.example.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import actron.timetravel.compose.TimeTravelPanel

enum class PanelPlacement { Bottom, Left, Right }

/** Host wiring: create a lifecycle-owned MVI Store and render its immutable state. */
@Composable
fun WeatherApp(api: WeatherApi, sourceLabel: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val presenter = remember(api, scope) { WeatherDemoStore(api, scope) }
    DisposableEffect(presenter) { onDispose { presenter.close() } }
    val state by presenter.state.collectAsState()
    WeatherDemoScreen(state, sourceLabel, presenter::dispatch, modifier)
}

/** Rendering only. Weather, recording, navigation and placement decisions arrive from MVI. */
@Composable
fun WeatherDemoScreen(state: WeatherDemoState, sourceLabel: String, dispatch: (DemoIntent) -> Unit, modifier: Modifier = Modifier) {
    var panelHeight by remember { mutableStateOf(300.dp) }
    var panelWidth by remember { mutableStateOf(340.dp) }
    val live = state.playback is DemoPlayback.Live
    val available = live && state.startup == DemoStartup.Ready && state.article == ArticleStatus.Disabled
    val app: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Weather, step by step", style = MaterialTheme.typography.headlineMedium)
            Text(sourceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.page == WeatherPage.Cities) {
                Text("Where shall we go?", style = MaterialTheme.typography.titleLarge)
                Text("Choose a city. Three machines coordinate the selection, loading and recommendation.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (city in demoCities) OutlinedButton(onClick = { dispatch(DemoIntent.Choose(city)) }, enabled = available, modifier = Modifier.testTag("city-${city.name}")) { Text(city.name) }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { dispatch(DemoIntent.ShowCities) }, enabled = state.article == ArticleStatus.Disabled) { Text("Choose another city") }
                    OutlinedButton(onClick = { dispatch(DemoIntent.Refresh) }, enabled = available, modifier = Modifier.testTag("weather-refresh")) { Text("Refresh") }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().testTag("weather-preview")) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.view.city, style = MaterialTheme.typography.titleLarge)
                        Text(state.view.phase, style = MaterialTheme.typography.labelLarge)
                        Text(state.view.temperature, style = MaterialTheme.typography.displayLarge)
                        Text(state.view.detail)
                    }
                }
                Text("Weather recommendation", style = MaterialTheme.typography.titleMedium)
                Text(state.view.advice, modifier = Modifier.testTag("weather-advice"))
            }
            Text(state.view.revisions, style = MaterialTheme.typography.labelMedium)
            Text("Load calls: ${state.view.calls} · ${state.view.mode}", modifier = Modifier.testTag("weather-network-count"), style = MaterialTheme.typography.bodySmall)
            Text(state.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("weather-status"))
            if (state.article != ArticleStatus.Disabled) ArticleStory(state)
            Text("Live weather: Open-Meteo · CC BY 4.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
        val playback = state.playback
        if (playback is DemoPlayback.History) TimeTravelPanel(playback.recording.inspector, timeTravel = playback.recording.controls, modifier = panelModifier)
        else Surface(panelModifier, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Time Travel", style = MaterialTheme.typography.titleMedium)
                Text("Recording all three machines from startup. Capture the history to step through it without new requests.")
                Text("Try rapid city changes, then inspect the cancellations and the response that was accepted.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Surface(modifier.fillMaxSize()) {
        BoxWithConstraints {
            val placement = if (maxWidth < 640.dp) PanelPlacement.Bottom else state.placement
            val dockWidth = minOf(panelWidth, (maxWidth - 240.dp).coerceAtLeast(240.dp))
            val dockHeight = minOf(panelHeight, (maxHeight - 200.dp).coerceAtLeast(160.dp))
            Column {
                FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = state.article != ArticleStatus.Running && state.startup == DemoStartup.Ready && state.capture == CaptureStatus.Available, modifier = Modifier.testTag("weather-capture"), onClick = { dispatch(DemoIntent.Capture) }) { Text(if (state.article == ArticleStatus.Running) "Scenario running…" else if (state.capture == CaptureStatus.Capturing) "Capturing…" else "Capture history") }
                    TextButton(onClick = { dispatch(DemoIntent.ReturnLive) }, enabled = !live, modifier = Modifier.testTag("weather-live")) { Text("Return to live") }
                    TextButton(onClick = { dispatch(DemoIntent.RunChaos) }, enabled = available, modifier = Modifier.testTag("weather-chaos")) { Text("Rapid city changes") }
                    PlacementPicker(state.placement) { dispatch(DemoIntent.PlacePanel(it)) }
                    Text(state.view.mode, modifier = Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.labelMedium)
                }
                if (state.article == ArticleStatus.Completed && state.playback is DemoPlayback.History) FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (checkpoint in state.articleCheckpoints) TextButton(onClick = { dispatch(DemoIntent.ArticleSeek(checkpoint)) }, modifier = Modifier.testTag("article-seek-${checkpoint.label}")) { Text(checkpoint.label) }
                }
                HorizontalDivider()
                if (placement == PanelPlacement.Bottom) {
                    Box(Modifier.weight(1f)) { app() }
                    ResizeHandle(true) { amount -> panelHeight = (panelHeight - amount.dp).coerceIn(220.dp, 520.dp) }
                    panel(Modifier.fillMaxWidth().height(dockHeight))
                } else Row(Modifier.weight(1f)) {
                    if (placement == PanelPlacement.Left) {
                        panel(Modifier.width(dockWidth).fillMaxHeight())
                        ResizeHandle(false) { amount -> panelWidth = (panelWidth + amount.dp).coerceIn(240.dp, 520.dp) }
                    }
                    Box(Modifier.weight(1f)) { app() }
                    if (placement == PanelPlacement.Right) {
                        ResizeHandle(false) { amount -> panelWidth = (panelWidth - amount.dp).coerceIn(240.dp, 520.dp) }
                        panel(Modifier.width(dockWidth).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleStory(state: WeatherDemoState) {
    Card(Modifier.fillMaxWidth().testTag("article-story")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Article scenario · scripted data", style = MaterialTheme.typography.titleMedium)
            Text("Original run · five requests. This summary stays fixed while you inspect historical states.", style = MaterialTheme.typography.bodySmall)
            for ((index, request) in state.articleRequests.withIndex()) {
                val timing = if (index < 2) "response held" else "${request.delayMillis} ms"
                Text("${index + 1}. ${request.city.name} · ${request.status} · $timing · ${request.outcome}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.article == ArticleStatus.Completed) Text("Back / Next: watch Loading → Ready → Failed → Ready and the recommendation change. Verify: replay the recorded answers. Experiment here: replace an answer or advance virtual time. Load calls stays at 5.", style = MaterialTheme.typography.bodySmall)
            if (state.article == ArticleStatus.Failed) Text("Start a fresh article session to try again.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlacementPicker(placement: PanelPlacement, select: (PanelPlacement) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("weather-placement")) { Text("Panel: ${placement.name}") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (value in PanelPlacement.entries) DropdownMenuItem(text = { Text(value.name) }, onClick = { select(value); open = false }, modifier = Modifier.testTag("weather-placement-${value.name}"))
        }
    }
}

@Composable
private fun ResizeHandle(horizontal: Boolean, resize: (Float) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier = (if (horizontal) Modifier.fillMaxWidth().height(8.dp) else Modifier.fillMaxHeight().width(8.dp))
        .background(MaterialTheme.colorScheme.outlineVariant)
        .pointerInput(horizontal) { detectDragGestures { change, amount -> change.consume(); resize((if (horizontal) amount.y else amount.x) / density.density) } }, contentAlignment = Alignment.Center) {}
}
