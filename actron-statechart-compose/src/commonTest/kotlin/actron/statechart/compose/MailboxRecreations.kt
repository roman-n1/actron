package actron.statechart.compose

import actron.statechart.compose.MailboxFixture.Act
import actron.statechart.compose.MailboxFixture.Ev
import actron.statechart.machine.Delivery
import actron.statechart.machine.EffectId
import actron.statechart.machine.MachineStore
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recreate only after a delivery starts; await acknowledgement before advancing to the next effect. */
internal suspend fun TestScope.verifyMailboxRecreations(
    store: MachineStore<Unit, Act, Nothing, Ev>,
    effects: Int,
    recreations: Int,
) {
    val gates = mutableMapOf<EffectId, CompletableDeferred<Unit>>()
    val deliveries = Channel<Delivery<Ev>>(Channel.UNLIMITED)
    val acknowledgements = Channel<Boolean>(Channel.UNLIMITED)
    val taken = mutableListOf<Delivery<Ev>>()
    val content: @Composable () -> Unit = {
        MailboxEffect(store.mailbox) { delivery ->
            taken += delivery
            deliveries.send(delivery)
            gates.getOrPut(delivery.id) { CompletableDeferred() }.await()
            acknowledgements.send(delivery.acknowledge())
        }
    }
    try {
        withComposeHarness {
            compose(content)
            pumpFrame()
            repeat(effects) { index ->
                val first = deliveries.receive()
                assertEquals(Ev.Navigate("chat/$index"), first.event)
                assertEquals(1, first.attempt)
                if (index < recreations) {
                    compose(content)
                    pumpFrame()
                    val replay = deliveries.receive()
                    assertEquals(first.id, replay.id)
                    assertEquals(first.event, replay.event)
                    assertEquals(2, replay.attempt)
                }
                gates.getValue(first.id).complete(Unit)
                assertTrue(acknowledgements.receive(), "Each effect must still be pending until its acknowledgement")
            }
        }
        assertEquals(effects + recreations, taken.size)
        assertEquals(effects, taken.map { it.id }.toSet().size)
        assertEquals(recreations, taken.count { it.attempt == 2 })
        assertTrue(store.mailbox.pending.isEmpty())
    } finally {
        deliveries.cancel()
        acknowledgements.cancel()
    }
}
