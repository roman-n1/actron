package actron.statechart.machine

import actron.core.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectStateDslTest {
    private data class CounterState(
        val count: Int = 0,
    )

    private data object Increment : Action

    @Test
    fun readmeCounterNeedsNoSeparateContextBinding() {
        val counter =
            typedStateMachine<CounterState, Increment, Nothing, Nothing>(
                DefinitionId("counter"),
                DefinitionVersion("1"),
                CounterState(),
            ) {
                state<CounterState> {
                    internal<Increment> { state, _ -> state.copy(count = state.count + 1) }
                }
            }
        val started = counter.decide(counter.initialSnapshot(CounterState(5)), MachineInput.Start(MachineTime.Zero)).snapshot
        val result = counter.decide(started, MachineInput.Dispatch(Increment, MachineTime.Zero))
        assertEquals(CounterState(6), result.snapshot.context)
        assertEquals(started.configuration, result.snapshot.configuration)
        assertEquals(emptyList(), counter.checkInvariants(result.snapshot))
    }

    private sealed interface Phase {
        data object Idle : Phase

        data class Ready(
            val result: String,
        ) : Phase
    }

    private data class Loaded(
        val value: String,
    ) : Action

    @Test
    fun directSealedStateTransitionsUpdateGraphAndDataTogether() {
        val machine =
            typedStateMachine<Phase, Loaded, Nothing, Nothing>(
                DefinitionId("direct-phase"),
                DefinitionVersion("1"),
                Phase.Idle,
            ) {
                state<Phase.Idle> {
                    on<Loaded, Phase.Ready> { _, action -> Phase.Ready(action.value) }
                }
                state<Phase.Ready>()
            }
        val initial = machine.decide(machine.initialSnapshot(Phase.Idle), MachineInput.Start(MachineTime.Zero)).snapshot
        val result = machine.decide(initial, MachineInput.Dispatch(Loaded("result"), MachineTime.Zero))
        assertEquals(Phase.Ready("result"), result.snapshot.context)
        assertEquals(emptyList(), machine.checkInvariants(result.snapshot))
    }
}
