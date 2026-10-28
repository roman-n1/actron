package actron.example.weather

import actron.example.weather.core.fixtures.demoCities
import actron.example.weather.feature.weather.domain.WeatherAdvice
import actron.example.weather.feature.weather.domain.WeatherReading
import actron.example.weather.feature.weather.domain.weatherAdvice
import kotlin.test.Test
import kotlin.test.assertEquals

class WeatherAdviceTest {
    private fun reading(
        code: Int = 0,
        temperature: Double = 15.0,
        wind: Double = 5.0,
    ) = WeatherReading(city = demoCities.first(), temperature = temperature, feelsLike = temperature, wind = wind, code = code, time = "12:00")

    @Test
    fun rainSuggestsUmbrellaAndFreezingPrecipitationWarnsAboutSlipperyRoads() {
        for (code in listOf(51, 53, 55, 61, 63, 65, 80, 81, 82)) {
            assertEquals(expected = listOf(WeatherAdvice.Umbrella), actual = weatherAdvice(reading = reading(code = code)))
        }
        for (code in listOf(56, 57, 66, 67)) {
            assertEquals(expected = listOf(WeatherAdvice.SlipperyRoads), actual = weatherAdvice(reading = reading(code = code)))
        }
        for (code in listOf(95, 96, 97, 99)) {
            assertEquals(expected = listOf(WeatherAdvice.Thunderstorm), actual = weatherAdvice(reading = reading(code = code)))
        }
        // A low temperature alone says nothing about ice on a road.
        assertEquals(expected = listOf(WeatherAdvice.Cold), actual = weatherAdvice(reading = reading(temperature = -10.0)))
    }

    @Test
    fun demoThresholdsAreInclusiveAndAdviceCanCombineSeveralConditions() {
        assertEquals(expected = emptyList(), actual = weatherAdvice(reading = reading(temperature = 29.9, wind = 39.9)))
        assertEquals(expected = listOf(WeatherAdvice.StrongWind, WeatherAdvice.Heat), actual = weatherAdvice(reading = reading(temperature = 30.0, wind = 40.0)))
        assertEquals(expected = listOf(WeatherAdvice.SlipperyRoads, WeatherAdvice.StrongWind, WeatherAdvice.Cold), actual = weatherAdvice(reading = reading(code = 67, temperature = -10.0, wind = 40.0)))
        assertEquals(expected = emptyList(), actual = weatherAdvice(reading = reading(code = 999)))
    }
}
