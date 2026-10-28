package actron.example.weather.app.session

import actron.example.weather.resources.Res
import actron.example.weather.resources.timestamp
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
internal expect fun formatWeatherDate(instant: Instant): String

@Composable
fun formatWeatherTime(instant: Instant): String = stringResource(resource = Res.string.timestamp, formatWeatherDate(instant = instant), instant.nanosecondsOfSecond / 1_000_000)
