@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package actron.example.weather

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

fun main() {
    val client = weatherHttpClient()
    ComposeViewport(checkNotNull(document.body)) { MaterialTheme { WeatherApp(OpenMeteoApi(client), "Open-Meteo · live public API") } }
}
