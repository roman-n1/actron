package actron.timetravel.compose

import androidx.compose.runtime.Composable
import java.text.DateFormat
import java.util.Date
import kotlin.time.Instant

@Composable
actual fun formatHistoryTime(instant: Instant): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(instant.toEpochMilliseconds())) +
        " · ${instant.nanosecondsOfSecond / 1_000_000} ${historyText(HistoryText.Milliseconds)}"
