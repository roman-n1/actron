@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package consumer

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishedSmokeTest {
    @Test
    fun publishedMachinesComposeAdaptersAndMessageBusWorkTogether() =
        runTest {
            System.getProperty("consumer.expected.java.feature")?.let {
                assertEquals(it.toInt(), Runtime.version().feature(), "CI must execute the smoke on the requested Java runtime")
            }
            val counter = counter(this)
            val receiver = receiver(this)
            val machine = runningMachine(this)
            try {
                listOf(counter, receiver, machine).forEach { it.start() }
                runCurrent()
                counter.dispatch(Increment)
                machine.dispatch(Increment)
                runCurrent()
                assertEquals(1, counter.currentState.context.count)
                assertEquals(1, receiver.currentState.context.count, "message plugin was obtained from the published artifact")
                assertEquals(1, machine.currentState.context.count)
            } finally {
                listOf(counter, receiver, machine).forEach { it.close() }
            }
        }
}
