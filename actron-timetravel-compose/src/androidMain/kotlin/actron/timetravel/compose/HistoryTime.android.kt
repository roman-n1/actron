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
    return DateFormat.getDateFormat(context).format(date) + " " + SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(context)) "Hms" else "hms"), Locale.getDefault()).format(date) +
        " · ${instant.nanosecondsOfSecond / 1_000_000} ${historyText(HistoryText.Milliseconds)}"
}
