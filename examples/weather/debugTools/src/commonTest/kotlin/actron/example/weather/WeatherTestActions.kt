package actron.example.weather

import actron.example.weather.app.debug.DebugWeatherRootComponent
import actron.example.weather.feature.weather.presentation.CityWeatherComponent
import actron.example.weather.feature.weather.presentation.CityWeatherIntent
import kotlin.test.assertIs

/** Drive the same explicit parent intent as the screen's Refresh button. */
internal fun DebugWeatherRootComponent.refreshWeather() {
    assertIs<CityWeatherComponent>(value = getChildStack().value.active.instance).onUiEvent(event = CityWeatherIntent.Refresh)
}
