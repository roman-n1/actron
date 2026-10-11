@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.example.weather

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

class WeatherAppTest {
    @Test
    fun articleWorkflow_opensHistory_andNamedCheckpointsChangeTheScreenWithoutLoads() = runComposeUiTest {
        val api = ArticleWeatherApi()
        setContent { MaterialTheme { WeatherApp(api, "Article scenario · fixed responses · no network", Modifier.width(1180.dp).height(900.dp)) } }
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("time-travel-panel").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("weather-network-count").performScrollTo().assertTextContains("Load calls: 5", substring = true)
        onNodeWithTag("article-seek-Connection failed").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Article fixture: connection unavailable").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("article-seek-Before failure").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Loading weather…").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("article-seek-Recovered · wind").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Strong wind: keep your walk short").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("weather-network-count").performScrollTo().assertTextContains("Load calls: 5", substring = true)
        onNodeWithTag("weather-placement").performClick()
        onNodeWithTag("weather-placement-Right").performClick()
        onNodeWithTag("weather-preview").performScrollTo()
        val bitmap = onRoot().captureToImage().asSkiaBitmap()
        org.jetbrains.skia.Image.makeFromBitmap(bitmap).use { image ->
            val directory = java.io.File("build/previews").also { it.mkdirs() }
            java.io.File(directory, "weather-article.png").writeBytes(checkNotNull(image.encodeToData()).bytes)
        }
    }

    @Test
    fun desktopWorkflow_keepsWeatherVisible_inEveryDockPlacement() = runComposeUiTest {
        setContent { MaterialTheme { WeatherApp(SampleWeatherApi, "Offline sample", Modifier.width(1180.dp).height(900.dp)) } }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Choose a city, then capture its history").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("city-Lisbon").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("18°").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("weather-capture").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("time-travel-panel").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("weather-preview").assertIsDisplayed()
        for (placement in listOf(PanelPlacement.Left, PanelPlacement.Right, PanelPlacement.Bottom)) {
            onNodeWithTag("weather-placement").performClick()
            onNodeWithTag("weather-placement-${placement.name}").performClick()
            onNodeWithTag("weather-preview").assertIsDisplayed()
            onNodeWithTag("panel-forward").assertIsDisplayed()
            val bitmap = onRoot().captureToImage().asSkiaBitmap()
            org.jetbrains.skia.Image.makeFromBitmap(bitmap).use { image ->
                val directory = java.io.File("build/previews").also { it.mkdirs() }
                java.io.File(directory, "weather-${placement.name.lowercase()}.png").writeBytes(checkNotNull(image.encodeToData()).bytes)
            }
        }
        onNodeWithTag("panel-machines").performClick()
        onNodeWithTag("panel-machine-weather").performClick()
        onNodeWithTag("panel-branch").performClick()
        onNodeWithTag("panel-input-weather-Refresh").performScrollTo().performClick()
        onNodeWithText("Answer…").performScrollTo().performClick()
        onNodeWithText("Lisbon: strong wind").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Strong wind: keep your walk short").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("weather-network-count").assertTextContains("Load calls: 2", substring = true)
        onNodeWithTag("panel-return").performClick()
        onNodeWithTag("panel-back").performClick()
        onNodeWithTag("weather-network-count").assertTextContains("Recorded screen", substring = true)
        onNodeWithTag("weather-live").performClick()
        onNodeWithTag("weather-network-count").assertTextContains("Live", substring = true)
        onNodeWithTag("weather-refresh").assertIsEnabled()
    }
}
