package actron.example.weather.feature.cities.presentation

import actron.example.weather.core.mvi.RequestState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CitySearchScreen(
    state: CitySearchUiState,
    modifier: Modifier = Modifier,
    onIntent: (CitySearchIntent) -> Unit,
    onSettings: () -> Unit = {},
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("cities-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val settingsLabel = if (androidx.compose.ui.text.intl.Locale.current.language == "ru") "Настройки" else "Settings"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Cities", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onSettings, modifier = Modifier.testTag("debug-settings").semantics { contentDescription = settingsLabel }) {
                    val color = MaterialTheme.colorScheme.onSurfaceVariant
                    Canvas(Modifier.size(24.dp)) {
                        val radius = size.minDimension * 0.31f
                        val stroke = 2.dp.toPx()
                        drawCircle(color, radius, style = Stroke(stroke))
                        drawCircle(color, radius * 0.4f, style = Stroke(stroke))
                        repeat(8) { tooth ->
                            val angle = tooth * kotlin.math.PI.toFloat() / 4f
                            val direction = Offset(cos(angle), sin(angle))
                            drawLine(color, center + direction * radius, center + direction * (size.minDimension * 0.46f), stroke * 1.6f)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = { onIntent(CitySearchIntent.QueryChanged(it)) },
                label = { Text("Search cities") },
                singleLine = true,
                readOnly = !state.enabled,
                modifier = Modifier.fillMaxWidth().testTag("city-search"),
            )
            when (val phase = state.phase) {
                RequestState.Idle -> {
                    Text("Enter a city name", modifier = Modifier.testTag("city-search-hint"))
                }

                RequestState.Debouncing -> {
                    Text("Waiting for typing…", modifier = Modifier.testTag("city-search-debounce"))
                }

                RequestState.Loading -> {
                    Text("Searching…", modifier = Modifier.testTag("city-search-loading"))
                }

                is RequestState.Error, RequestState.TimedOut -> {
                    val message = if (phase is RequestState.Error) phase.message else "City search timed out. Try again."
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onIntent(CitySearchIntent.Retry) }, enabled = state.enabled) { Text("Retry") }
                }

                is RequestState.Success -> {
                    if (phase.value.isEmpty()) Text("No cities found")
                    for (option in phase.value) {
                        val city = option.city
                        OutlinedButton(
                            onClick = { onIntent(CitySearchIntent.SelectCity(city)) },
                            enabled = state.enabled,
                            colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.primary),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth().testTag("city-${city.name}"),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(city.name, style = MaterialTheme.typography.titleMedium)
                                if (option.region.isNotBlank()) Text(option.region, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
