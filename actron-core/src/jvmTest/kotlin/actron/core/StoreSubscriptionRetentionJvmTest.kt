@file:OptIn(actron.core.InternalActronApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreSubscriptionRetentionJvmTest {
    private data object Active : State

    private data object Subscribe : Action

    @Test
    fun completedSubscriptionsAreReleasedWithoutLeavingTheState() =
        runTest {
            for (dispatcher in listOf(StandardTestDispatcher(testScheduler), UnconfinedTestDispatcher(testScheduler))) {
                val release = CompletableDeferred<Unit>()
                val store =
                    createRuntimeStore<Active, Subscribe, Nothing>(Active, dispatcher) {
                        state<Active> {
                            enter { subscribe { release.await() } }
                            action<Subscribe> { subscribe {} }
                        }
                    }
                try {
                    store.startAndAwaitForTest()
                    repeat(100) { store.dispatchAndAwaitForTest(Subscribe) }
                    runCurrent()
                    assertEquals(1, StoreRuntimeInspection.subscriptions(store).size)
                    assertTrue(StoreRuntimeInspection.subscriptions(store).single().isActive)
                    // Live subscriptions remain excluded from idle; completion still releases them.
                    store.awaitIdleForTest()
                    release.complete(Unit)
                    runCurrent()
                    assertTrue(StoreRuntimeInspection.subscriptions(store).isEmpty())
                } finally {
                    release.complete(Unit)
                    store.close()
                }
            }
        }
}
