package actron.example.weather.app.session

import androidx.compose.runtime.Composable
import kotlin.time.Instant

@Composable
expect fun formatWeatherTime(instant: Instant): String
