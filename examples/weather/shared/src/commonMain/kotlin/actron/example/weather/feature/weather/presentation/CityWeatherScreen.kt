@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.example.weather.feature.weather.presentation

import actron.example.weather.app.session.formatWeatherTime
import actron.example.weather.core.presentation.resolve
import actron.example.weather.resources.Res
import actron.example.weather.resources.advice_close_symbol
import actron.example.weather.resources.advice_dismiss
import actron.example.weather.resources.advice_title
import actron.example.weather.resources.back
import actron.example.weather.resources.current_weather
import actron.example.weather.resources.next_hours
import actron.example.weather.resources.rain
import actron.example.weather.resources.refresh
import actron.example.weather.resources.retry
import actron.example.weather.resources.retry_forecast
import actron.example.weather.resources.temperature
import actron.example.weather.resources.updated
import actron.example.weather.resources.weather_in_city
import actron.example.weather.resources.wind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
fun CityWeatherScreen(
    state: CityWeatherUiState,
    modifier: Modifier = Modifier,
    onIntent: (CityWeatherIntent) -> Unit,
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("weather-screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WeatherHeader(state = state, onIntent = onIntent)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().testTag("weather-current")) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Text(text = stringResource(resource = Res.string.current_weather), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(weight = 1f))
                        if (state.updatedAt.isNotEmpty()) {
                            Text(
                                localizedUpdatedAt(state.updatedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.weight(1f).testTag("weather-updated-at"),
                            )
                        }
                    }
                    Text(state.temperature.resolve(), style = MaterialTheme.typography.displayLarge)
                    Text(state.detail.resolve())
                }
            }
            WeatherAdviceCard(state = state, onIntent = onIntent)
            Column(Modifier.fillMaxWidth().testTag("hourly-forecast"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(resource = Res.string.next_hours), style = MaterialTheme.typography.titleMedium)
                for (status in state.forecastStatus) Text(status.resolve(), modifier = Modifier.testTag("forecast-status"))
                if (state.forecastFailed) TextButton(onClick = { onIntent(CityWeatherIntent.RetryForecast) }, enabled = state.enabled, modifier = Modifier.testTag("forecast-retry")) { Text(stringResource(resource = Res.string.retry_forecast)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (hour in state.forecast) {
                        Card(Modifier.width(140.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(hour.time.substringAfter('T'), style = MaterialTheme.typography.labelLarge)
                                Text(stringResource(resource = Res.string.temperature, kotlin.math.round(x = hour.temperature).toInt()), style = MaterialTheme.typography.headlineSmall)
                                Text(stringResource(resource = Res.string.wind, kotlin.math.round(x = hour.wind).toInt()), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(resource = Res.string.rain, hour.rainChance), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun localizedUpdatedAt(value: String): String {
    val parsed =
        try {
            listOf(Instant.parse(value))
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    return if (parsed.isEmpty()) value else stringResource(resource = Res.string.updated, formatWeatherTime(instant = parsed.single()))
}

@Composable
private fun WeatherAdviceCard(
    state: CityWeatherUiState,
    onIntent: (CityWeatherIntent) -> Unit,
) {
    if (state.advice.isEmpty()) return
    val closeDescription = stringResource(resource = Res.string.advice_dismiss)
    Card(modifier = Modifier.fillMaxWidth().testTag(tag = "weather-advice"), content = {
        Column(modifier = Modifier.padding(all = 16.dp), verticalArrangement = Arrangement.spacedBy(space = 8.dp), content = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = {
                Text(text = stringResource(resource = Res.string.advice_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(weight = 1f))
                for (action in state.adviceActions) {
                    when (action) {
                        WeatherAdviceAction.Dismiss -> {
                            TextButton(
                                onClick = { onIntent(CityWeatherIntent.DismissAdvice) },
                                enabled = state.enabled,
                                modifier = Modifier.testTag(tag = "advice-dismiss").semantics(properties = { contentDescription = closeDescription }),
                                content = { Text(text = stringResource(resource = Res.string.advice_close_symbol)) },
                            )
                        }

                        WeatherAdviceAction.Retry -> {
                            TextButton(
                                onClick = { onIntent(CityWeatherIntent.RetryAdvice) },
                                enabled = state.enabled,
                                modifier = Modifier.testTag(tag = "advice-retry"),
                                content = { Text(text = stringResource(resource = Res.string.retry)) },
                            )
                        }
                    }
                }
            })
            for (message in state.advice) Text(text = message.resolve())
        })
    })
}

/** Shared screen chrome belongs to the parent; child cards render only their own feature. */
@Composable
private fun WeatherHeader(
    state: CityWeatherUiState,
    onIntent: (CityWeatherIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(tag = "weather-header"), verticalArrangement = Arrangement.spacedBy(space = 8.dp), content = {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, content = {
            TextButton(
                onClick = { onIntent(CityWeatherIntent.Back) },
                enabled = state.enabled,
                modifier = Modifier.testTag(tag = "weather-back"),
                content = { Text(text = stringResource(resource = Res.string.back)) },
            )
            OutlinedButton(
                onClick = { onIntent(CityWeatherIntent.Refresh) },
                enabled = state.enabled,
                modifier = Modifier.testTag(tag = "weather-refresh"),
                content = { Text(text = stringResource(resource = Res.string.refresh)) },
            )
        })
        Text(text = stringResource(resource = Res.string.weather_in_city, state.city), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag(tag = "weather-city"))
    })
}
