package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class StateMachineDslTest {
    private sealed interface Input : Action {
        data class Change(
            val value: Int,
        ) : Input

        data object Touch : Input

        data object Other : Input
    }

    private sealed interface Output : Event

    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val done = StateId("Done")

    private fun machine() =
        stateMachine<Int, Input, String, Output>(DefinitionId("typed"), DefinitionVersion("1"), idle) {
            state(idle) {
                on<Input.Change>(loading, guard = { _, action -> action.value > 0 }, reduce = { _, action -> action.value })
            }
            state(loading) {
                onEnter { command("load $context") }
                onExit { context += 10 }
                internal<Input.Touch> { context, _ -> context + 1 }
                on<Input.Change>(loading) { _, action -> action.value }
                after(2.seconds, done) { it * 2 }
            }
            state(done) {}
        }

    @Test
    fun typedGuardsReducersAndInternalHandlers_preserveCommandLifetime() {
        val machine = machine()
        val start = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        val rejected = machine.decide(start, MachineInput.Dispatch(Input.Change(-1), MachineTime.Zero))
        assertTrue(rejected.snapshot.isActive(idle))
        assertEquals(0, rejected.snapshot.context)
        assertEquals(0, machine.decide(start, MachineInput.Dispatch(Input.Other, MachineTime.Zero)).snapshot.context)
        val entered = machine.decide(start, MachineInput.Dispatch(Input.Change(3), MachineTime.Zero))
        assertEquals(3, entered.snapshot.context)
        val touched = machine.decide(entered.snapshot, MachineInput.Dispatch(Input.Touch, MachineTime.Zero))
        assertEquals(4, touched.snapshot.context)
        assertTrue(touched.cancelledScopes.isEmpty())
        assertEquals(entered.snapshot.commands, touched.snapshot.commands)
        assertEquals(entered.snapshot.timers, touched.snapshot.timers)
        val restarted = machine.decide(touched.snapshot, MachineInput.Dispatch(Input.Change(5), MachineTime.Zero))
        assertEquals(5, restarted.snapshot.context)
        assertTrue(restarted.cancelledScopes.isNotEmpty())
        val finished =
            machine.decide(
                restarted.snapshot,
                MachineInput.TimerFired(
                    restarted.snapshot.timers.keys
                        .single(),
                    MachineTime(2.seconds),
                ),
            )
        assertTrue(finished.snapshot.isActive(done))
        assertEquals(30, finished.snapshot.context) // exit, then timer reducer
    }

    @Test
    fun definitionsRebuiltForReplay_haveStableKeysAndDecisions() {
        val first = machine()
        val second = machine()
        assertEquals(first.chart.transitions.map { it.guard.displayLabel }, second.chart.transitions.map { it.guard.displayLabel })
        val inputs = listOf(MachineInput.Start(MachineTime.Zero), MachineInput.Dispatch(Input.Change(3), MachineTime.Zero), MachineInput.Dispatch(Input.Touch, MachineTime.Zero))
        var a = first.initialSnapshot(0)
        var b = second.initialSnapshot(0)
        for (input in inputs) {
            a = first.decide(a, input).snapshot
            b = second.decide(b, input).snapshot
            assertEquals(a, b)
        }
    }
}
