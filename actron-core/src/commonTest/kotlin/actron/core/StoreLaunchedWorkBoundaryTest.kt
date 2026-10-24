@file:OptIn(InternalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class StoreLaunchedWorkBoundaryTest {
    private sealed interface Snapshot : State {
        data class Active(
            val value: Int = 0,
        ) : Snapshot

        data object Finished : Snapshot
    }

    private sealed interface Command : Action {
        data object Transact : Command

        data object Increment : Command

        data object FailLater : Command

        data object Leave : Command
    }

    @Test
    fun admittedTransactionSurvivesItsLaunchCancellationAndKeepsTheLockUntilCommit() =
        runTest {
            val owner = CompletableDeferred<Job>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val incremented = CompletableDeferred<Unit>()
            val resumed = CompletableDeferred<Unit>()
            val traces = mutableListOf<StoreTrace<Snapshot, Command, Nothing>>()
            val store =
                Store<Snapshot, Command, Nothing>(Snapshot.Active()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    probe(StoreProbe { traces += it })
                    state<Snapshot.Active> {
                        action<Command.Transact> {
                            launch {
                                owner.complete(currentCoroutineContext().job)
                                transaction {
                                    entered.complete(Unit)
                                    release.await()
                                    nextState { Snapshot.Active(state.value + 1) }
                                }
                                resumed.complete(Unit)
                            }
                        }
                        action<Command.Increment> {
                            incremented.complete(Unit)
                            nextState { Snapshot.Active(state.value + 1) }
                        }
                    }
                }
            try {
                store.dispatch(Command.Transact)
                entered.await()
                owner.await().cancel()
                store.dispatch(Command.Increment)
                runCurrent()
                assertFalse(incremented.isCompleted)
                assertEquals(Snapshot.Active(), store.currentState)
                release.complete(Unit)
                assertEquals(StorePendingWork(0, 0), (store as StoreInternalApi<Snapshot, Command, Nothing>).awaitIdle(1.seconds))
                assertEquals(Snapshot.Active(2), store.currentState)
                assertFalse(resumed.isCompleted)
                val transaction = traces.filterIsInstance<StoreTrace.InputAccepted<Command>>().single { it.kind is InputKind.Transaction }
                assertEquals(InputKind.Transaction(InputId(2)), transaction.kind)
                assertEquals(ProcessingOutcome.Handled(1), traces.filterIsInstance<StoreTrace.ProcessingFinished>().single { it.input == transaction.input }.outcome)
            } finally {
                store.close()
            }
        }

    @Test
    fun queuedRecoveryReportsTheOriginalFailureInsteadOfRecoveringAnExitedState() =
        runTest {
            val fail = CompletableDeferred<Unit>()
            val leaveStarted = CompletableDeferred<Unit>()
            val leaveRelease = CompletableDeferred<Unit>()
            val original = IllegalStateException("launch failed")
            val reported = mutableListOf<Throwable>()
            val traces = mutableListOf<StoreTrace<Snapshot, Command, Nothing>>()
            var recovered = 0
            val store =
                Store<Snapshot, Command, Nothing>(Snapshot.Active()) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    probe(StoreProbe { traces += it })
                    exceptionHandler(ExceptionHandler { reported += it })
                    state<Snapshot.Active> {
                        action<Command.FailLater> {
                            launch {
                                fail.await()
                                throw original
                            }
                        }
                        action<Command.Leave> {
                            leaveStarted.complete(Unit)
                            leaveRelease.await()
                            nextState { Snapshot.Finished }
                        }
                    }
                    state<Snapshot> {
                        recover<IllegalStateException> {
                            recovered++
                            nextState { Snapshot.Active(-1) }
                        }
                    }
                }
            try {
                store.dispatchAndAwaitForTest(Command.FailLater)
                store.dispatch(Command.Leave)
                leaveStarted.await()
                fail.complete(Unit)
                runCurrent()
                val recovery = traces.filterIsInstance<StoreTrace.InputAccepted<Command>>().single { it.kind is InputKind.Recovery }
                val kind = assertIs<InputKind.Recovery>(recovery.kind)
                assertSame(original, kind.error)
                assertEquals(InputId(2), kind.origin)
                assertEquals(Snapshot.Active(), store.currentState)
                leaveRelease.complete(Unit)
                assertEquals(StorePendingWork(0, 0), (store as StoreInternalApi<Snapshot, Command, Nothing>).awaitIdle(1.seconds))
                assertEquals(Snapshot.Finished, store.currentState)
                assertEquals(0, recovered)
                assertEquals(listOf<Throwable>(original), reported)
                assertEquals(ProcessingOutcome.Failed(original), traces.filterIsInstance<StoreTrace.ProcessingFinished>().single { it.input == recovery.input }.outcome)
                assertEquals(listOf(StoreTrace.FailureReported(recovery.input, original)), traces.filterIsInstance<StoreTrace.FailureReported>())
            } finally {
                store.close()
            }
        }
}
