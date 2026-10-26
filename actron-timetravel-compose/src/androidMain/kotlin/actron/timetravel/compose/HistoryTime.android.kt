package actron.timetravel.compose

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Instant

@Composable
actual fun formatHistoryTime(instant: Instant): String {
    val context = LocalContext.current
    val date = Date(instant.toEpochMilliseconds())
    val pattern: String = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(context)) "HmsSSS" else "hmsSSS")
    val time: String = SimpleDateFormat(pattern, Locale.getDefault()).format(date)
    return "${historyText(HistoryText.RecordedAt)}: ${DateFormat.getDateFormat(context).format(date)} $time"
}
