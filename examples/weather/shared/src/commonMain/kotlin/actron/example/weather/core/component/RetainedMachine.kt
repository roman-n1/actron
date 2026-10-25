@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.weather.core.component

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineStore
import actron.statechart.machine.resumeMachineStore
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Creates, configures, retains and starts a machine in one call. The keeper owns all Store work.
 * Use a unique stable key per machine/business identity; a retained key keeps its original inputs.
 * Handlers and recording options must not capture a component, UI scope or bridge endpoint.
 * Initial actions run once for a fresh instance; resumed checkpoints never repeat them.
 */
internal fun <C : Any, A : Action, CMD : Any, E : Event> ComponentContext.retainedMachine(
    key: String,
    machine: Machine<C, A, CMD, E>,
    initial: C,
    handler: CommandHandler<CMD, A>,
    recording: MachineRecording<C, A, CMD, E> =
        MachineRecording { definition, context, commands ->
            MachineSetup(ExecutorCheckpoint.initial(definition.initialSnapshot(context)), commands)
        },
    coroutineContext: CoroutineContext = Dispatchers.Main,
    initialActions: List<A> = emptyList(),
): MachineStore<C, A, CMD, E> =
    instanceKeeper
        .getOrCreate(key = key) {
            val setup = recording.prepare(machine, initial, handler)
            MachineInstance(coroutineContext) { scope ->
                if (setup.checkpoint.snapshot.isStarted) {
                    resumeMachineStore(machine, setup.checkpoint, setup.handler, scope, coroutineContext, setup.clock, setup.observers, builder = setup.configure)
                } else {
                    MachineStore(machine, setup.checkpoint.snapshot.context, setup.handler, scope, setup.clock, coroutineContext, observers = setup.observers, builder = setup.configure)
                }
            }.also { instance ->
                if (!setup.checkpoint.snapshot.isStarted) initialActions.forEach(instance.store::dispatch)
            }
        }.store
