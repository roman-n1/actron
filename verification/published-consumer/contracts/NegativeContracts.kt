package consumer

import actron.core.ExceptionHandler
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineStore
import kotlinx.coroutines.CoroutineScope

// Each forbidden operation has its own expected compiler diagnostic. One unrelated error must
// never make this test pass; the verifier requires a diagnostic for every labelled line.
fun forbiddenMachineConfiguration(scope: CoroutineScope) =
    MachineStore(machine, Counter(), CommandHandler { _, _ -> }, scope) {
        state<MachineSnapshot<Counter>> {} // reject: state
        initialState(TODO()) // reject: initialState
        recover<IllegalStateException> {} // reject: recover
    }

fun forbiddenHandwrittenStore(scope: CoroutineScope) = actron.core.Store<MachineSnapshot<Counter>, Increment, Ev>(machine.initialSnapshot(Counter()), scope.coroutineContext) {} // reject: Store

fun forbiddenCoroutineChart(scope: CoroutineScope) = actron.statechart.StateChartStore<Counter, Increment, Ev>(machine.chart, Counter(), scope.coroutineContext) {} // reject: StateChartStore

fun forbiddenRuntimeBypass(scope: CoroutineScope) = actron.core.createRuntimeStore<MachineSnapshot<Counter>, Increment, Ev>(machine.initialSnapshot(Counter()), scope.coroutineContext) {} // reject: internal API
