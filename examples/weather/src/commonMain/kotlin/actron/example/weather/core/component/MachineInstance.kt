package actron.example.weather.core.component

import actron.core.Action
import actron.core.Event
import actron.example.weather.core.debug.WeatherHistory
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineStore
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/** Decompose retains the executor; Actron owns all screen state and transitions. */
class MachineInstance<C : Any, A : Action, CMD : Any, E : Event>(
    machine: Machine<C, A, CMD, E>,
    initial: C,
    handler: CommandHandler<CMD, A>,
    context: CoroutineContext,
    history: WeatherHistory = WeatherHistory(),
) : InstanceKeeper.Instance {
    private val scope = CoroutineScope(context + SupervisorJob())
    val store = MachineStore(machine, initial, handler, scope, coroutineContext = context, observers = listOf(history.observer(machine, initial)))

    init {
        store.start()
    }

    override fun onDestroy() {
        store.close()
        scope.cancel()
    }
}
