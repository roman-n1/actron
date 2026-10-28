package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.typedStateMachine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MachineTestDslTest {
    sealed interface State {
        data object Idle : State

        data object Loading : State

        data object Done : State
    }

    sealed interface Act : Action {
        data object Load : Act

        data object Loaded : Act
    }

    sealed interface Ev : Event

    data class Fetch(
        val number: Int,
    )

    private fun machine(commands: Int = 1) =
        typedStateMachine<State, Act, Fetch, Ev>(DefinitionId("scenario"), DefinitionVersion("1"), State.Idle) {
            state<State.Idle> { on<Act.Load, State.Loading> { _, _ -> State.Loading } }
            state<State.Loading> {
                onEnter { repeat(commands) { command(Fetch(it)) } }
                on<Act.Loaded, State.Done> { _, _ -> State.Done }
            }
            state<State.Done> {}
        }

    @Test
    fun typedStateAndCommandCompletionReturnCoverage() =
        runTest {
            val report =
                machine().test(this, State.Idle) {
                    expectState<State.Idle>()
                    dispatch(Act.Load)
                    expectState<State.Loading>()
                    completeCommand { Act.Loaded }
                    expectState<State.Done>()
                    expectContext(State.Done)
                }
            report.assertTransitionsCovered()
        }

    @Test
    fun ambiguityAndAssertionFailureAlwaysCancelCommands() =
        runTest {
            val handlers = mutableListOf<ScriptedCommandHandler<Fetch, Act>>()
            assertFailsWith<AssertionError> {
                machine(2).test(this, State.Idle) {
                    handlers += driver.handler
                    dispatch(Act.Load)
                    completeCommand { Act.Loaded }
                }
            }
            testScheduler.runCurrent()
            assertEquals(2, handlers.single().cancelled.size)
            assertTrue(handlers.single().running.isEmpty())
        }

    @Test
    fun matchingCommandIsExplicitAndNoMatchHasUsefulDiagnostic() =
        runTest {
            machine(2).test(this, State.Idle) {
                dispatch(Act.Load)
                val failure = assertFailsWith<AssertionError> { completeCommand(matching = { it.number == 9 }) { Act.Loaded } }
                assertTrue(failure.message.orEmpty().contains("found 0"))
                completeCommand(matching = { it.number == 1 }) { Act.Loaded }
                expectState<State.Done>()
            }
        }

    @Test
    fun leftoverWorkAndWrongStateFailUnlessExplicitlyAllowed() =
        runTest {
            assertFailsWith<AssertionError> { machine().test(this, State.Idle) { dispatch(Act.Load) } }
            machine().test(this, State.Idle, allowPendingWork = true) {
                dispatch(Act.Load)
                assertFailsWith<AssertionError> { expectState<State.Done>() }
                assertFailsWith<IllegalArgumentException> { advanceTimeBy((-1).milliseconds) }
            }
        }
}
