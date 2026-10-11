@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package actron.example.weather

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window

fun main() {
    val client = weatherHttpClient()
    val article = window.location.search.removePrefix("?").split('&').any { it == "scenario=article" }
    val api = if (article) ArticleWeatherApi() else OpenMeteoApi(client)
    ComposeViewport(checkNotNull(document.body)) { MaterialTheme { WeatherApp(api, if (article) "Article scenario · fixed responses · no network" else "Open-Meteo · live public API") } }
}
