@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.Plugin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MachineConfigurationTest {
    private data class Counter(
        val count: Int = 0,
    )

    private data object Increment : Action

    private data object Started : Event

    @Test
    fun configuredPluginUsesFeatureActionsAndReceivesMachineEffects() =
        runTest {
            val machine =
                typedStateMachine<Counter, Increment, Nothing, Started>(DefinitionId("configuration"), DefinitionVersion("1"), Counter()) {
                    state<Counter> {
                        onEnter { event(Started) }
                        internal<Increment> { context, _ -> context.copy(count = context.count + 1) }
                    }
                }
            val actions = mutableListOf<Increment>()
            val effects = mutableListOf<Started>()
            val observer =
                Plugin<MachineSnapshot<Counter>, Increment, Started>(
                    onStart = { dispatch(Increment) },
                    onAction = { _, action -> actions += action },
                    onEvent = { _, event -> effects += event },
                )
            val store =
                MachineStore(machine, Counter(), CommandHandler { _, _ -> }, this, coroutineContext = coroutineContext) {
                    plugin(observer)
                }
            try {
                store.start()
                runCurrent()
                assertEquals(1, store.currentState.context.count)
                assertEquals(listOf(Increment), actions)
                assertEquals(listOf(Started), effects)
            } finally {
                store.close()
            }
        }
}
