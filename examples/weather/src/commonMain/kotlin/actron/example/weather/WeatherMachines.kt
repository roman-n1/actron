package actron.example.weather

import actron.core.Action
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.EffectKey
import actron.statechart.GuardKey
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.LaneId
import actron.statechart.machine.Machine
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class City(val name: String, val latitude: Double, val longitude: Double)

val demoCities = listOf(City("Novosibirsk", 55.03, 82.92), City("Lisbon", 38.72, -9.14), City("Tokyo", 35.68, 139.69), City("Reykjavik", 64.15, -21.94))

@Serializable
data class WeatherReading(val city: City, val temperature: Double, val feelsLike: Double, val wind: Double, val code: Int, val time: String)

@Serializable data class LocationContext(val city: City = demoCities.first())
@Serializable data class ChooseCity(val city: City) : Action
@Serializable data class CityChosen(val city: City) : Event
@Serializable data object NoCommand
@Serializable data object NoEvent : Event

@Serializable
data class WeatherContext(val city: City = demoCities.first(), val readings: List<WeatherReading> = emptyList(), val failures: List<String> = emptyList())

@Serializable
sealed interface WeatherAction : Action {
    @Serializable data class Select(val city: City) : WeatherAction
    @Serializable data object Refresh : WeatherAction
    @Serializable data class Loaded(val reading: WeatherReading) : WeatherAction
    @Serializable data class Unavailable(val reason: String) : WeatherAction
}

@Serializable data class FetchWeather(val city: City)
@Serializable data class WeatherUpdated(val reading: WeatherReading) : Event
@Serializable data class AdviceContext(val city: City = demoCities.first(), val reports: List<String> = emptyList())

@Serializable
sealed interface AdviceAction : Action {
    @Serializable data class Select(val city: City) : AdviceAction
    @Serializable data class Evaluate(val reading: WeatherReading) : AdviceAction
}

/** Three independent executors joined by typed effects; all definitions are pure shared KMP code. */
object WeatherMachines {
    val idle = StateId("Idle")
    val loading = StateId("Loading")
    val ready = StateId("Ready")
    val failed = StateId("Failed")
    val timedOut = StateId("TimedOut")
    private val root = StateId("Weather")

    fun location(): Machine<LocationContext, ChooseCity, NoCommand, CityChosen> {
        val root = StateId("Location")
        val selected = StateId("Selected")
        return Machine(DefinitionId("weather-location"), DefinitionVersion("1"), StateChartDefinition(root,
            listOf(CompoundState(root, initial = selected), AtomicState(selected, root)),
            listOf(Transition(selected, selected, ActionMatcher.of<ChooseCity>("Choose city"), effect = EffectKey("choose"))),
        )) {
            effect("choose") { _, action -> LocationContext((action as ChooseCity).city) }
            onEnter(selected) { event(CityChosen(context.city)) }
        }
    }

    fun weather(): Machine<WeatherContext, WeatherAction, FetchWeather, WeatherUpdated> = Machine(
        DefinitionId("weather-loader"), DefinitionVersion("1"),
        StateChartDefinition(root, listOf(CompoundState(root, initial = idle)) + listOf(idle, loading, ready, failed, timedOut).map { AtomicState(it, root) },
            buildList {
                for (from in listOf(idle, loading, ready, failed, timedOut)) {
                    add(Transition(from, loading, ActionMatcher.of<WeatherAction.Select>("Select city"), effect = EffectKey("select")))
                    add(Transition(from, loading, ActionMatcher.of<WeatherAction.Refresh>("Refresh")))
                }
                add(Transition(loading, ready, ActionMatcher.of<WeatherAction.Loaded>("Weather loaded"), guard = GuardKey("same-city"), effect = EffectKey("loaded")))
                add(Transition(loading, failed, ActionMatcher.of<WeatherAction.Unavailable>("Load failed"), effect = EffectKey("failed")))
                add(Transition(loading, timedOut, Trigger.After(8.seconds)))
            }),
    ) {
        effect("select") { context, action -> context.copy(city = (action as WeatherAction.Select).city) }
        effect("loaded") { context, action -> context.copy(readings = (context.readings + (action as WeatherAction.Loaded).reading).takeLast(20)) }
        effect("failed") { context, action -> context.copy(failures = (context.failures + (action as WeatherAction.Unavailable).reason).takeLast(20)) }
        guard("same-city", "The result belongs to a city that is no longer selected") { snapshot, action -> (action as WeatherAction.Loaded).reading.city == snapshot.context.city }
        onEnter(loading) { command(FetchWeather(context.city), LaneId("forecast"), ConcurrencyPolicy.Latest) }
        onEnter(ready) { event(WeatherUpdated(context.readings.last())) }
        invariant("Ready has weather for the selected city") { snapshot ->
            !snapshot.isActive(ready) || snapshot.context.readings.isNotEmpty() && snapshot.context.readings.last().city == snapshot.context.city
        }
    }

    fun advice(): Machine<AdviceContext, AdviceAction, NoCommand, NoEvent> {
        val root = StateId("Advice")
        val waiting = StateId("Waiting")
        val calm = StateId("Comfortable")
        val windy = StateId("Windy")
        val hot = StateId("Hot")
        val states = listOf(waiting, calm, windy, hot)
        return Machine(DefinitionId("weather-advice"), DefinitionVersion("1"), StateChartDefinition(root,
            listOf(CompoundState(root, initial = waiting)) + states.map { AtomicState(it, root) },
            buildList {
                for (from in states) {
                    add(Transition(from, waiting, ActionMatcher.of<AdviceAction.Select>("Select city"), effect = EffectKey("select")))
                    add(Transition(from, windy, ActionMatcher.of<AdviceAction.Evaluate>("Evaluate weather"), guard = GuardKey("windy"), effect = EffectKey("windy")))
                    add(Transition(from, hot, ActionMatcher.of<AdviceAction.Evaluate>("Evaluate weather"), guard = GuardKey("hot"), effect = EffectKey("hot")))
                    add(Transition(from, calm, ActionMatcher.of<AdviceAction.Evaluate>("Evaluate weather"), guard = GuardKey("calm"), effect = EffectKey("calm")))
                }
            },
        )) {
            effect("select") { context, action -> context.copy(city = (action as AdviceAction.Select).city) }
            for ((key, report) in listOf("windy" to "Strong wind: keep your walk short", "hot" to "Hot weather: take water and find shade", "calm" to "A good moment for a walk")) {
                effect(key) { context, action -> context.copy(city = (action as AdviceAction.Evaluate).reading.city, reports = (context.reports + report).takeLast(20)) }
            }
            guard("windy") { snapshot, action -> val reading = (action as AdviceAction.Evaluate).reading; reading.city == snapshot.context.city && reading.wind >= 30 }
            guard("hot") { snapshot, action -> val reading = (action as AdviceAction.Evaluate).reading; reading.city == snapshot.context.city && reading.wind < 30 && reading.temperature >= 30 }
            guard("calm") { snapshot, action -> val reading = (action as AdviceAction.Evaluate).reading; reading.city == snapshot.context.city && reading.wind < 30 && reading.temperature < 30 }
        }
    }
}
