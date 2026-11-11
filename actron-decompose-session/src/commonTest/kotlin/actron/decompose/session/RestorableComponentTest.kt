package actron.decompose.session

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Admission
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.typedStateMachine
import actron.test.dispatchAndAwait
import actron.test.startAndAwait
import actron.timetravel.RecordingCodec
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class RestorableComponentTest {
    @Serializable private data object Tick : Action

    @Serializable private data object Output : Event

    @Serializable private enum class Route { Search, Detail }

    private val definition =
        SessionMachine(
            typedStateMachine<Int, Tick, Unit, Output>(DefinitionId("screen"), DefinitionVersion("1"), 0) {
                state<Int> { internal<Tick> { value, _ -> value + 1 } }
            },
            RecordingCodec(Int.serializer(), Tick.serializer(), Unit.serializer(), Output.serializer()),
        )

    @Test
    fun ordinaryChildStackRestoresWithoutAnApplicationNavigationAdapter() =
        runTest {
            val lifecycle = LifecycleRegistry().apply { resume() }
            val host =
                RestorableComponent(
                    DefaultComponentContext(lifecycle),
                    "demo",
                    "1",
                    listOf(definition),
                    coroutineContext,
                ) { DemoRoot(it, definition) }
            try {
                val original = host.component.value
                val search = original.stack.value.active.instance
                search.dispatchAndAwait(Tick)
                val saved = host.encode(host.checkpoint())
                original.navigation.push(Route.Detail)
                original.stack.value.active.instance
                    .dispatchAndAwait(Tick)
                host.continueFrom(host.decode(saved))
                val restored = host.component.value
                restored.stack.value.active.instance
                    .startAndAwait()
                assertNotSame(original, restored)
                assertEquals(Route.Search, restored.stack.value.active.configuration)
                assertEquals(1, restored.stack.value.active.instance.state.value.context)
                assertEquals(Admission.Closed, search.admit(Tick))
                restored.navigation.push(Route.Detail)
                restored.stack.value.active.instance
                    .dispatchAndAwait(Tick)
                assertEquals(1, restored.stack.value.active.instance.state.value.context)
            } finally {
                host.close()
                lifecycle.destroy()
            }
        }

    @Test
    fun parentDestructionClosesGenerationOnceAfterRepeatedCaptures() =
        runTest {
            val lifecycle = LifecycleRegistry()
            val host = RestorableComponent(DefaultComponentContext(lifecycle), "demo", "1", listOf(definition), coroutineContext) { DemoRoot(it, definition) }
            val store = host.component.value.stack.value.active.instance
            store.startAndAwait()
            host.checkpoint()
            host.checkpoint()
            lifecycle.resume()
            lifecycle.destroy()
            assertEquals(Admission.Closed, store.admit(Tick))
            host.close()
        }

    private class DemoRoot(
        context: ComponentContext,
        definition: SessionMachine<Int, Tick, Unit, Output>,
    ) : ComponentContext by context {
        val navigation = StackNavigation<Route>()
        val stack =
            childStack(
                source = navigation,
                serializer = Route.serializer(),
                initialConfiguration = Route.Search,
                handleBackButton = true,
                childFactory = { route, child -> child.sessionMachine(route.name, definition, 0) },
            )
    }
}
