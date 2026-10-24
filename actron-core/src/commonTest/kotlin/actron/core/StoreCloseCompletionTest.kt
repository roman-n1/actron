@file:OptIn(InternalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StoreCloseCompletionTest {
    private data object Snapshot : State

    private enum class Owner { Launch, Subscription, Plugin }

    @Test
    fun closeWaitsForStateLaunchCleanup() = assertClosingCompletion(Owner.Launch)

    @Test
    fun closeWaitsForSubscriptionCleanup() = assertClosingCompletion(Owner.Subscription)

    @Test
    fun closeWaitsForPluginLaunchCleanup() = assertClosingCompletion(Owner.Plugin)

    @Test
    fun parentCancellationAlsoWaitsForCleanup() = assertClosingCompletion(Owner.Launch, cancelParent = true)

    private fun assertClosingCompletion(
        owner: Owner,
        cancelParent: Boolean = false,
    ) = runTest {
        val parent = Job()
        val started = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        val traces = mutableListOf<StoreTrace<Snapshot, Nothing, Nothing>>()
        val work: suspend () -> Unit = {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupEntered.complete(Unit)
                    cleanupRelease.await()
                }
            }
        }
        val store =
            Store<Snapshot, Nothing, Nothing>(Snapshot, StandardTestDispatcher(testScheduler) + parent) {
                probe(StoreProbe { traces += it })
                if (owner == Owner.Plugin) plugin(Plugin(onStart = { launch { work() } }))
                state<Snapshot> {
                    enter {
                        when (owner) {
                            Owner.Launch -> launch { work() }
                            Owner.Subscription -> subscribe { work() }
                            Owner.Plugin -> Unit
                        }
                    }
                }
            }
        try {
            store.startAndAwaitForTest()
            started.await()
            if (cancelParent) parent.cancel() else store.close()
            cleanupEntered.await()
            val internal = store as StoreInternalApi<Snapshot, Nothing, Nothing>
            val pending = internal.awaitIdle(1.seconds)
            assertFalse(pending.isIdle)
            assertEquals(0, pending.inputs)
            assertTrue(pending.launches > 0)
            assertFalse(traces.any { it is StoreTrace.StoreClosed })
            val waiting = async { internal.awaitIdle(10.seconds) }
            runCurrent()
            assertFalse(waiting.isCompleted)
            cleanupRelease.complete(Unit)
            assertEquals(StorePendingWork(0, 0), waiting.await())
            assertEquals(1, traces.count { it is StoreTrace.StoreClosed })
        } finally {
            cleanupRelease.complete(Unit)
            store.close()
            parent.cancel()
        }
    }
}
