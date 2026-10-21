package actron.example.weather.core.session

import androidx.compose.runtime.Composable
import kotlin.js.Date
import kotlin.time.Instant

@Composable
actual fun formatWeatherTime(instant: Instant): String = Date(instant.toEpochMilliseconds().toDouble()).toLocaleString() + " · ${instant.nanosecondsOfSecond / 1_000_000} ${if (androidx.compose.ui.text.intl.Locale.current.language == "ru") "мс" else "ms"}"
