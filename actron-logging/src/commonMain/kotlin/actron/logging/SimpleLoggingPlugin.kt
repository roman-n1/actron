package actron.logging

import actron.core.Action
import actron.core.Event
import actron.core.Plugin
import actron.core.PluginScope
import actron.core.State

/** Adapts Store hook snapshots into lazy log messages; delivery owns execution and error routing. */
internal class SimpleLoggingPlugin<S : State, A : Action, E : Event>(
    private val delivery: LoggingDelivery,
) : Plugin<S, A, E> {
    override suspend fun onAction(
        scope: PluginScope<S, A>,
        state: S,
        action: A,
    ) {
        delivery.write(scope) { "Action: $action" }
    }

    override suspend fun onEvent(
        scope: PluginScope<S, A>,
        state: S,
        event: E,
    ) {
        delivery.write(scope) { "Event: $event" }
    }

    override suspend fun onState(
        scope: PluginScope<S, A>,
        prevState: S,
        state: S,
    ) {
        delivery.write(scope) { "State: $state <- $prevState" }
    }
}
