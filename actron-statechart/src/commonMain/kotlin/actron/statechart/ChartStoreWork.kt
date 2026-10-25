package actron.statechart

import actron.core.EnterLaunchScope
import actron.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

internal class ChartStoreWorker<C : Any, E : Event>(
    val transactor: EnterLaunchScope<ChartState<C>, E, ChartState<C>>,
    val scope: CoroutineScope,
)

internal class ChartStoreTask<C : Any, E : Event>(
    val activation: Job,
    val block: suspend ChartStoreWorker<C, E>.() -> Unit,
)

/**
 * Runs activity and timer tasks under the Store subscription, never under the transition lock.
 * The subscription is excluded from awaitIdle: live activities and timers are chart data.
 */
internal class ChartStoreWork<C : Any, E : Event> {
    private val tasks = Channel<ChartStoreTask<C, E>>(Channel.UNLIMITED)

    fun submit(task: ChartStoreTask<C, E>) {
        tasks.trySend(task)
    }

    suspend fun run(
        scope: EnterLaunchScope<ChartState<C>, E, ChartState<C>>,
        execute: suspend ChartStoreWorker<C, E>.(ChartStoreTask<C, E>) -> Unit,
    ) {
        supervisorScope {
            val worker = ChartStoreWorker(scope, this)
            for (task in tasks) {
                if (!task.activation.isActive) continue
                launch { worker.execute(task) }
            }
        }
    }
}
