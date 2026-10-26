package actron.example.weather.feature.weather.domain

import kotlinx.serialization.Serializable

@Serializable
enum class WeatherAdvice { Umbrella, SlipperyRoads, StrongWind, Heat, Cold, Thunderstorm }

/** Demo thresholds; precipitation codes follow https://open-meteo.com/en/docs (WMO table). */
fun weatherAdvice(reading: WeatherReading): List<WeatherAdvice> =
    buildList(builderAction = {
        when (reading.code) {
            56, 57, 66, 67 -> add(element = WeatherAdvice.SlipperyRoads)
            51, 53, 55, 61, 63, 65, 80, 81, 82 -> add(element = WeatherAdvice.Umbrella)
            95, 96, 97, 99 -> add(element = WeatherAdvice.Thunderstorm)
        }
        if (reading.wind >= 40.0) add(element = WeatherAdvice.StrongWind)
        if (reading.temperature >= 30.0) add(element = WeatherAdvice.Heat)
        if (reading.temperature <= -10.0) add(element = WeatherAdvice.Cold)
    })
