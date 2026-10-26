package consumer

import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.Store
import actron.statechart.machine.Machine
import actron.statechart.test.MachineTestDriver
import actron.statechart.test.ScenarioTrace
import actron.test.startAndAwait
import actron.timetravel.HistoryDefinition
import actron.timetravel.compose.InspectorScreen
import actron.timetravel.compose.InspectorState
import actron.timetravel.inspect.Inspector
import actron.timetravel.test.recording
import androidx.compose.runtime.Composable
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonPrimitive

fun <C : Any, A : Action, CMD : Any, E : Event> publishedDriver(
    machine: Machine<C, A, CMD, E>,
    context: C,
    scope: TestScope,
) = MachineTestDriver(machine, context, scope)

fun <C : Any, A : Action, CMD : Any, E : Event> publishedRecording(
    trace: ScenarioTrace<C, A, CMD, E>,
    definition: HistoryDefinition,
) = trace.recording(
    "consumer/1",
    listOf(definition),
    describe = { JsonPrimitive(it.toString()) },
    snapshot = { step, _ -> step.after.context.toString() },
)

suspend fun <S : State, A : Action, E : Event> publishedTestHelper(store: Store<S, A, E>) = store.startAndAwait()

@Composable
fun PublishedInspector() = InspectorScreen(InspectorState(Inspector(emptyList())))
