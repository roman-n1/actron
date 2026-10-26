package actron.example.weather.app.session

import androidx.compose.runtime.Composable
import kotlin.js.Date
import kotlin.time.Instant

@Composable
internal actual fun formatWeatherDate(instant: Instant): String = Date(instant.toEpochMilliseconds().toDouble()).toLocaleString()
