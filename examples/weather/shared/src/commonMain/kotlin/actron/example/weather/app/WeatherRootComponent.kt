@file:OptIn(com.arkivanov.decompose.DelicateDecomposeApi::class)

package actron.example.weather.app

import actron.example.weather.app.session.WeatherFrame
import actron.example.weather.app.session.WeatherRoute
import actron.example.weather.app.session.WeatherSavedState
import actron.example.weather.app.session.WeatherSavedStateCodec
import actron.example.weather.app.session.WeatherSession
import actron.example.weather.app.session.retainedWeatherSession
import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.ParentBridge
import actron.example.weather.core.component.StackComponent
import actron.example.weather.core.component.toStateFlow
import actron.example.weather.core.domain.City
import actron.example.weather.feature.cities.domain.CitySearchRepository
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.weather.domain.HourlyForecastRepository
import actron.example.weather.feature.weather.domain.WeatherRepository
import actron.example.weather.feature.weather.presentation.CityWeatherComponent
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.CoroutineContext

interface WeatherRootComponent : StackComponent

class DefaultWeatherRootComponent(
    componentContext: ComponentContext,
    api: WeatherRepository,
    citySearch: CitySearchRepository,
    hourlyApi: HourlyForecastRepository,
    coroutineContext: CoroutineContext = Dispatchers.Main,
    private val history: WeatherSession = retainedWeatherSession(componentContext),
    private val settings: WeatherSettings = WeatherSettings(),
    private val searchFactory: CitySearchComponent.Factory = DefaultCitySearchComponentFactory(citySearch, { history.SettingsAction(settings::open) }, coroutineContext, history),
    private val weatherFactory: CityWeatherComponent.Factory = DefaultCityWeatherComponentFactory(api, hourlyApi, coroutineContext, history),
) : WeatherRootComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())

    private val searchBridge = ParentBridge<Nothing, CitySearchComponent.News>()
    private val weatherBridge = ParentBridge<Nothing, CityWeatherComponent.News>()

    // Navigation is a host concern, independent of the two Actron machine definitions.
    private val navigation = StackNavigation<ChildConfiguration>()
    private val stack =
        childStack(
            source = navigation,
            serializer = ChildConfiguration.serializer(),
            initialConfiguration = ChildConfiguration.Search(),
            handleBackButton = true,
            childFactory = ::createChild,
        )
    private val stackFlow = stack.toStateFlow()

    init {
        stateKeeper.register("weather-checkpoint", WeatherSavedState.serializer()) { WeatherSavedStateCodec.encode(history) }
        val navigationObserver =
            stack.subscribe { value ->
                if (value.active.configuration is ChildConfiguration.Search && history.frame.route is WeatherRoute.Weather) history.back()
            }
        lifecycle.doOnDestroy {
            stateKeeper.unregister("weather-checkpoint")
            navigationObserver.cancel()
            scope.cancel()
        }
        searchBridge.subscribe(scope) { news ->
            if (stack.value.active.configuration is ChildConfiguration.Search) {
                when (news) {
                    is CitySearchComponent.News.CitySelected -> {
                        history.openCity(news.city)
                        navigation.push(ChildConfiguration.Weather(news.city, history.branch))
                    }
                }
            }
        }
        weatherBridge.subscribe(scope) { news ->
            if (stack.value.active.configuration is ChildConfiguration.Weather) {
                when (news) {
                    CityWeatherComponent.News.BackClicked -> {
                        history.back()
                        navigation.pop()
                    }
                }
            }
        }
    }

    private fun createChild(
        configuration: ChildConfiguration,
        context: ComponentContext,
    ): BaseComponent =
        when (configuration) {
            is ChildConfiguration.Search -> searchFactory.create(context, searchBridge.endpoint)
            is ChildConfiguration.Weather -> weatherFactory.create(context, configuration.city, weatherBridge.endpoint)
        }

    @Composable
    override fun Render() {
        history.Render(settings, ::continueFrom) { super<WeatherRootComponent>.Render() }
    }

    fun continueFrom(frame: WeatherFrame) {
        // New branch keys replace retained children; their MachineInstances resume saved checkpoints.
        history.beginBranch(frame)
        when (val route = frame.route) {
            WeatherRoute.Search -> navigation.replaceAll(ChildConfiguration.Search(history.branch))
            is WeatherRoute.Weather -> navigation.replaceAll(ChildConfiguration.Search(history.branch), ChildConfiguration.Weather(route.checkpoint.snapshot.context.city, history.branch))
        }
    }

    override fun getChildStack(): StateFlow<ChildStack<*, BaseComponent>> = stackFlow

    @Serializable
    private sealed interface ChildConfiguration {
        @Serializable
        data class Search(
            val branch: Long = 0,
        ) : ChildConfiguration

        @Serializable
        data class Weather(
            val city: City,
            val branch: Long = 0,
        ) : ChildConfiguration
    }
}
