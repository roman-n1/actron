package actron.example.weather.feature.weather.presentation

import actron.compose.collectAsUiState
import actron.decompose.observeEvent
import actron.example.weather.app.assembly.WeatherMachines
import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.domain.City
import actron.statechart.machine.UiActionMapper
import actron.statechart.machine.combineStates
import actron.statechart.machine.present
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
    val state: StateFlow<CityWeatherUiState>

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
    private val presentation =
        machines.screen.present(
            uiState = combineStates(machines.state, machines.screen.state) { weather, screen -> weather.toUiState(enabled = screen.isStarted) },
            actionMapper = UiActionMapper<CityWeatherIntent, WeatherScreenState, WeatherScreenAction> { event, _, dispatch -> dispatch(event.toAction()) },
        )
    override val state: StateFlow<CityWeatherUiState> = presentation.state

    init {
        lifecycle.doOnDestroy { scope.cancel() }
        machines.screen.observeEvent(lifecycle) { event ->
            if (event == WeatherScreenEvent.BackRequested) scope.launch { endpoint.news.emit(CityWeatherComponent.News.BackClicked) }
        }
    }

    override fun onUiEvent(event: CityWeatherIntent) = presentation.onUiEvent(event)

    @Composable
    override fun Render() {
        val uiState by state.collectAsUiState()
        CityWeatherScreen(uiState = uiState, onIntent = ::onUiEvent)
    }
}
