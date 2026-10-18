package actron.example.weather.feature.cities.presentation

import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.component.MachineInstance
import actron.example.weather.core.debug.WeatherHistory
import actron.example.weather.core.domain.City
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.cities.domain.SearchCitiesUseCase
import actron.statechart.machine.MachineSnapshot
import actron.timetravel.historyFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

interface CitySearchComponent : BaseComponent {
    val state: StateFlow<MachineSnapshot<CitySearchState>>

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
    private val repository: CitySearchRepository,
    coroutineContext: CoroutineContext,
    history: WeatherHistory = WeatherHistory(),
) : CitySearchComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    private val machine =
        instanceKeeper.getOrCreate {
            MachineInstance(
                CitySearchMachine.create(),
                CitySearchState(),
                citySearchHandler(SearchCitiesUseCase(repository)),
                coroutineContext,
                history,
                checkpoint = history.searchCheckpoint(),
                describe = { historyFields(Json.encodeToJsonElement(CitySearchState.serializer(), it)) },
                update = history::updateSearch,
            )
        }
    override val state = machine.store.state

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    override fun onUiEvent(event: CitySearchIntent) {
        if (!state.value.isStarted) return
        when (event) {
            is CitySearchIntent.QueryChanged -> machine.store.dispatch(CitySearchAction.ChangeQuery(event.query))
            CitySearchIntent.Retry -> machine.store.dispatch(CitySearchAction.ChangeQuery(state.value.context.query))
            is CitySearchIntent.SelectCity -> scope.launch { endpoint.news.emit(CitySearchComponent.News.CitySelected(event.city)) }
        }
    }

    @Composable
    override fun Render() {
        val snapshot by state.collectAsState()
        CitySearchScreen(snapshot.context.toUiState(snapshot.isStarted), onIntent = ::onUiEvent)
    }
}

class DefaultCitySearchComponentFactory(
    private val repository: CitySearchRepository,
    private val coroutineContext: CoroutineContext,
    private val history: WeatherHistory = WeatherHistory(),
) : CitySearchComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        endpoint: BridgeEndpoint<Nothing, CitySearchComponent.News>,
    ): CitySearchComponent = DefaultCitySearchComponent(componentContext, endpoint, repository, coroutineContext, history)
}
