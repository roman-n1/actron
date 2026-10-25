package actron.example.weather.feature.weather.data

import actron.example.weather.core.domain.City
import actron.example.weather.feature.weather.domain.ForecastHour
import actron.example.weather.feature.weather.domain.HourlyForecast
import actron.example.weather.feature.weather.domain.HourlyForecastRepository

object SampleHourlyRepository : HourlyForecastRepository {
    override suspend fun load(city: City): HourlyForecast {
        kotlinx.coroutines.delay(700)
        return HourlyForecast(city, List(6) { index -> ForecastHour("${12 + index}:00", 18.0 + index, 10.0 + index * 2, index * 10) })
    }
}
