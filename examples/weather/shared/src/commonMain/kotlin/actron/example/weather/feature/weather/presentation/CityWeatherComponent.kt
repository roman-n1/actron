package actron.example.weather.feature.weather.presentation

import actron.compose.collectAsUiState
import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.domain.City
import actron.example.weather.core.mvi.NoEvent
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineStore
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
    val state: StateFlow<MachineSnapshot<WeatherContext>>

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
    private val store: MachineStore<WeatherContext, WeatherAction, WeatherCommand, NoEvent>,
) : CityWeatherComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    override val state = store.state

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    override fun onUiEvent(event: CityWeatherIntent) {
        if (!state.value.isStarted) return
        when (event) {
            CityWeatherIntent.Refresh -> store.dispatch(WeatherAction.Refresh)
            CityWeatherIntent.RetryForecast -> store.dispatch(WeatherAction.RetryForecast)
            CityWeatherIntent.Back -> scope.launch { endpoint.news.emit(CityWeatherComponent.News.BackClicked) }
        }
    }

    @Composable
    override fun Render() {
        val snapshot by state.collectAsUiState()
        CityWeatherScreen(snapshot.context.toUiState(enabled = snapshot.isStarted), onIntent = ::onUiEvent)
    }
}
