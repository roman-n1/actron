package actron.example.weather.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

fun weatherHttpClient(): HttpClient = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 7_000 } }
