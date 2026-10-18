package actron.timetravel.compose

import androidx.compose.runtime.Composable
import kotlin.time.Instant

private fun localTime(millis: Double): String = js("new Date(millis).toLocaleString()")

@Composable
actual fun formatHistoryTime(instant: Instant): String = localTime(instant.toEpochMilliseconds().toDouble()) + " · ${instant.nanosecondsOfSecond / 1_000_000} ${historyText(HistoryText.Milliseconds)}"
