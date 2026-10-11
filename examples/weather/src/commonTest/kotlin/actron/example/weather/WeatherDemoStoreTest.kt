@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.weather

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherDemoStoreTest {
    @Test
    fun intentsDriveNavigation_andHistoryDoesNotReceiveLiveUpdates() = runTest {
        val demo = WeatherDemoStore(SampleWeatherApi, backgroundScope)
        demo.state.first { it.startup == DemoStartup.Ready }
        assertEquals(WeatherPage.Cities, demo.state.value.page)
        demo.dispatch(DemoIntent.Choose(demoCities[1]))
        demo.state.first { it.view.detail.contains("Lisbon") && it.view.phase == "Ready" }
        assertEquals(WeatherPage.Weather, demo.state.value.page)
        demo.dispatch(DemoIntent.Capture)
        val recorded = demo.state.first { it.playback is DemoPlayback.History }
        demo.state.first { it.view.mode == "Recorded screen" }
        val calls = demo.runtime.networkCalls.value
        demo.dispatch(DemoIntent.Choose(demoCities[2]))
        demo.dispatch(DemoIntent.Refresh)
        demo.dispatch(DemoIntent.PlacePanel(PanelPlacement.Right))
        demo.state.first { it.placement == PanelPlacement.Right }
        assertEquals(calls, demo.runtime.networkCalls.value)
        assertTrue(demo.state.value.playback is DemoPlayback.History)
        val history = (recorded.playback as DemoPlayback.History).recording
        history.controls.replay.seek(0)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        demo.state.first { it.view.temperature == "—" && it.view.mode == "Recorded screen" }
        demo.dispatch(DemoIntent.ReturnLive)
        demo.state.first { it.playback is DemoPlayback.Live && it.view.phase == "Ready" }
        assertEquals("Lisbon", demo.state.value.view.city)
        demo.close()
    }

    @Test
    fun reducerKeepsTheLastCityChoiceDeterministic_withoutRunningEffects() {
        var state = WeatherDemoState(startup = DemoStartup.Ready)
        for (city in demoCities) state = reduceWeatherDemo(state, DemoIntent.Choose(city))
        assertEquals(WeatherPage.Weather, state.page)
        assertEquals(0, state.view.calls)
        assertEquals(WeatherDemoState(startup = DemoStartup.Ready).view, state.view)
    }
}
