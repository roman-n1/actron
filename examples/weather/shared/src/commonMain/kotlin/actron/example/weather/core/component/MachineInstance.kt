package actron.example.weather.core.component

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.MachineStore
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/** Owns a retained Store and its work; configuration and diagnostics belong to its creator. */
class MachineInstance<C : Any, A : Action, CMD : Any, E : Event>(
    context: CoroutineContext,
    createStore: (CoroutineScope) -> MachineStore<C, A, CMD, E>,
) : InstanceKeeper.Instance {
    private val scope = CoroutineScope(context + SupervisorJob())
    val store = createStore(scope)

    init {
        store.start()
    }

    override fun onDestroy() {
        store.close()
        scope.cancel()
    }
}
