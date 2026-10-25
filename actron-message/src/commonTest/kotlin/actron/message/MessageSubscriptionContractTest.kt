@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.message

import actron.core.Action
import actron.core.ExceptionHandler
import actron.core.Plugin
import actron.core.State
import actron.core.Store
import actron.core.closeAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MessageSubscriptionContractTest {
    @Test
    fun sharedPluginKeepsIndependentSubscriptionsAndDispatchScopes() =
        runTest {
            val plugin =
                receiveMessages<Inbox, Receive, Nothing> { packet ->
                    if (packet is Packet) dispatch(Receive.Record(packet.id))
                }
            val first = receiver(plugin)
            val second = receiver(plugin)
            val sender = sender()
            try {
                first.start()
                second.start()
                runCurrent()
                sender.dispatch(Send(1..1))
                runCurrent()
                assertEquals(listOf(1), first.currentState.received)
                assertEquals(listOf(1), second.currentState.received)
                first.closeAndJoin()
                sender.dispatch(Send(2..2))
                runCurrent()
                assertEquals(listOf(1), first.currentState.received)
                assertEquals(listOf(1, 2), second.currentState.received)
            } finally {
                first.closeAndJoin()
                second.closeAndJoin()
                sender.closeAndJoin()
            }
        }

    @Test
    fun callbackReadsLatestCommittedStateRatherThanStartupState() =
        runTest {
            val phases = mutableListOf<Int>()
            val receiver = receiver(receiveMessages { packet -> if (packet is Packet) phases += currentState.phase })
            val sender = sender()
            try {
                receiver.start()
                runCurrent()
                sender.dispatch(Send(1..1))
                runCurrent()
                receiver.dispatch(Receive.Advance)
                runCurrent()
                sender.dispatch(Send(2..2))
                runCurrent()
                assertEquals(listOf(0, 1), phases)
            } finally {
                receiver.closeAndJoin()
                sender.closeAndJoin()
            }
        }

    @Test
    fun closeCancelsAnActiveCallbackWithoutReportingItAsABlockFailure() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val handled = mutableListOf<Throwable>()
            val receiver =
                receiver(
                    receiveMessages { packet ->
                        if (packet is Packet) {
                            started.complete(Unit)
                            try {
                                release.await()
                            } finally {
                                cleaned.complete(Unit)
                            }
                        }
                    },
                    handled,
                )
            val sender = sender()
            try {
                receiver.start()
                runCurrent()
                sender.dispatch(Send(1..1))
                runCurrent()
                assertTrue(started.isCompleted)
                receiver.closeAndJoin()
                assertTrue(cleaned.isCompleted)
                assertTrue(handled.isEmpty())
            } finally {
                release.complete(Unit)
                receiver.closeAndJoin()
                sender.closeAndJoin()
            }
        }

    @Test
    fun closingASlowReceiverReleasesSenderBackpressure() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val received = mutableListOf<Int>()
            val completedSends = mutableListOf<Int>()
            val receiver =
                receiver(
                    receiveMessages { packet ->
                        if (packet is Packet) {
                            received += packet.id
                            release.await()
                        }
                    },
                )
            val sender = sender { completedSends += it }
            try {
                receiver.start()
                runCurrent()
                sender.dispatch(Send(1..66))
                runCurrent()
                assertEquals(listOf(1), received)
                assertEquals((1..65).toList(), completedSends, "64 pending messages plus the active callback")
                receiver.closeAndJoin()
                runCurrent()
                assertEquals((1..66).toList(), completedSends)
                assertEquals(listOf(1), received)
            } finally {
                release.complete(Unit)
                receiver.closeAndJoin()
                sender.closeAndJoin()
            }
        }

    @Test
    fun ordinaryBlockFailureKeepsItsIdentityAndDoesNotStopDelivery() =
        runTest {
            val failure = IllegalArgumentException("bad packet")
            val handled = mutableListOf<Throwable>()
            val received = mutableListOf<Int>()
            val receiver =
                receiver(
                    receiveMessages { packet ->
                        if (packet is Packet) {
                            received += packet.id
                            if (packet.id == 1) throw failure
                        }
                    },
                    handled,
                )
            val sender = sender()
            try {
                receiver.start()
                runCurrent()
                sender.dispatch(Send(1..2))
                runCurrent()
                assertSame(failure, handled.single())
                assertEquals(listOf(1, 2), received)
            } finally {
                receiver.closeAndJoin()
                sender.closeAndJoin()
            }
        }

    @Test
    fun subscriptionSurvivesExitFromTheStartupStateVariant() =
        runTest {
            val received = mutableListOf<Int>()
            val receiver: Store<Phase, Receive, Nothing> =
                Store(Phase.Before) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    plugin(receiveMessages { packet -> if (packet is Packet) received += packet.id })
                    state<Phase.Before> { action<Receive.Advance> { nextState { Phase.After } } }
                    state<Phase.After> {}
                }
            val sender = sender()
            try {
                receiver.dispatch(Receive.Advance)
                runCurrent()
                assertEquals(Phase.After, receiver.currentState)
                sender.dispatch(Send(1..1))
                runCurrent()
                assertEquals(listOf(1), received)
            } finally {
                receiver.closeAndJoin()
                sender.closeAndJoin()
            }
        }
}

private data class Packet(
    val id: Int,
) : Message

private data class Inbox(
    val received: List<Int> = emptyList(),
    val phase: Int = 0,
) : State

private data object SenderState : State

private data class Send(
    val ids: IntRange,
) : Action

private sealed interface Receive : Action {
    data class Record(
        val id: Int,
    ) : Receive

    data object Advance : Receive
}

private sealed interface Phase : State {
    data object Before : Phase

    data object After : Phase
}

private fun TestScope.sender(onSent: (Int) -> Unit = {}): Store<SenderState, Send, Nothing> =
    Store(SenderState) {
        coroutineContext(StandardTestDispatcher(testScheduler))
        state<SenderState> {
            action<Send> {
                for (id in action.ids) {
                    message(Packet(id))
                    onSent(id)
                }
            }
        }
    }

private fun TestScope.receiver(
    plugin: Plugin<Inbox, Receive, Nothing>,
    handled: MutableList<Throwable> = mutableListOf(),
): Store<Inbox, Receive, Nothing> =
    Store(Inbox()) {
        coroutineContext(StandardTestDispatcher(testScheduler))
        exceptionHandler(ExceptionHandler { handled += it })
        plugin(plugin)
        state<Inbox> {
            action<Receive.Record> { nextState { state.copy(received = state.received + action.id) } }
            action<Receive.Advance> { nextState { state.copy(phase = state.phase + 1) } }
        }
    }
