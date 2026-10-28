package actron.example.weather.feature.weather.presentation

import actron.compose.collectAsUiState
import actron.example.weather.app.WeatherMachines
import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.domain.City
import actron.example.weather.feature.hourlyforecast.presentation.HourlyForecastAction
import actron.example.weather.feature.weatheradvice.presentation.WeatherAdviceAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

interface CityWeatherComponent : BaseComponent {
    val state: StateFlow<WeatherContext>

    fun onUiEvent(event: CityWeatherIntent)

    sealed interface News {
        data object BackClicked : News
    }

    interface Factory {
        fun create(
            componentContext: ComponentContext,
            city: City,
            endpoint: BridgeEndpoint<Nothing, News>,
        ): CityWeatherComponent
    }
}

class DefaultCityWeatherComponent(
    componentContext: ComponentContext,
    private val endpoint: BridgeEndpoint<Nothing, CityWeatherComponent.News>,
    coroutineContext: CoroutineContext,
    private val machines: WeatherMachines,
) : CityWeatherComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    override val state = machines.state

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    override fun onUiEvent(event: CityWeatherIntent) {
        if (!machines.screen.state.value.isStarted) return
        when (event) {
            CityWeatherIntent.DismissAdvice -> machines.advice.dispatch(action = WeatherAdviceAction.Dismiss)
            CityWeatherIntent.RetryAdvice -> machines.advice.dispatch(action = WeatherAdviceAction.Retry)
            CityWeatherIntent.Refresh -> machines.screen.dispatch(action = WeatherScreenAction.Refresh)
            CityWeatherIntent.RetryForecast -> machines.hourly.dispatch(action = HourlyForecastAction.Retry)
            CityWeatherIntent.Back -> scope.launch { endpoint.news.emit(CityWeatherComponent.News.BackClicked) }
        }
    }

    @Composable
    override fun Render() {
        val snapshot by state.collectAsUiState()
        val parent by machines.screen.state.collectAsUiState()
        CityWeatherScreen(state = snapshot.toUiState(enabled = parent.isStarted), onIntent = ::onUiEvent)
    }
}
