@file:OptIn(actron.core.InternalActronApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreLaunchRetentionJvmTest {
    private data object Active : State

    private data class Work(
        val lane: LaunchLane,
        val wait: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : Action

    @Test
    fun completedWorkDoesNotRetainPerRequestLanesInALongLivedState() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val store =
                createRuntimeStore<Active, Work, Nothing>(Active, backgroundScope.coroutineContext) {
                    state<Active> {
                        action<Work> {
                            launch(control = LaunchControl.DropIfRunning(action.lane)) { action.wait.await() }
                        }
                    }
                }
            try {
                repeat(1000) { store.dispatchAndAwaitForTest(Work(LaunchLane(), release)) }
                release.complete(Unit)
                runCurrent()

                // Completion, without another dispatch or state exit, must release every lane.
                assertEquals(0, StoreRuntimeInspection.launchJobs(store).size)
            } finally {
                store.close()
            }
        }

    @Test
    fun anOldLaunchCompletingDoesNotUntrackItsReplacement() =
        runTest {
            val lane = LaunchLane()
            val store =
                createRuntimeStore<Active, Work, Nothing>(Active, backgroundScope.coroutineContext) {
                    state<Active> {
                        action<Work> { launch(control = LaunchControl.CancelPrevious(action.lane)) { awaitCancellation() } }
                    }
                }
            try {
                store.dispatchAndAwaitForTest(Work(lane))
                runCurrent()
                store.dispatchAndAwaitForTest(Work(lane))
                runCurrent()

                assertEquals(1, StoreRuntimeInspection.launchJobs(store).size)
                assertTrue(
                    StoreRuntimeInspection
                        .launchJobs(store)
                        .values
                        .single()
                        .isActive,
                )
            } finally {
                store.close()
            }
        }
}
