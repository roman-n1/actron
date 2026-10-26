package actron.timetravel.compose

import androidx.compose.runtime.Composable
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.autoupdatingCurrentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import kotlin.time.Instant

@Composable
actual fun formatHistoryTime(instant: Instant): String {
    val formatter = NSDateFormatter()
    formatter.locale = NSLocale.autoupdatingCurrentLocale
    formatter.timeZone = NSTimeZone.localTimeZone
    formatter.setLocalizedDateFormatFromTemplate("yMdjmmssSSS")
    val time = formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(instant.toEpochMilliseconds() / 1000.0))
    return "${historyText(HistoryText.RecordedAt)}: $time"
}
