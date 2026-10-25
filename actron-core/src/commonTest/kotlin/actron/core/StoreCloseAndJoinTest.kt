@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, actron.core.InternalActronApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreCloseAndJoinTest {
    private data object Snapshot : State

    private data object Ping : Action

    @Test
    fun waitsForCleanupAndCanBeRepeatedAfterClose() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cleanupEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store =
                createRuntimeStore<Snapshot, Nothing, Nothing>(Snapshot, StandardTestDispatcher(testScheduler)) {
                    state<Snapshot> {
                        enter {
                            subscribe {
                                started.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    withContext(NonCancellable) {
                                        cleanupEntered.complete(Unit)
                                        release.await()
                                    }
                                }
                            }
                        }
                    }
                }
            try {
                store.startAndAwaitForTest()
                started.await()
                val closing = async { store.closeAndJoin() }
                cleanupEntered.await()
                assertFalse(closing.isCompleted)
                release.complete(Unit)
                closing.await()
                store.closeAndJoin()
            } finally {
                release.complete(Unit)
                store.close()
            }
        }

    @Test
    fun cancellingWaiterDoesNotUndoClosing() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cleanupEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var actions = 0
            val store =
                createRuntimeStore<Snapshot, Ping, Nothing>(Snapshot, StandardTestDispatcher(testScheduler)) {
                    plugin(
                        Plugin(onStart = {
                            launch {
                                started.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    withContext(NonCancellable) {
                                        cleanupEntered.complete(Unit)
                                        release.await()
                                    }
                                }
                            }
                        }),
                    )
                    state<Snapshot> { action<Ping> { actions++ } }
                }
            try {
                store.startAndAwaitForTest()
                started.await()
                val closing = async { store.closeAndJoin() }
                cleanupEntered.await()
                closing.cancel()
                closing.join()
                store.dispatch(Ping)
                runCurrent()
                assertEquals(0, actions)
                release.complete(Unit)
                store.closeAndJoin()
            } finally {
                release.complete(Unit)
                store.close()
            }
        }

    @Test
    fun rejectsOwnLaunchAndTransactionBeforeClosingButAllowsPeerStore() =
        runTest {
            val ready = CompletableDeferred<Store<Snapshot, Ping, Nothing>>()
            val checked = CompletableDeferred<Unit>()
            var actions = 0
            val peer = createRuntimeStore<Snapshot, Nothing, Nothing>(Snapshot, StandardTestDispatcher(testScheduler)) {}
            val store =
                createRuntimeStore<Snapshot, Ping, Nothing>(Snapshot, StandardTestDispatcher(testScheduler)) {
                    state<Snapshot> {
                        enter {
                            launch {
                                val self = ready.await()
                                assertFailsWith<IllegalStateException> { self.closeAndJoin() }
                                withContext(NonCancellable) { assertFailsWith<IllegalStateException> { self.closeAndJoin() } }
                                transaction { assertFailsWith<IllegalStateException> { self.closeAndJoin() } }
                                peer.closeAndJoin()
                                checked.complete(Unit)
                            }
                        }
                        action<Ping> { actions++ }
                    }
                }
            ready.complete(store)
            try {
                store.startAndAwaitForTest()
                runCurrent()
                assertTrue(checked.isCompleted)
                store.dispatchAndAwaitForTest(Ping)
                assertEquals(1, actions)
                store.closeAndJoin()
            } finally {
                store.close()
                peer.close()
            }
        }

    @Test
    fun closingPassiveStoreDoesNotRestoreStateOrStartHandlers() =
        runTest {
            var consumed = false
            val store =
                createRuntimeStore<Snapshot, Nothing, Nothing>(Snapshot, StandardTestDispatcher(testScheduler)) {
                    stateSaver(
                        StateSaver(save = {}, restore = {
                            consumed = true
                            it
                        }),
                    )
                    state<Snapshot> { enter { consumed = true } }
                }
            store.closeAndJoin()
            runCurrent()
            assertTrue(!consumed)
        }
}
