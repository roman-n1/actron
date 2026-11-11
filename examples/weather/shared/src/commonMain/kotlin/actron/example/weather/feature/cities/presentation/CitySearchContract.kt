package actron.example.weather.feature.cities.presentation

sealed interface CitySearchIntent {
    data class QueryChanged(
        val query: String,
    ) : CitySearchIntent

    data object Retry : CitySearchIntent

    data class SelectCity(
        val name: String,
        val latitude: Double,
        val longitude: Double,
    ) : CitySearchIntent
}

enum class CitySearchControls(
    val enabled: Boolean,
) {
    Interactive(true),
    ReadOnly(false),
    ;

    val readOnly: Boolean = !enabled
}
