@file:OptIn(actron.core.InternalActronApi::class)

package actron.statechart

import actron.core.Action
import actron.core.Event

/** Executable registrations are copied once; later builder edits affect only later builds. */
internal class ChartStoreBindings<C : Any, A : Action, E : Event>(
    builder: StateChartStoreBuilder<C, A, E>,
) {
    val maxMicrosteps = builder.maxMicrosteps
    val guards = builder.guards.toMap()
    val effects = builder.effects.toMap()
    val enterHooks = builder.enterHooks.mapValues { it.value.toList() }
    val exitHooks = builder.exitHooks.mapValues { it.value.toList() }
    val activities = builder.activities.mapValues { it.value.toList() }
    val storeBlocks = builder.storeBlocks.toList()

    fun validate(definition: StateChartDefinition) {
        val missing = buildList { definition.transitions.forEach { it.effect.withLabel { add(it) } } }.distinct().filter { it !in effects }
        require(missing.isEmpty()) { "[Actron] Missing effect implementations: ${missing.joinToString()}" }
        val declared =
            definition.states
                .filter { it !is HistoryState }
                .map { it.id }
                .toSet()
        val undeclared = (enterHooks.keys + exitHooks.keys + activities.keys).filter { it !in declared }
        require(undeclared.isEmpty()) { "[Actron] Hooks for undeclared states: ${undeclared.joinToString()}" }
        // Store steps must not leave the declared graph, even though the pure runtime allows it.
        val undeclaredStates = definition.endpointIssues()
        require(undeclaredStates.isEmpty()) { "[Actron] Chart refers to undeclared or duplicate states: ${undeclaredStates.joinToString()}" }
        val loops = definition.instantTimerCycles()
        require(loops.isEmpty()) {
            "[Actron] Timers without a positive delay restart each other forever: " +
                loops.joinToString { cycle -> cycle.joinToString(" -> ") { "${it.source.value} --after ${it.timerDelay()}--> ${it.target.value}" } }
        }
    }
}
