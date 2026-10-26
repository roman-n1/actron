package actron.timetravel.compose

import androidx.compose.runtime.Composable
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.time.Instant

@Composable
actual fun formatHistoryTime(instant: Instant): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) as SimpleDateFormat
    formatter.applyPattern(formatter.toPattern().replace("ss", "ss.SSS"))
    return "${historyText(HistoryText.RecordedAt)}: ${formatter.format(Date(instant.toEpochMilliseconds()))}"
}
