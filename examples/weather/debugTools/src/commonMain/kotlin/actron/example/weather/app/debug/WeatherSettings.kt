package actron.example.weather.app.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The application shell owns settings visibility; feature screens receive only an action. */
class WeatherSettings {
    var isOpen: Boolean by mutableStateOf(false)
        private set

    fun open() {
        isOpen = true
    }

    fun close() {
        isOpen = false
    }
}
