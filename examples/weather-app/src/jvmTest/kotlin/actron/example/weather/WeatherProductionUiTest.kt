package actron.example.weather

import actron.example.weather.app.WeatherTheme
import actron.example.weather.core.session.WeatherSession
import actron.example.weather.feature.cities.presentation.CitySearchScreen
import actron.example.weather.feature.cities.presentation.toUiState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalTestApi::class)
class WeatherProductionUiTest {
    @Test
    fun productionClasspathContainsNoDebugger() {
        listOf(
            "actron.timetravel.HistoryTimeline",
            "actron.timetravel.BoundedMachineRecorder",
            "actron.timetravel.MachineRecorder",
            "actron.timetravel.ReplaySession",
            "actron.timetravel.compose.HistoryPanelKt",
            "actron.example.weather.core.debug.WeatherHistory",
            "actron.example.weather.core.debug.WeatherDebugLayoutKt",
        ).forEach { name ->
            assertFailsWith<ClassNotFoundException>(name) {
                Class.forName(name)
                Unit
            }
        }
    }

    @Test
    fun normalScreenHasNoDebugEntryPoint() =
        runComposeUiTest {
            val session = WeatherSession()
            setContent {
                WeatherTheme {
                    session.Render(onContinue = {}) {
                        CitySearchScreen(
                            session.frame.search.snapshot.context
                                .toUiState(true),
                            onIntent = {},
                        )
                    }
                }
            }
            onNodeWithTag("cities-screen").assertExists()
            onNodeWithTag("city-search").assertExists()
            onNodeWithTag("debug-settings").assertDoesNotExist()
            onNodeWithTag("weather-history-panel").assertDoesNotExist()
        }
}
