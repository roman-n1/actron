package actron.decompose.session

import actron.core.Action
import actron.core.AutoStartPolicy
import actron.core.Event
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.observability.StoreInstanceId
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.statechart.machine.resumeMachineStore
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/** A generation of executors. Installation and owner teardown run on the host dispatcher. */
internal class MachineSession<R : Any>(
    private val schema: SessionSchema<R>,
    private val context: CoroutineContext,
    private val clock: MachineClock,
    private val saved: Map<String, SavedMachine> = emptyMap(),
    private var active: Boolean = true,
) {
    internal val group = MachineGroup()
    private val owners = linkedMapOf<String, SessionOwner>()
    private val restored = mutableSetOf<String>()
    private var closed = false
    private var sequence = 0L
    private var recordedTime = MachineTime.Zero
    private var effectsBeingPublished = false
    private val watchedGroups = mutableSetOf<MachineGroup>()
    private val readyCallbacks = mutableListOf<() -> Unit>()

    /** Registers before startup; the component's keeper and session both can close the Store. */
    fun <C : Any, A : Action, CMD : Any, E : Event> retainedMachine(
        component: ComponentContext,
        key: String,
        definition: SessionMachine<C, A, CMD, E>,
        initial: C,
        handler: CommandHandler<CMD, A>,
        recording: MachineRecording<C, A, CMD, E> = MachineRecording { _, _, commands -> MachineDiagnostics(commands) },
        initialActions: List<A> = emptyList(),
        start: Boolean = true,
    ): MachineStore<C, A, CMD, E> {
        check(!closed) { "Session is closed" }
        schema.requireDefinition(definition)
        require(key.isNotBlank())
        require(start || initialActions.isEmpty())
        val owner =
            component.instanceKeeper.getOrCreate(SessionOwnerKey(this, key)) {
                check(key !in owners) { "Duplicate live session instance: $key" }
                val checkpoint = restore(key, definition, initial)
                val id = StoreInstanceId("session-${sequence++}")
                val member = group.member<C, A, CMD, E>(id)
                val setup = recording.prepare(definition.machine, checkpoint, handler)
                val tracker =
                    SessionRecording(checkpoint, definition) { now, effects ->
                        recordedTime = maxOf(recordedTime, now)
                        effectsBeingPublished = effects
                    }
                val scope = CoroutineScope(context + SupervisorJob())
                runCatching {
                    val configure: actron.statechart.machine.MachineConfiguration<C, A, E>.() -> Unit = {
                        setup.configure(this)
                        autoStartPolicy(AutoStartPolicy.OnDispatch)
                    }
                    val store =
                        if (checkpoint.snapshot.isStarted) {
                            resumeMachineStore(definition.machine, checkpoint, setup.handler, scope, context, clock, listOf(member, tracker) + setup.observers, builder = configure)
                        } else {
                            MachineStore(definition.machine, checkpoint.snapshot.context, setup.handler, scope, clock, context, observers = listOf(member, tracker) + setup.observers, builder = configure)
                        }
                    member.attach(store)
                    val instance =
                        RetainedSessionMachine(store, scope) {
                            member.release()
                            forget(key, id)
                        }
                    owners[key] =
                        SessionOwner(id, instance, { cut ->
                            @Suppress("UNCHECKED_CAST")
                            val typed = cut as ExecutorCheckpoint<C, CMD>
                            SavedMachine(definition.machine.id.value, definition.persistence.save(typed))
                        }, recorded = tracker::save, start = {
                            start(store, checkpoint, initialActions, start)
                        })
                    if (key in saved) restored += key
                    if (active) owners.getValue(key).start()
                    instance
                }.onFailure {
                    member.detach()
                    scope.cancel()
                }.getOrThrow()
            }
        return owner.store
    }

    internal fun whenReady(block: () -> Unit) {
        if (active) block() else readyCallbacks += block
    }

    private fun forget(
        key: String,
        id: StoreInstanceId,
    ) {
        if (key in owners && owners.getValue(key).id == id) owners.remove(key)
    }

    internal fun watch(group: MachineGroup): AutoCloseable {
        watchedGroups += group
        return AutoCloseable { watchedGroups -= group }
    }

    internal fun recorded(): Map<String, SavedMachine> {
        check(!closed) { "Session is closed" }
        check(!effectsBeingPublished && watchedGroups.all { it.inFlight.isEmpty() }) { "Machine effects have not finished delivery" }
        return owners.mapValues { (_, owner) -> owner.recorded(recordedTime) }
    }

    private fun <C : Any, A : Action, CMD : Any, E : Event> restore(
        key: String,
        definition: SessionMachine<C, A, CMD, E>,
        initial: C,
    ): ExecutorCheckpoint<C, CMD> {
        if (key !in saved || key in restored) return ExecutorCheckpoint.initial(definition.machine.initialSnapshot(initial))
        val value = saved.getValue(key)
        require(value.definition == definition.machine.id.value) { "Session key belongs to a different machine: $key" }
        return definition.persistence.restore(value.checkpoint)
    }

    internal fun encode(checkpoints: Map<StoreInstanceId, ExecutorCheckpoint<*, *>>): Map<String, SavedMachine> {
        check(!closed) { "Session is closed" }
        check(checkpoints.keys == owners.values.map { it.id }.toSet()) { "Session membership changed while capturing" }
        return owners.mapValues { (_, owner) ->
            check(owner.id in checkpoints) { "Session member changed while capturing" }
            owner.encode(checkpoints.getValue(owner.id))
        }
    }

    internal fun verifyRestored() {
        check(restored == saved.keys) { "Navigation did not recreate every saved machine" }
    }

    internal fun activate() {
        check(!closed) { "Session is closed" }
        if (!active) {
            active = true
            owners.values.toList().forEach { it.start() }
            val callbacks = readyCallbacks.toList()
            readyCallbacks.clear()
            callbacks.forEach { it() }
        }
    }

    internal suspend fun closeAndJoin() {
        val current = owners.values.toList()
        closeNow()
        current.forEach { it.instance.join() }
    }

    internal fun closeNow() {
        closed = true
        readyCallbacks.clear()
        owners.values.toList().forEach { it.instance.close() }
    }
}

private data class SessionOwnerKey(
    val session: Any,
    val key: String,
)

private class SessionOwner(
    val id: StoreInstanceId,
    val instance: RetainedSessionMachine<*, *, *, *>,
    val encode: (ExecutorCheckpoint<*, *>) -> SavedMachine,
    val recorded: (MachineTime) -> SavedMachine,
    val start: () -> Unit,
)

private class RetainedSessionMachine<C : Any, A : Action, CMD : Any, E : Event>(
    val store: MachineStore<C, A, CMD, E>,
    private val scope: CoroutineScope,
    private val detach: () -> Unit,
) : InstanceKeeper.Instance {
    fun close() {
        store.close()
        scope.cancel()
        detach()
    }

    suspend fun join() = store.closeAndJoin()

    override fun onDestroy() = close()
}

private fun <C : Any, A : Action, CMD : Any, E : Event> start(
    store: MachineStore<C, A, CMD, E>,
    checkpoint: ExecutorCheckpoint<C, CMD>,
    actions: List<A>,
    enabled: Boolean,
) {
    if (enabled) {
        store.start()
        if (!checkpoint.snapshot.isStarted) actions.forEach(store::dispatch)
    }
}
