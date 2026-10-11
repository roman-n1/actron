package actron.example.weather

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenMeteoApiTest {
    @Test
    fun usesExplicitUnitsAndCoordinates_andDecodesCurrentConditions() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("api.open-meteo.com", request.url.host)
            assertEquals("/v1/forecast", request.url.encodedPath)
            assertEquals("38.72", request.url.parameters["latitude"])
            assertEquals("-9.14", request.url.parameters["longitude"])
            assertEquals("celsius", request.url.parameters["temperature_unit"])
            assertEquals("kmh", request.url.parameters["wind_speed_unit"])
            assertEquals("temperature_2m,apparent_temperature,wind_speed_10m,weather_code", request.url.parameters["current"])
            respond("""{"current":{"temperature_2m":21.5,"apparent_temperature":20.0,"wind_speed_10m":15.0,"weather_code":2,"time":"2026-10-11T12:00"},"extra":true}""", HttpStatusCode.OK)
        })
        try {
            assertEquals(WeatherReading(demoCities[1], 21.5, 20.0, 15.0, 2, "2026-10-11T12:00"), OpenMeteoApi(client).load(demoCities[1]))
        } finally { client.close() }
    }

    @Test
    fun missingMeasurement_isRejected_insteadOfInventingWeatherData() = runTest {
        val client = HttpClient(MockEngine { respond("""{"current":{"temperature_2m":null}}""") })
        try { assertFailsWith<kotlinx.serialization.SerializationException> { OpenMeteoApi(client).load(demoCities.first()) } }
        finally { client.close() }
    }
}
