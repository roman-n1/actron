package actron.example.weather.app.session

import androidx.compose.runtime.Composable
import kotlin.time.Instant

private fun localTime(millis: Double): String = js("new Date(millis).toLocaleString()")

@Composable
actual fun formatWeatherTime(instant: Instant): String = localTime(instant.toEpochMilliseconds().toDouble()) + " · ${instant.nanosecondsOfSecond / 1_000_000} ${if (androidx.compose.ui.text.intl.Locale.current.language == "ru") "мс" else "ms"}"
