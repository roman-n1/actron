package actron.decompose.session

import actron.core.Action
import actron.core.Event
import actron.decompose.MachineDiagnostics
import actron.decompose.MachineRecording
import actron.decompose.MachineRestoration
import actron.decompose.retainedMachine
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineStore
import actron.statechart.machine.noCommandHandler
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.ComponentContextFactory
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.statekeeper.SerializableContainer
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/** Propagates session installation through ordinary Decompose childStack/childSlot factories. */
internal class SessionComponentContext(
    private val delegate: ComponentContext,
    val session: MachineSession<SerializableContainer>,
) : ComponentContext by delegate {
    override val componentContextFactory =
        ComponentContextFactory<ComponentContext> { lifecycle, stateKeeper, instanceKeeper, backHandler ->
            SessionComponentContext(DefaultComponentContext(lifecycle, stateKeeper, instanceKeeper, backHandler), session)
        }
}

/** Connect an existing routed group so history refuses positions with undelivered messages. */
fun ComponentContext.trackSessionGroup(group: MachineGroup) {
    if (this is SessionComponentContext) {
        val registration = session.watch(group)
        lifecycle.doOnDestroy(registration::close)
    }
}

/** Start a composed group only after routes and all restored stores have been installed. */
fun ComponentContext.whenSessionReady(block: () -> Unit) {
    if (this is SessionComponentContext) session.whenReady(block) else block()
}

/** The same component works under a session root or an ordinary production root. */
fun <C : Any, A : Action, E : Event> ComponentContext.sessionMachine(
    key: String,
    definition: SessionMachine<C, A, Unit, E>,
    initial: C,
    recording: MachineRecording<C, A, Unit, E> = MachineRecording { _, _, commands -> MachineDiagnostics(commands) },
    coroutineContext: CoroutineContext = Dispatchers.Main,
    initialActions: List<A> = emptyList(),
    start: Boolean = true,
    clock: MachineClock = MachineClock.monotonic(),
): MachineStore<C, A, Unit, E> = sessionMachine(key, definition, initial, noCommandHandler(), recording, coroutineContext, initialActions, start, clock)

fun <C : Any, A : Action, CMD : Any, E : Event> ComponentContext.sessionMachine(
    key: String,
    definition: SessionMachine<C, A, CMD, E>,
    initial: C,
    handler: CommandHandler<CMD, A>,
    recording: MachineRecording<C, A, CMD, E> = MachineRecording { _, _, commands -> MachineDiagnostics(commands) },
    coroutineContext: CoroutineContext = Dispatchers.Main,
    initialActions: List<A> = emptyList(),
    start: Boolean = true,
    clock: MachineClock = MachineClock.monotonic(),
): MachineStore<C, A, CMD, E> =
    when (this) {
        is SessionComponentContext -> session.retainedMachine(this, key, definition, initial, handler, recording, initialActions, start)
        else -> retainedMachine(key, definition.machine, initial, handler, restoration = MachineRestoration(clock) { machine, state -> ExecutorCheckpoint.initial(machine.initialSnapshot(state)) }, recording = recording, coroutineContext = coroutineContext, initialActions = initialActions, start = start)
    }
