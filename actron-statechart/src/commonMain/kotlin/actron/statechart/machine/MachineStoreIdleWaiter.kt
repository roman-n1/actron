@file:OptIn(actron.core.InternalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.StoreInternalApi
import actron.core.StorePendingWork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** Joins core processing with the executor barrier, but not commands/timers retained as data. */
internal class MachineStoreIdleWaiter<S : State, A : Action, E : Event>(
    private val core: StoreInternalApi<S, MachineInput<A>, E>,
    private val queue: MachineStoreQueue<A>,
    private val checkpointActive: suspend () -> Boolean,
) {
    suspend fun await(timeout: Duration): StorePendingWork {
        // The same-store guard must run even for a zero-time query or a frozen store.
        core.awaitIdle(Duration.ZERO)
        val settled =
            withTimeoutOrNull(timeout) {
                settle()
                true
            } ?: false
        if (settled) return StorePendingWork(0, 0)
        val pending = core.awaitIdle(Duration.ZERO)
        return StorePendingWork(pending.inputs + queue.pendingHandoffs, pending.launches)
    }

    private suspend fun settle() {
        while (true) {
            awaitHandoffs()
            core.awaitIdle(Duration.INFINITE)
            if (!checkpointActive()) return
            if (queue.pendingHandoffs == 0 && core.awaitIdle(Duration.ZERO).isIdle) return
        }
    }

    private suspend fun awaitHandoffs() {
        while (true) {
            val observed = queue.handoffChanges.value
            if (queue.pendingHandoffs == 0) return
            queue.handoffChanges.first { it != observed }
        }
    }
}
