@file:OptIn(actron.core.InternalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.InputAttribution
import actron.core.StoreInternalApi
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.test.awaitIdle
import actron.test.dispatchAndAwait
import actron.test.startAndAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MachineStoreQueueContractTest {
    private data class Add(
        val value: Int,
    ) : Action

    private data object Note : Event

    private class Fixture(
        scope: TestScope,
        limit: Int,
        immediate: Boolean = false,
        observers: List<DecisionObserver<List<Int>, Add, Nothing, Note>> = emptyList(),
    ) {
        private val node = StateId("active")
        private val machine =
            Machine<List<Int>, Add, Nothing, Note>(
                DefinitionId("queue-contract"),
                DefinitionVersion("1"),
                StateChartDefinition(node, listOf(AtomicState(node)), emptyList()),
            ) {
                onAction(node, ActionMatcher.of<Add>("add")) { context = context + (action as Add).value }
            }
        private val clock =
            object : MachineClock {
                override fun now(): MachineTime = MachineTime.Zero

                override suspend fun delayUntil(deadline: MachineTime) = awaitCancellation()
            }
        val store =
            MachineStore(
                machine,
                emptyList(),
                CommandHandler<Nothing, Add> { _, _ -> },
                scope.backgroundScope,
                clock = clock,
                coroutineContext = if (immediate) Dispatchers.Unconfined else StandardTestDispatcher(scope.testScheduler),
                admission = AdmissionPolicy.Bounded(limit),
                observers = observers,
            )
        val impl = store as MachineStoreImpl<List<Int>, Add, Nothing, Note>
        val api: StoreInternalApi<MachineSnapshot<List<Int>>, Add, Note> = impl
    }

    @Test
    fun thawKeepsMixedSourcesAheadOfInlineReentrantOffersAndReleasesCapacityBeforeObservers() =
        runTest {
            val offer = CompletableDeferred<(Add) -> Admission>()
            val reentrant = mutableListOf<Admission>()
            val observer =
                object : DecisionObserver<List<Int>, Add, Nothing, Note> {
                    override fun onCommitted(
                        input: InputAttribution,
                        machineInput: MachineInput<Add>,
                        decision: Decision<List<Int>, Nothing, Note>,
                    ) {
                        if (machineInput is MachineInput.Dispatch && machineInput.action.value == 1) reentrant += offer.getCompleted()(Add(3))
                    }
                }
            val f = Fixture(this, 2, immediate = true, observers = listOf(observer))
            offer.complete(f.store::admit)
            try {
                f.store.startAndAwait()
                f.impl.freeze()
                assertEquals(Admission.Accepted, f.store.admit(Add(1)))
                assertEquals(Admission.Accepted, f.store.feed(SourceId("socket"), Add(2)))
                assertEquals(Admission.Rejected(2, 2), f.store.admit(Add(99)))
                f.impl.thaw()
                f.store.awaitIdle()
                assertEquals(listOf<Admission>(Admission.Accepted), reentrant)
                assertEquals(listOf(1, 2, 3), f.store.currentState.context)
                assertEquals(0, f.impl.pendingAdmissions)
                assertEquals(0, f.impl.heldInputs)
            } finally {
                f.store.closeAndJoin()
            }
        }

    @Test
    fun frozenInputsAreOutsideIdleAndCloseDiscardsThemAndReleasesTheirWaitersWithoutThaw() =
        runTest {
            val f = Fixture(this, 2)
            try {
                f.store.startAndAwait()
                f.impl.freeze()
                val awaited = async(start = CoroutineStart.UNDISPATCHED) { f.store.dispatchAndAwait(Add(1)) }
                assertEquals(Admission.Accepted, f.store.admit(Add(2)))
                assertFalse(awaited.isCompleted)
                assertEquals(2, f.impl.pendingAdmissions)
                assertTrue(f.api.awaitIdle(10.milliseconds).isIdle, "held cut inputs are not inner-store work")
                f.store.close()
                awaited.await()
                f.impl.thaw()
                assertEquals(Admission.Closed, f.store.feed(SourceId("socket"), Add(3)))
                assertEquals(emptyList(), f.store.currentState.context)
                assertEquals(0, f.impl.pendingAdmissions)
                assertEquals(0, f.impl.heldInputs)
                f.store.closeAndJoin()
                assertTrue(f.api.awaitIdle(10.milliseconds).isIdle)
            } finally {
                f.store.closeAndJoin()
            }
        }

    @Test
    fun cancellingAnAwaiterDoesNotWithdrawAnAcceptedInputOrReleaseItsReservationBeforeProcessing() =
        runTest {
            val f = Fixture(this, 1)
            try {
                f.store.startAndAwait()
                f.impl.freeze()
                val awaited = async(start = CoroutineStart.UNDISPATCHED) { f.store.dispatchAndAwait(Add(1)) }
                awaited.cancelAndJoin()
                assertEquals(Admission.Rejected(1, 1), f.store.admit(Add(99)))
                f.impl.thaw()
                f.store.awaitIdle()
                assertEquals(listOf(1), f.store.currentState.context)
                assertEquals(Admission.Accepted, f.store.admit(Add(2)))
                f.store.awaitIdle()
                assertEquals(listOf(1, 2), f.store.currentState.context)
            } finally {
                f.store.closeAndJoin()
            }
        }
}
