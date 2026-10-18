@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.example.weather

import actron.example.weather.app.DefaultWeatherRootComponent
import actron.example.weather.core.domain.City
import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.cities.data.LocalCitySearchRepository
import actron.example.weather.feature.cities.domain.CityOption
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.weather.data.SampleHourlyRepository
import actron.example.weather.feature.weather.data.SampleWeatherRepository
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.domain.WeatherRepository
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlin.test.Test

class WeatherAppTest {
    @Test
    fun searchResult_opensWeatherForServerCoordinates() =
        runComposeUiTest {
            val city = City("Springfield", 39.78, -89.65)
            val requests = java.util.concurrent.CopyOnWriteArrayList<City>()
            val api =
                WeatherRepository { selected ->
                    requests += selected
                    WeatherReading(selected, 19.0, 18.0, 5.0, 0, "12:00")
                }
            val search = CitySearchRepository { listOf(CityOption(city, "Illinois, United States")) }
            setRootContent(api, citySearch = search)
            onNodeWithText("Weather, step by step").assertDoesNotExist()
            onNodeWithTag("city-search").performTextInput("Springfield")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Springfield").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
            onNodeWithText("Illinois, United States").assertIsDisplayed()
            onNodeWithTag("city-Springfield").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Springfield").fetchSemanticsNodes().isNotEmpty() && onAllNodesWithTag("weather-updated-at").fetchSemanticsNodes().isNotEmpty() }
            kotlin.test.assertEquals(city, requests.last())
            onNodeWithTag("weather-back").performClick()
            onNodeWithTag("city-search").assertTextContains("Springfield")
            val previousCalls = requests.size
            onNodeWithTag("city-Springfield").assertIsEnabled().performClick()
            onNodeWithTag("weather-refresh").assertIsEnabled()
            waitUntil(timeoutMillis = 10_000) { requests.size == previousCalls + 1 }
            kotlin.test.assertEquals(city, requests.last())
            onNodeWithTag("weather-city").assertTextContains("Springfield")
        }

    @Test
    fun cityNavigation_supportsBack_anotherCity_andRefresh() =
        runComposeUiTest {
            val requests = java.util.concurrent.CopyOnWriteArrayList<City>()
            val api =
                object : WeatherRepository {
                    override suspend fun load(city: City): WeatherReading {
                        requests += city
                        return WeatherReading(city, 22.0, 21.0, 5.0, 0, "12:00")
                    }
                }
            setRootContent(api)
            onNodeWithTag("cities-screen").assertIsDisplayed()
            onNodeWithTag("weather-screen").assertDoesNotExist()
            onNodeWithTag("city-search").performTextReplacement("Lisbon")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Lisbon").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("city-Lisbon").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Lisbon").fetchSemanticsNodes().isNotEmpty() && onAllNodesWithTag("weather-updated-at").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("cities-screen").assertDoesNotExist()
            onNodeWithTag("weather-city").assertTextContains("Lisbon")
            onNodeWithTag("weather-back").performClick()
            onNodeWithTag("weather-screen").assertDoesNotExist()
            onNodeWithTag("city-search").performTextReplacement("")
            onNodeWithTag("city-search-hint").assertIsDisplayed()
            onNodeWithTag("city-Lisbon").assertDoesNotExist()
            onNodeWithTag("city-search").performTextReplacement("Tokyo")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Tokyo").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("city-Tokyo").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Tokyo").fetchSemanticsNodes().isNotEmpty() && onAllNodesWithTag("weather-updated-at").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("weather-city").assertTextContains("Tokyo")
            val calls = requests.size
            onNodeWithTag("weather-refresh").performClick()
            waitUntil(timeoutMillis = 10_000) { requests.size == calls + 1 }
            kotlin.test.assertEquals(demoCities[2], requests.last())
            onNodeWithTag("weather-city").assertTextContains("Tokyo")
            onNodeWithTag("cities-screen").assertDoesNotExist()
        }

    @Test
    fun weatherAppShowsOnlyScreensWithoutDebugControls() =
        runComposeUiTest {
            setRootContent(SampleWeatherRepository)
            onNodeWithTag("cities-screen").assertIsDisplayed()
            assertNoDebugUi()
            onNodeWithTag("city-search").performTextReplacement("Lisbon")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("city-Lisbon").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("city-Lisbon").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Feels like 16° · wind 12 km/h").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("weather-current").assertIsDisplayed()
            onNodeWithTag("hourly-forecast").assertIsDisplayed()
            assertNoDebugUi()
        }
}

private fun ComposeUiTest.assertNoDebugUi() {
    for (tag in listOf("time-travel-panel", "weather-capture", "weather-live", "weather-network-count", "article-story")) {
        onNodeWithTag(tag).assertDoesNotExist()
    }
    for (text in listOf("Time Travel", "Capture history", "Return to live")) {
        onNodeWithText(text).assertDoesNotExist()
    }
}

private fun ComposeUiTest.setRootContent(
    api: WeatherRepository,
    citySearch: CitySearchRepository = LocalCitySearchRepository,
) {
    val lifecycle = LifecycleRegistry()
    val root =
        runOnUiThread {
            DefaultWeatherRootComponent(DefaultComponentContext(lifecycle), api, citySearch, SampleHourlyRepository)
        }
    setContent {
        DisposableEffect(root) {
            lifecycle.resume()
            onDispose { lifecycle.destroy() }
        }
        MaterialTheme {
            Box(Modifier.width(1180.dp).height(900.dp)) { root.Render() }
        }
    }
}
