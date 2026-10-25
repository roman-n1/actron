@file:OptIn(actron.core.InternalActronApi::class)

package actron.message

import actron.core.Plugin
import actron.core.State
import actron.core.Store
import actron.core.createRuntimeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagePluginTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun messagePlugin_shouldReceiveMessages() =
        runTest(testDispatcher) {
            val sendMessage = TestMessage("Hello")

            val messages = mutableListOf<TestMessage>()
            val plugin =
                receiveMessages<CounterState, Nothing, Nothing> { message ->
                    if (message is TestMessage) {
                        messages += message
                    }
                }

            val store = createTestStore(CounterState(10), plugin, sendMessage)

            store.collectState { } // start Store

            assertEquals(listOf(sendMessage), messages)
            store.close()
        }
}

private data class CounterState(
    val count: Int,
) : State

private data class TestMessage(
    val value: String,
) : Message

private fun createTestStore(
    initialState: CounterState,
    plugin: Plugin<CounterState, Nothing, Nothing>,
    message: Message,
): Store<CounterState, Nothing, Nothing> =
    createRuntimeStore(initialState) {
        coroutineContext(Dispatchers.Unconfined)
        plugin(plugin)
        state<CounterState> {
            enter {
                message(message)
            }
        }
    }
