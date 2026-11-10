package actron.decompose.session

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Admission
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.MachineStore
import actron.statechart.machine.typedStateMachine
import actron.test.dispatchAndAwait
import actron.test.startAndAwait
import actron.timetravel.RecordingCodec
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class SessionControllerTest {
    @Serializable private data object Tick : Action

    @Serializable private data object Output : Event

    private fun definition() =
        SessionMachine(
            typedStateMachine<Int, Tick, Unit, Output>(DefinitionId("counter"), DefinitionVersion("1"), 0) {
                state<Int> { internal<Tick> { value, _ -> value + 1 } }
            },
            RecordingCodec(Int.serializer(), Tick.serializer(), Unit.serializer(), Output.serializer()),
        )

    @Test
    fun restoresPreviousScreenAndStateThenAcceptsNewActions() =
        runTest {
            val host = Host(coroutineContext)
            try {
                host.open("search")
                val search = host.stores.getValue("search")
                search.dispatchAndAwait(Tick)
                val point = host.controller.checkpoint()
                host.open("detail")
                search.dispatchAndAwait(Tick)
                host.controller.restore(point)
                val restored = host.stores.getValue("search")
                restored.startAndAwait()
                assertEquals("search", host.route)
                assertNotSame(search, restored)
                assertEquals(Admission.Closed, search.admit(Tick))
                assertEquals(2, restored.state.value.context, "Initial action must not repeat")
                restored.dispatchAndAwait(Tick)
                assertEquals(3, restored.state.value.context)
            } finally {
                host.controller.close()
            }
        }

    @Test
    fun encodedCheckpointStartsANewProcessWithTheSavedScreen() =
        runTest {
            val first = Host(coroutineContext)
            first.open("detail")
            first.stores.getValue("detail").dispatchAndAwait(Tick)
            val saved = first.schema.encode(first.controller.checkpoint())
            first.controller.close()
            val second = Host(coroutineContext)
            try {
                second.controller.restore(second.schema.decode(saved))
                second.stores.getValue("detail").startAndAwait()
                assertEquals("detail", second.route)
                assertEquals(
                    2,
                    second.stores
                        .getValue("detail")
                        .state.value.context,
                )
            } finally {
                second.controller.close()
            }
        }

    @Test
    fun incompatibleCheckpointDoesNotCloseLiveStores() =
        runTest {
            val host = Host(coroutineContext)
            try {
                host.open("search")
                val saved = host.schema.encode(host.controller.checkpoint())
                assertFailsWith<IllegalArgumentException> {
                    host.controller.restore(host.schema.decode(saved.replace("account-one", "account-two")))
                }
                host.stores.getValue("search").dispatchAndAwait(Tick)
                assertEquals(
                    2,
                    host.stores
                        .getValue("search")
                        .state.value.context,
                )
            } finally {
                host.controller.close()
            }
        }

    private class Host(
        context: CoroutineContext,
    ) : SessionNavigation<String> {
        private val definition = SessionControllerTest().definition()
        val schema = SessionSchema("account-one", "1", String.serializer(), listOf(definition))
        val controller = SessionController(schema, this, context)
        val stores = linkedMapOf<String, MachineStore<Int, Tick, Unit, Output>>()
        private val keepers = mutableListOf<InstanceKeeperDispatcher>()
        var route = "home"
        private var paused = false

        fun open(
            route: String,
            session: MachineSession<String> = controller.session,
        ) {
            this.route = route
            val keeper = InstanceKeeperDispatcher()
            keepers += keeper
            stores[route] =
                session.retainedMachine(
                    DefaultComponentContext(LifecycleRegistry(), instanceKeeper = keeper),
                    route,
                    definition,
                    0,
                    CommandHandler { _, _ -> },
                    initialActions = listOf(Tick),
                )
        }

        override suspend fun pause() {
            paused = true
        }

        override fun snapshot(): String {
            check(paused)
            return route
        }

        override suspend fun replace(
            routes: String,
            session: MachineSession<String>,
        ) {
            check(paused)
            keepers.forEach { it.destroy() }
            keepers.clear()
            stores.clear()
            open(routes, session)
        }

        override fun resume() {
            paused = false
        }
    }
}
