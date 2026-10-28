package actron.example.weather.app.session

import androidx.compose.runtime.Composable
import kotlin.time.Instant

private fun localTime(millis: Double): String = js("new Date(millis).toLocaleString()")

@Composable
internal actual fun formatWeatherDate(instant: Instant): String = localTime(instant.toEpochMilliseconds().toDouble())
