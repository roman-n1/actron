package consumer

import actron.core.ExceptionHandler
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.MachineStore
import kotlinx.coroutines.CoroutineScope

fun configuredMachine(scope: CoroutineScope) =
    MachineStore(machine, Counter(), CommandHandler { _, _ -> }, scope, coroutineContext = scope.coroutineContext) {
        exceptionHandler(ExceptionHandler.Ignore)
    }
