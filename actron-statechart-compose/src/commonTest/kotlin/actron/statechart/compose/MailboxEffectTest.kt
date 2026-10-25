@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.statechart.compose

import actron.core.ExperimentalActronApi
import actron.core.closeAndJoin
import actron.statechart.compose.MailboxFixture.Act
import actron.statechart.compose.MailboxFixture.Ev
import actron.statechart.machine.Delivery
import actron.statechart.machine.EffectId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `MailboxEffect` ties a mailbox subscription to a composition: a delivery reaches the block and
 * leaves the mailbox on acknowledgement, what was queued before the composition entered is
 * delivered when it enters, a composition that leaves mid-handling hands the effect to the next
 * one as attempt 2, the latest block is used, a throwing block fails the composition and the
 * effect waits, and a storm of recreations loses and duplicates nothing.
 */
class MailboxEffectTest {
    private val testDispatcher = StandardTestDispatcher()

    private inner class Fixture(
        scope: TestScope,
        budget: actron.statechart.machine.RetryBudget = actron.statechart.machine.RetryBudget.Unlimited,
        maxRetained: Int = 64,
    ) {
        val executionScope = CoroutineScope(testDispatcher + SupervisorJob(scope.backgroundScope.coroutineContext.job))
        val store = MailboxFixture.store(executionScope, testDispatcher, maxRetained = maxRetained, budget = budget)

        init {
            scope.testScheduler.runCurrent()
        }
    }

    @Test
    fun replacingMailboxCancelsOldHandlingAndRetainsItsDeliveryForRetry() =
        runTest(testDispatcher) {
            val old = Fixture(this)
            val next = Fixture(this)
            val cancelled = CompletableDeferred<Unit>()
            val received = mutableListOf<Delivery<Ev>>()
            val selectedMailbox = mutableStateOf(old.store.mailbox)
            val callback =
                mutableStateOf<suspend (Delivery<Ev>) -> Unit>({
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                })
            try {
                withComposeHarness {
                    compose { MailboxEffect(selectedMailbox.value, callback.value) }
                    pumpFrame()
                    old.store.dispatch(Act.Navigate("old"))
                    next.store.dispatch(Act.Navigate("next"))
                    pumpFrame()
                    assertTrue(
                        old.store.mailbox.pending
                            .single()
                            .handling,
                    )
                    selectedMailbox.value = next.store.mailbox
                    callback.value = {
                        received += it
                        it.acknowledge()
                    }
                    pumpFrame()
                    assertTrue(cancelled.isCompleted)
                    assertFalse(
                        old.store.mailbox.pending
                            .single()
                            .handling,
                    )
                    assertEquals(listOf<Ev>(Ev.Navigate("next")), received.map { it.event })
                    assertTrue(
                        next.store.mailbox.pending
                            .isEmpty(),
                    )
                    selectedMailbox.value = old.store.mailbox
                    pumpFrame()
                    assertEquals(listOf<Ev>(Ev.Navigate("next"), Ev.Navigate("old")), received.map { it.event })
                    assertEquals(listOf(1, 2), received.map { it.attempt })
                    assertTrue(
                        old.store.mailbox.pending
                            .isEmpty(),
                    )
                }
            } finally {
                old.store.closeAndJoin()
                next.store.closeAndJoin()
            }
        }

    @Test
    fun replacingCallbackKeepsSuspendedHandlingAndUsesTheNewCallbackForNextDelivery() =
        runTest(testDispatcher) {
            val f = Fixture(this)
            val gate = CompletableDeferred<Unit>()
            val handled = mutableListOf<String>()
            val callback =
                mutableStateOf<suspend (Delivery<Ev>) -> Unit>({
                    handled += "first-start"
                    gate.await()
                    handled += "first-end"
                    it.acknowledge()
                })
            try {
                withComposeHarness {
                    compose { MailboxEffect(f.store.mailbox, callback.value) }
                    pumpFrame()
                    f.store.dispatch(Act.Navigate("first"))
                    pumpFrame()
                    callback.value = {
                        handled += "second"
                        it.acknowledge()
                    }
                    pumpFrame()
                    assertEquals(listOf("first-start"), handled)
                    assertTrue(
                        f.store.mailbox.pending
                            .single()
                            .handling,
                    )
                    gate.complete(Unit)
                    pumpFrame()
                    assertEquals(listOf("first-start", "first-end"), handled)
                    assertTrue(
                        f.store.mailbox.pending
                            .isEmpty(),
                    )
                    f.store.dispatch(Act.Navigate("second"))
                    pumpFrame()
                    assertEquals(listOf("first-start", "first-end", "second"), handled)
                    assertTrue(
                        f.store.mailbox.pending
                            .isEmpty(),
                    )
                }
            } finally {
                f.store.closeAndJoin()
            }
        }

    @Test
    fun aDelivery_reachesTheBlock_andItsAcknowledgementLeavesTheMailbox() =
        runTest(testDispatcher) {
            val f = Fixture(this)
            val received = mutableListOf<Delivery<Ev>>()

            withComposeHarness {
                compose {
                    MailboxEffect(f.store.mailbox) {
                        received += it
                        it.acknowledge()
                    }
                }
                pumpFrame()
                assertTrue(received.isEmpty())

                f.store.dispatch(Act.Navigate("chat/7"))
                pumpFrame()

                assertEquals(listOf<Ev>(Ev.Navigate("chat/7")), received.map { it.event })
                assertEquals(1, received.single().attempt)
                assertTrue(
                    f.store.mailbox.pending
                        .isEmpty(),
                    "acknowledged: ${f.store.mailbox.pending}",
                )
            }
            f.store.close()
        }

    @Test
    fun anEffectQueuedBeforeTheCompositionEntered_isDeliveredWhenItEnters() =
        runTest(testDispatcher) {
            val f = Fixture(this)
            f.store.dispatch(Act.Navigate("chat/7"))
            f.store.dispatch(Act.Navigate("chat/8"))
            runCurrent()
            assertEquals(2, f.store.mailbox.pending.size, "the mailbox keeps what no one collects yet")

            val received = mutableListOf<Delivery<Ev>>()
            withComposeHarness {
                compose {
                    MailboxEffect(f.store.mailbox) {
                        received += it
                        it.acknowledge()
                    }
                }
                pumpFrame()

                assertEquals(listOf<Ev>(Ev.Navigate("chat/7"), Ev.Navigate("chat/8")), received.map { it.event }, "oldest first")
                assertTrue(
                    f.store.mailbox.pending
                        .isEmpty(),
                )
            }
            f.store.close()
        }

    @Test
    fun aCompositionThatLeavesMidHandling_handsTheEffectToTheNextOne_asAttempt2() =
        runTest(testDispatcher) {
            val f = Fixture(this)
            val first = mutableListOf<Delivery<Ev>>()
            val second = mutableListOf<Delivery<Ev>>()

            withComposeHarness {
                compose {
                    MailboxEffect(f.store.mailbox) {
                        first += it
                        awaitCancellation()
                    }
                }
                pumpFrame()
                f.store.dispatch(Act.Navigate("chat/7"))
                pumpFrame()
                assertEquals(1, first.size)
                assertEquals(1, first.single().attempt)
                assertTrue(
                    f.store.mailbox.pending
                        .single()
                        .handling,
                    "the first composition holds it",
                )

                // The screen is recreated while the effect is being handled: a new composition, a
                // new subscription; the old one acknowledged nothing.
                compose {
                    MailboxEffect(f.store.mailbox) {
                        second += it
                        it.acknowledge()
                    }
                }
                pumpFrame()

                assertEquals(1, first.size, "the old block was cancelled, not called again")
                assertEquals(listOf(first.single().id), second.map { it.id }, "the same effect")
                assertEquals(2, second.single().attempt)
                assertTrue(
                    f.store.mailbox.pending
                        .isEmpty(),
                )
            }
            f.store.close()
        }

    @Test
    fun theBudgetOfThePolicy_endsTheRedeliveries() =
        runTest(testDispatcher) {
            val f =
                Fixture(
                    this,
                    budget =
                        actron.statechart.machine.RetryBudget
                            .Limited(2),
                )
            val taken = mutableListOf<Delivery<Ev>>()

            withComposeHarness {
                f.store.dispatch(Act.Navigate("chat/7"))
                repeat(3) {
                    compose {
                        MailboxEffect(f.store.mailbox) {
                            taken += it
                            awaitCancellation()
                        }
                    }
                    pumpFrame()
                }
                assertEquals(listOf(1, 2), taken.map { it.attempt }, "two subscribers took it and left; the third got nothing")
                assertTrue(
                    f.store.mailbox.pending
                        .isEmpty(),
                    "discarded as Exhausted",
                )
            }
            f.store.close()
        }

    @Test
    fun theLatestBlock_isUsed() =
        runTest(testDispatcher) {
            val f = Fixture(this)
            val handledBy = mutableListOf<String>()

            withComposeHarness {
                compose {
                    MailboxEffect(f.store.mailbox) {
                        handledBy += "first"
                        it.acknowledge()
                    }
                }
                pumpFrame()
                recompose {
                    MailboxEffect(f.store.mailbox) {
                        handledBy += "second"
                        it.acknowledge()
                    }
                }
                pumpFrame()

                f.store.dispatch(Act.Navigate("chat/7"))
                pumpFrame()

                assertEquals(listOf("second"), handledBy)
            }
            f.store.close()
        }

    @Test
    fun aBlockThatThrows_failsTheComposition_andTheEffectWaitsForTheNextSubscriber() =
        runTest(testDispatcher) {
            val f = Fixture(this)
            f.store.dispatch(Act.Navigate("chat/7"))
            runCurrent()

            val failure =
                assertFailsWith<IllegalStateException> {
                    withComposeHarness {
                        compose { MailboxEffect(f.store.mailbox) { error("the screen could not navigate") } }
                        pumpFrame()
                    }
                }
            assertEquals("the screen could not navigate", failure.message)

            val pending =
                f.store.mailbox.pending
                    .single()
            assertEquals(1, pending.attempts, "the attempt counted against the budget")
            assertFalse(pending.handling, "released for the next subscriber")
            f.store.close()
        }

    /**
     * 400 retained effects through 200 recreations of the composition, each recreation while a
     * delivery is being handled: every effect is acknowledged exactly once, the 200 interrupted
     * ones on their second attempt, and the mailbox ends empty. The block waits at a gate the
     * test opens, so what is mid-handling at each recreation is known.
     */
    @Test
    fun aStormOfRecreations_acknowledgesEveryEffectOnce_andLosesNone() =
        runTest(testDispatcher) {
            val effects = 400
            val recreations = 200
            val f = Fixture(this, maxRetained = effects)
            try {
                repeat(effects) { f.store.dispatch(Act.Navigate("chat/$it")) }
                verifyMailboxRecreations(f.store, effects, recreations)
            } finally {
                f.store.closeAndJoin()
            }
        }
}
