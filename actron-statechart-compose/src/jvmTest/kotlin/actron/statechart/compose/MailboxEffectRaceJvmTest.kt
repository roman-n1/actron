@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.statechart.compose

import actron.core.ExperimentalActronApi
import actron.core.closeAndJoin
import actron.statechart.compose.MailboxFixture.Act
import actron.statechart.compose.MailboxFixture.Ev
import actron.statechart.machine.Delivery
import actron.statechart.machine.EffectId
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The storm of `MailboxEffectTest` with the store on real threads: its executor queues the
 * effects and rings the mailbox from `Dispatchers.Default` while the composition, on the test
 * dispatcher, is recreated after a delivery-start signal and before acknowledgement. The test
 * awaits each retry and acknowledgement while the producer continues on real threads.
 */
class MailboxEffectRaceJvmTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun aStormOfRecreations_withTheStoreOnRealThreads_acknowledgesEveryEffectOnce() =
        runTest(testDispatcher) {
            val effects = 400
            val recreations = 200
            val storeScope = CoroutineScope(Dispatchers.Default + SupervisorJob(backgroundScope.coroutineContext.job))
            val store = MailboxFixture.store(storeScope, coroutineContext = kotlinx.coroutines.Dispatchers.Default, maxRetained = effects)
            try {
                val producer = storeScope.launch { repeat(effects) { store.dispatch(Act.Navigate("chat/$it")) } }
                verifyMailboxRecreations(store, effects, recreations)
                producer.join()
            } finally {
                store.closeAndJoin()
                storeScope.cancel()
            }
        }
}
