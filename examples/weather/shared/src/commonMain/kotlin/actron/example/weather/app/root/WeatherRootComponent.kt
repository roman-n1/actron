@file:OptIn(com.arkivanov.decompose.DelicateDecomposeApi::class)

package actron.example.weather.app.root

import actron.example.weather.core.component.BaseComponent
import actron.example.weather.core.component.ParentBridge
import actron.example.weather.core.component.StackComponent
import actron.example.weather.core.component.toStateFlow
import actron.example.weather.core.domain.City
import actron.example.weather.feature.cities.presentation.CitySearchComponent
import actron.example.weather.feature.weather.presentation.CityWeatherComponent
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

class WeatherRootComponent(
    componentContext: ComponentContext,
    uiContext: CoroutineContext,
    private val searchFactory: CitySearchComponent.Factory,
    private val weatherFactory: CityWeatherComponent.Factory,
) : StackComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(uiContext + SupervisorJob())

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
        lifecycle.doOnDestroy {
            scope.cancel()
        }
        searchBridge.subscribe(scope) { news ->
            if (stack.value.active.configuration is ChildConfiguration.Search) {
                when (news) {
                    is CitySearchComponent.News.CitySelected -> {
                        navigation.push(ChildConfiguration.Weather(news.city))
                    }
                }
            }
        }
        weatherBridge.subscribe(scope) { news ->
            if (stack.value.active.configuration is ChildConfiguration.Weather) {
                when (news) {
                    CityWeatherComponent.News.BackClicked -> {
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

    override fun getChildStack(): StateFlow<ChildStack<*, BaseComponent>> = stackFlow

    // Preserve previous Decompose route names and branch keys when reading saved navigation.
    @Serializable
    @SerialName("actron.example.weather.app.DefaultWeatherRootComponent.ChildConfiguration")
    private sealed interface ChildConfiguration {
        @Serializable
        @SerialName("actron.example.weather.app.DefaultWeatherRootComponent.ChildConfiguration.Search")
        data class Search(
            val branch: Long = 0,
        ) : ChildConfiguration

        @Serializable
        @SerialName("actron.example.weather.app.DefaultWeatherRootComponent.ChildConfiguration.Weather")
        data class Weather(
            val city: City,
            val branch: Long = 0,
        ) : ChildConfiguration
    }
}
