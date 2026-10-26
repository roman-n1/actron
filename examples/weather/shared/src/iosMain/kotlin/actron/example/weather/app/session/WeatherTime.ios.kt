package actron.example.weather.app.session

import androidx.compose.runtime.Composable
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.autoupdatingCurrentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import kotlin.time.Instant

@Composable
internal actual fun formatWeatherDate(instant: Instant): String {
    val formatter = NSDateFormatter()
    formatter.locale = NSLocale.autoupdatingCurrentLocale
    formatter.timeZone = NSTimeZone.localTimeZone
    formatter.dateStyle = NSDateFormatterShortStyle
    formatter.timeStyle = NSDateFormatterMediumStyle
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(instant.toEpochMilliseconds() / 1000.0))
}
