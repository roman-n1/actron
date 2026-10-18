package actron.timetravel.compose

import androidx.compose.runtime.Composable
import kotlin.js.Date
import kotlin.time.Instant

@Composable
actual fun formatHistoryTime(instant: Instant): String = Date(instant.toEpochMilliseconds().toDouble()).toLocaleString() + " · ${instant.nanosecondsOfSecond / 1_000_000} ${historyText(HistoryText.Milliseconds)}"
