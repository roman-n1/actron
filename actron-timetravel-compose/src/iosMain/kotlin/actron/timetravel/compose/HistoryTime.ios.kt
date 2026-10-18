package actron.timetravel.compose

import androidx.compose.runtime.Composable
import platform.Foundation.*
import kotlin.time.Instant

@Composable
actual fun formatHistoryTime(instant: Instant): String {
    val formatter = NSDateFormatter()
    formatter.locale = NSLocale.autoupdatingCurrentLocale
    formatter.timeZone = NSTimeZone.localTimeZone
    formatter.dateStyle = NSDateFormatterShortStyle
    formatter.timeStyle = NSDateFormatterMediumStyle
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(instant.toEpochMilliseconds() / 1000.0)) +
        " · ${instant.nanosecondsOfSecond / 1_000_000} ${historyText(HistoryText.Milliseconds)}"
}
