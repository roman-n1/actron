package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.test.dispatchAndAwait
import actron.test.startAndAwait
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenMachineOutputTest {
    private data class Choose(
        val id: Int,
    ) : Action

    private data class Chosen(
        val id: Int,
    ) : Event

    @Test
    fun domainActionEmitsOutputWithoutRestartingStateWork() =
        runTest {
            var entries = 0
            val machine =
                screenMachine<Int, Choose, Chosen>(DefinitionId("output"), DefinitionVersion("1"), 7) {
                    state<Int> {
                        onEnter {
                            entries++
                            awaitCancellation()
                        }
                    }
                    onEvent<Choose> { _, action -> Chosen(action.id) }
                }
            val store = MachineStore(machine, 7, scope = backgroundScope, coroutineContext = coroutineContext)
            val events = mutableListOf<Chosen>()
            val observation = store.observeEvent(events::add)
            try {
                store.startAndAwait()
                testScheduler.runCurrent()
                val configuration = store.currentState.configuration
                store.dispatchAndAwait(Choose(42))
                testScheduler.runCurrent()
                assertEquals(listOf(Chosen(42)), events)
                assertEquals(7, store.currentState.context)
                assertEquals(configuration, store.currentState.configuration)
                assertEquals(1, entries)
            } finally {
                observation.close()
                store.close()
            }
        }
}
