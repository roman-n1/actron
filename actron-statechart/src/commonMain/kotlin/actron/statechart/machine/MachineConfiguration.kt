@file:OptIn(actron.core.InternalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.AutoStartPolicy
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.InternalActronApi
import actron.core.Plugin
import actron.core.PluginExecutionPolicy
import actron.core.StateSaver
import actron.core.StoreProbe
import actron.statechart.StoreConfiguration
import kotlin.coroutines.CoroutineContext

/** Runtime options for our StateChart machine. Plugins see feature actions, never scheduler inputs. */
@ActronStoreDsl
class MachineConfiguration<C : Any, A : Action, E : Event> internal constructor(
    internal val runtime: StoreConfiguration<MachineSnapshot<C>, MachineInput<A>, E>,
    private val adaptPlugin: (Plugin<MachineSnapshot<C>, A, E>) -> Plugin<MachineSnapshot<C>, MachineInput<A>, E>,
) {
    fun coroutineContext(context: CoroutineContext) = runtime.coroutineContext(context)

    fun stateSaver(saver: StateSaver<MachineSnapshot<C>>) = runtime.stateSaver(saver)

    fun exceptionHandler(handler: ExceptionHandler) = runtime.exceptionHandler(handler)

    fun autoStartPolicy(policy: AutoStartPolicy) = runtime.autoStartPolicy(policy)

    fun pluginExecutionPolicy(policy: PluginExecutionPolicy) = runtime.pluginExecutionPolicy(policy)

    /** Dispatch from a plugin enters the same admission queue as dispatch from the UI. */
    fun plugin(
        first: Plugin<MachineSnapshot<C>, A, E>,
        vararg rest: Plugin<MachineSnapshot<C>, A, E>,
    ) = runtime.plugin(adaptPlugin(first), *rest.map(adaptPlugin).toTypedArray())

    @InternalActronApi
    fun probe(
        first: StoreProbe<MachineSnapshot<C>, MachineInput<A>, E>,
        vararg rest: StoreProbe<MachineSnapshot<C>, MachineInput<A>, E>,
    ) = runtime.probe(first, *rest)
}
