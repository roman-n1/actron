package actron.example.weather.app.session

import androidx.compose.runtime.Composable
import java.text.DateFormat
import java.util.Date
import kotlin.time.Instant

@Composable
internal actual fun formatWeatherDate(instant: Instant): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(instant.toEpochMilliseconds()))
