@file:OptIn(InternalActronApi::class)

package actron.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** Observes inputs/state launches and closing-root completion without starting the Store. */
internal class StoreIdleWaiter<S : State, A : Action, E : Event>(
    private val inputs: StoreInputs<S, A, E>,
    private val mutex: Mutex,
    private val activeLaunches: () -> List<Job>,
    private val closingJobs: () -> List<Job>,
    private val checkOutside: suspend (String) -> Unit,
) {
    suspend fun await(timeout: Duration): StorePendingWork {
        checkOutside("awaitIdle")
        var launchesSeen = 0
        val settled =
            withTimeoutOrNull(timeout) {
                while (true) {
                    inputs.awaitPending()
                    val launches = closingJobs().ifEmpty { mutex.withLock { activeLaunches() } }
                    launchesSeen = launches.size
                    if (launches.isEmpty()) {
                        // Nothing was launched meanwhile and nothing is queued: idle. A launch that
                        // dispatched or transacted before ending queued an input, which the next round sees.
                        if (inputs.pendingCount <= 0) break else continue
                    }
                    launches.joinAll()
                }
                true
            } ?: false
        if (settled) return StorePendingWork(0, 0)
        // The report must not wait for the lock: a handler stuck under it is why the Store is not idle.
        val launches =
            if (closingJobs().isNotEmpty()) {
                // The root is a single completion barrier for state, subscription and plugin cleanup.
                1
            } else if (mutex.tryLock()) {
                try {
                    activeLaunches().size
                } finally {
                    mutex.unlock()
                }
            } else {
                launchesSeen
            }
        return StorePendingWork(inputs.pendingCount.coerceAtLeast(0), launches)
    }
}
