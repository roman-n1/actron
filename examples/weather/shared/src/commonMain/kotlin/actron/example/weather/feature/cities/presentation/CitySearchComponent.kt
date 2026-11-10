package actron.example.weather.feature.cities.presentation

import actron.compose.collectAsUiState
import actron.decompose.observeEvent
import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.domain.City
import actron.example.weather.feature.cities.presentation.CitySearchEvent
import actron.statechart.machine.MachineStore
import actron.statechart.machine.UiActionMapper
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

interface CitySearchComponent : BaseComponent {
    val state: StateFlow<CitySearchUiState>

    fun onUiEvent(event: CitySearchIntent)

    sealed interface News {
        data class CitySelected(
            val city: City,
        ) : News
    }

    interface Factory {
        fun create(
            componentContext: ComponentContext,
            endpoint: BridgeEndpoint<Nothing, News>,
        ): CitySearchComponent
    }
}

class DefaultCitySearchComponent(
    componentContext: ComponentContext,
    private val endpoint: BridgeEndpoint<Nothing, CitySearchComponent.News>,
    coroutineContext: CoroutineContext,
    private val store: MachineStore<CitySearchState, CitySearchAction, Unit, CitySearchEvent>,
    private val settingsAction: @Composable () -> Unit,
) : CitySearchComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    private val presentation =
        store.present(
            stateMapper = { it.toUiState() },
            actionMapper = UiActionMapper<CitySearchIntent, CitySearchState, CitySearchAction> { event, state, dispatch -> event.toAction(state, dispatch) },
        )
    override val state: StateFlow<CitySearchUiState> = presentation.state

    init {
        lifecycle.doOnDestroy { scope.cancel() }
        store.observeEvent(lifecycle) { event ->
            when (event) {
                is CitySearchEvent.CitySelected -> scope.launch { endpoint.news.emit(CitySearchComponent.News.CitySelected(event.city)) }
            }
        }
    }

    override fun onUiEvent(event: CitySearchIntent) = presentation.onUiEvent(event)

    @Composable
    override fun Render() {
        val uiState by state.collectAsUiState()
        CitySearchScreen(uiState = uiState, onIntent = ::onUiEvent, settingsAction = settingsAction)
    }
}
