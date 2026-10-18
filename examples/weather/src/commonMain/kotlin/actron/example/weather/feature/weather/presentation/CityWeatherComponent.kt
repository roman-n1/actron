package actron.example.weather.feature.weather.presentation

import actron.compose.collectAsUiState
import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.BridgeEndpoint
import actron.example.weather.core.component.MachineInstance
import actron.example.weather.core.debug.WeatherHistory
import actron.example.weather.core.domain.City
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.statechart.machine.MachineSnapshot
import actron.timetravel.historyFields
import androidx.compose.runtime.Composable
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
    city: City,
    private val endpoint: BridgeEndpoint<Nothing, CityWeatherComponent.News>,
    private val repository: WeatherRepository,
    private val hourlyRepository: HourlyForecastRepository,
    coroutineContext: CoroutineContext,
    history: WeatherHistory = WeatherHistory(),
) : CityWeatherComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    private val machine =
        instanceKeeper.getOrCreate {
            val checkpoint = history.weatherCheckpoint(city)
            MachineInstance(
                CityWeatherMachine.create(),
                WeatherContext(city),
                cityWeatherHandler(repository, hourlyRepository),
                coroutineContext,
                history,
                checkpoint = checkpoint,
                describe = { historyFields(Json.encodeToJsonElement(WeatherContext.serializer(), it)) },
                update = history::updateWeather,
            ).also { if (!checkpoint.snapshot.isStarted) it.store.dispatch(WeatherAction.Refresh) }
        }
    override val state = machine.store.state

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    override fun onUiEvent(event: CityWeatherIntent) {
        if (!state.value.isStarted) return
        when (event) {
            CityWeatherIntent.Refresh -> machine.store.dispatch(WeatherAction.Refresh)
            CityWeatherIntent.RetryForecast -> machine.store.dispatch(WeatherAction.RetryForecast)
            CityWeatherIntent.Back -> scope.launch { endpoint.news.emit(CityWeatherComponent.News.BackClicked) }
        }
    }

    @Composable
    override fun Render() {
        val snapshot by state.collectAsUiState()
        CityWeatherScreen(snapshot.context.toUiState(snapshot.isStarted), onIntent = ::onUiEvent)
    }
}

class DefaultCityWeatherComponentFactory(
    private val repository: WeatherRepository,
    private val hourlyRepository: HourlyForecastRepository,
    private val coroutineContext: CoroutineContext,
    private val history: WeatherHistory = WeatherHistory(),
) : CityWeatherComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        city: City,
        endpoint: BridgeEndpoint<Nothing, CityWeatherComponent.News>,
    ): CityWeatherComponent = DefaultCityWeatherComponent(componentContext, city, endpoint, repository, hourlyRepository, coroutineContext, history)
}
