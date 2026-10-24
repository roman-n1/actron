@file:OptIn(InternalActronApi::class)

package actron.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** Owns dispatch ordering and cancellation; processing still uses the Store's transition mutex. */
internal class StoreDispatchQueue(
    private val rootScope: () -> CoroutineScope,
    private val mutex: Mutex,
    private val discard: (InputId, DiscardReason) -> Unit,
) {
    private val dispatchScope by lazy {
        val parent = rootScope()
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }

    private sealed interface DispatchPhase {
        fun owns(job: Job): Boolean

        data object Idle : DispatchPhase {
            override fun owns(job: Job): Boolean = false
        }

        class Running(
            val job: Job,
        ) : DispatchPhase {
            override fun owns(job: Job): Boolean = this.job === job
        }
    }

    private var activeDispatch: DispatchPhase = DispatchPhase.Idle

    // The completion signal of the most recent dispatch. Each dispatch waits for its predecessor
    // before it competes for `mutex`, so actions are processed in dispatch order even on a
    // multi-threaded dispatcher, where freshly launched coroutines would otherwise reach the lock
    // in arbitrary order. MutableStateFlow is used as a thread-safe atomic reference (Job equality
    // is identity).
    private val lastDispatchDone = MutableStateFlow<Job>(Job().apply { complete() })

    fun enqueue(
        input: InputId,
        context: CoroutineContext,
        process: suspend () -> Unit,
    ): Job {
        val previousDone = CompletableDeferred<Job>()
        // Completed when this dispatch's work under the lock is over (or the dispatch never ran),
        // not when its coroutine ends: a handler that leaves a child coroutine behind in its own
        // Job must not hold up every later dispatch.
        val done = Job()
        val queued = QueuedStoreInput(input, discard)
        val job =
            dispatchScope.launch(context) {
                // Keeps dispatch order: a cancelled or failed predecessor completes too, so this never
                // waits forever. Waiting here costs nothing extra, as the lock serializes dispatches anyway.
                previousDone.await().join()
                mutex.withLock {
                    queued.locked = true
                    val dispatchJob = coroutineContext.job
                    activeDispatch = DispatchPhase.Running(dispatchJob)
                    try {
                        process()
                    } finally {
                        if (activeDispatch.owns(dispatchJob)) {
                            activeDispatch = DispatchPhase.Idle
                        }
                        done.complete()
                    }
                }
            }
        job.invokeOnCompletion { cause ->
            done.complete()
            if (cause is CancellationException) queued.onCancellation(cause)
        }
        previousDone.complete(lastDispatchDone.getAndUpdate { done })
        return job
    }

    fun clear(reason: DiscardReason) {
        val dispatchScopeJob = dispatchScope.coroutineContext[Job] ?: return
        val pending = dispatchScopeJob.children.filter { !activeDispatch.owns(it) && it.isActive }.toList()
        if (pending.isEmpty()) return
        // One cause for the whole clear: each queued dispatch finds it in its cancellation and
        // traces why it was discarded.
        val cause = PendingDispatchCleared(reason)
        pending.forEach { it.cancel(cause) }
    }
}
