package actron.example.weather.core.session

import androidx.compose.runtime.Composable
import java.text.DateFormat
import java.util.Date
import kotlin.time.Instant

@Composable
actual fun formatWeatherTime(instant: Instant): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(instant.toEpochMilliseconds())) +
        " · ${instant.nanosecondsOfSecond / 1_000_000} ${if (androidx.compose.ui.text.intl.Locale.current.language == "ru") "мс" else "ms"}"
