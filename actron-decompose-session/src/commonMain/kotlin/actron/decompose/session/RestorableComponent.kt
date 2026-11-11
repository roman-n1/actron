package actron.decompose.session

import actron.statechart.machine.MachineClock
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.serializer
import kotlin.coroutines.CoroutineContext

/**
 * One integration point for an ordinary Decompose tree. Routers use their existing StateKeeper
 * serializers; [sessionMachine] installs restorable executors in descendants automatically.
 * Own this object for the parent's lifetime and render [component]. Replacing the generation
 * destroys the old tree, including its InstanceKeepers, even when routes have not changed.
 * Constructor/factories and navigation must run on [coroutineContext], normally Main.
 */
class RestorableComponent<T : Any>(
    private val parent: ComponentContext,
    identity: String,
    version: String,
    machines: List<SessionMachine<*, *, *, *>>,
    coroutineContext: CoroutineContext = Dispatchers.Main,
    clock: MachineClock = MachineClock.monotonic(),
    private val create: (ComponentContext) -> T,
) : AutoCloseable {
    private val schema = SessionSchema(identity, version, serializer<SerializableContainer>(), machines)
    private val navigation = Navigation()
    private val controller = SessionController(schema, navigation, coroutineContext, clock, deferStartup = true)
    private var generation = createGeneration(StateKeeperDispatcher(), controller.session)
    private val current = MutableStateFlow(generation.component)
    val component: StateFlow<T> = current.asStateFlow()
    private val back = BackCallback(onBack = { generation.back.back() })
    private var closed = false

    init {
        parent.backHandler.register(back)
        activate()
        controller.session.activate()
        parent.lifecycle.doOnDestroy(::close)
    }

    suspend fun checkpoint(): SessionCheckpoint {
        check(!closed) { "Restorable component is closed" }
        return controller.checkpoint()
    }

    suspend fun continueFrom(checkpoint: SessionCheckpoint) {
        check(!closed) { "Restorable component is closed" }
        controller.restore(checkpoint)
    }

    fun encode(checkpoint: SessionCheckpoint): String = schema.encode(checkpoint)

    fun decode(saved: String): SessionCheckpoint = schema.decode(saved)

    /** Call from a decision recording observer on the owning UI dispatcher, after its commit. */
    fun recordedCheckpoint(): SessionContinuation =
        runCatching { schema.checkpoint(navigation.snapshot(), controller.session.recorded()) }
            .fold({ SessionContinuation.Ready(it) }, { SessionContinuation.Unavailable(it.message.orEmpty()) })

    override fun close() {
        if (closed) return
        closed = true
        controller.closeNow()
        generation.close()
        if (parent.backHandler.isRegistered(back)) parent.backHandler.unregister(back)
    }

    private fun activate() {
        if (generation.activated) return
        generation.activated = true
        generation.back.addEnabledChangedListener { back.isEnabled = it }
        back.isEnabled = generation.back.isEnabled
        parent.lifecycle.subscribe(generation.callbacks)
    }

    private fun createGeneration(
        state: StateKeeperDispatcher,
        session: MachineSession<SerializableContainer>,
    ): Generation<T> {
        val lifecycle = LifecycleRegistry()
        val keeper = InstanceKeeperDispatcher()
        val backDispatcher = BackDispatcher()
        val context = SessionComponentContext(DefaultComponentContext(lifecycle, state, keeper, backDispatcher), session)
        return runCatching {
            Generation(create(context), state, lifecycle, keeper, backDispatcher, parent.lifecycle)
        }.onFailure {
            lifecycle.destroy()
            keeper.destroy()
        }.getOrThrow()
    }

    private inner class Navigation : SessionNavigation<SerializableContainer> {
        override suspend fun pause() = Unit

        override fun snapshot(): SerializableContainer = generation.state.save()

        override suspend fun replace(
            routes: SerializableContainer,
            session: MachineSession<SerializableContainer>,
        ) {
            val next = createGeneration(StateKeeperDispatcher(routes), session)
            runCatching { session.verifyRestored() }.onFailure { next.close() }.getOrThrow()
            generation.close()
            generation = next
            current.value = next.component
        }

        override fun resume() {
            // Capture leaves the generation active; replacement subscribes a fresh lifecycle once.
            activate()
        }
    }
}

private class Generation<T : Any>(
    val component: T,
    val state: StateKeeperDispatcher,
    val lifecycle: com.arkivanov.essenty.lifecycle.LifecycleRegistry,
    val keeper: InstanceKeeperDispatcher,
    val back: BackDispatcher,
    private val parent: com.arkivanov.essenty.lifecycle.Lifecycle,
) {
    val callbacks =
        object : com.arkivanov.essenty.lifecycle.Lifecycle.Callbacks by lifecycle {
            override fun onDestroy() = lifecycle.destroy()
        }
    var activated = false

    fun close() {
        parent.unsubscribe(callbacks)
        lifecycle.destroy()
        keeper.destroy()
    }
}
