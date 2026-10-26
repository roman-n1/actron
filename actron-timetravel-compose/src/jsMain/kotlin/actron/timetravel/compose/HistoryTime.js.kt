package actron.timetravel.compose

import androidx.compose.runtime.Composable
import kotlin.time.Instant

private fun localTime(millis: Double): String = js("new Date(millis).toLocaleString(undefined, {year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3})")

@Composable
actual fun formatHistoryTime(instant: Instant): String = "${historyText(HistoryText.RecordedAt)}: ${localTime(instant.toEpochMilliseconds().toDouble())}"
