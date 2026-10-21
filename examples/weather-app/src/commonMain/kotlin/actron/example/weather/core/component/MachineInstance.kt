@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.weather.core.component

import actron.core.Action
import actron.core.Event
import actron.example.weather.core.session.WeatherSession
import actron.statechart.machine.*
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineStore
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.CoroutineContext

/** Decompose retains the executor; Actron owns all screen state and transitions. */
class MachineInstance<C : Any, A : Action, CMD : Any, E : Event>(
    machine: Machine<C, A, CMD, E>,
    initial: C,
    handler: CommandHandler<CMD, A>,
    context: CoroutineContext,
    history: WeatherSession = WeatherSession(),
    clock: MachineClock = history.clock,
    checkpoint: ExecutorCheckpoint<C, CMD> = ExecutorCheckpoint.initial(machine.initialSnapshot(initial)),
    describe: (C) -> JsonElement = { JsonPrimitive(it.toString()) },
    update: (ExecutorCheckpoint<C, CMD>) -> Unit = {},
) : InstanceKeeper.Instance {
    private val scope = CoroutineScope(context + SupervisorJob())

    // Attach before start/resume so even the first decision appears in history.
    private val observer = history.observe(machine, checkpoint, describe, update)
    val store =
        if (checkpoint.snapshot.isStarted) {
            // Restore executor work/timers as well as context; do not re-enter completed states.
            resumeMachineStore(machine, checkpoint, handler, scope, context, clock, listOf(observer))
        } else {
            MachineStore(machine, checkpoint.snapshot.context, handler, scope, coroutineContext = context, observers = listOf(observer), clock = clock)
        }

    init {
        store.start()
    }

    override fun onDestroy() {
        // Decompose owns this lifetime; closing it prevents obsolete child work from continuing.
        store.close()
        scope.cancel()
    }
}
