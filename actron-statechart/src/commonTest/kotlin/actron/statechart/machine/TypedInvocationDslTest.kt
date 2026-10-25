package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class TypedInvocationDslTest {
    private sealed interface Phase {
        data object Idle : Phase

        data object Active : Phase
    }

    private data class Parent(
        val phase: Phase = Phase.Idle,
        val seed: Int = 7,
    )

    private sealed interface Act : Action {
        data object Open : Act

        data object Close : Act

        data object Finish : Act
    }

    private sealed interface Ev : Event {
        data object Finished : Ev
    }

    private fun composition() =
        typedInvokedMachine<Parent, Phase, Act, String, Ev, Int, PhaseAction, String, ChildEvent>(
            DefinitionId("parent"),
            DefinitionVersion("1"),
            Phase.Idle,
            readState = { it.phase },
            writeState = { c, phase -> c.copy(phase = phase) },
            child = childMachine(),
        ) { child ->
            state(Phase.Idle) { on<Act.Open, Phase.Active>() }
            state(Phase.Active) {
                on<Act.Open, Phase.Active>()
                on<Act.Close, Phase.Idle>()
                invoke(child, context = { it.context.seed }, toParent = { event, send ->
                    when (event) {
                        ChildEvent.Finished -> send(Act.Close)
                    }
                })
            }
        }

    private sealed interface PhaseAction : Action {
        data object Finish : PhaseAction
    }

    private sealed interface ChildEvent : Event {
        data object Finished : ChildEvent
    }

    private fun childMachine() =
        stateMachine<Int, PhaseAction, String, ChildEvent>(
            DefinitionId("child"),
            DefinitionVersion("1"),
            actron.statechart.StateId("Working"),
        ) {
            state(actron.statechart.StateId("Working")) {
                onEnter { command("child-$context") }
                onExit { event(ChildEvent.Finished) }
                on<PhaseAction.Finish>(actron.statechart.StateId("Done"))
            }
            state(actron.statechart.StateId("Done")) {}
        }

    @Test
    fun typedInvokeRoutesCompletionAndRejectsOldOwnerInputs() {
        val machine = composition()
        val start = machine.decide(machine.initialSnapshot(Parent()), InvocationInput.Parent(MachineInput.Start(MachineTime.Zero))).snapshot
        val opened = machine.decide(start, InvocationInput.Parent(MachineInput.Dispatch(Act.Open, MachineTime.Zero)))
        val owner = assertIs<ChildInvocation.Active<Int>>(opened.snapshot.child).owner
        assertEquals(
            "child-7",
            opened.childCommands
                .single()
                .command.command,
        )
        val reopened = machine.decide(opened.snapshot, InvocationInput.Parent(MachineInput.Dispatch(Act.Open, MachineTime.Zero)))
        val nextOwner = assertIs<ChildInvocation.Active<Int>>(reopened.snapshot.child).owner
        assertNotEquals(owner, nextOwner)
        assertEquals(owner, reopened.childCancellations.single().owner)
        val stale = machine.decide(reopened.snapshot, InvocationInput.Child(owner, MachineInput.Dispatch(PhaseAction.Finish, MachineTime.Zero)))
        assertEquals(reopened.snapshot, stale.snapshot)
        val finished = machine.decide(reopened.snapshot, InvocationInput.Child(nextOwner, MachineInput.Dispatch(PhaseAction.Finish, MachineTime.Zero)))
        assertEquals(Phase.Idle, finished.snapshot.parent.context.phase)
        assertEquals(ChildInvocation.Dormant, finished.snapshot.child)
    }

    @Test
    fun missingInvokeDeclarationIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            typedInvokedMachine<Parent, Phase, Act, String, Ev, Int, PhaseAction, String, ChildEvent>(
                DefinitionId("invalid"),
                DefinitionVersion("1"),
                Phase.Idle,
                readState = { it.phase },
                writeState = { c, phase -> c.copy(phase = phase) },
                child = childMachine(),
            ) { state(Phase.Idle) }
        }
    }
}
