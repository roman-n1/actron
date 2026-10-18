package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import kotlin.test.*

class TypedHierarchyDslTest {
    private sealed interface Phase {
        data object Paused : Phase

        data object Active : Phase

        data object Nested : Phase

        data object First : Phase

        data object Second : Phase

        data object Done : Phase

        data class Result(
            val count: Int,
        ) : Phase

        data object Finished : Phase
    }

    private data class Context(
        val phase: Phase = Phase.Paused,
        val count: Int = 0,
    )

    private sealed interface Act : Action {
        data object Resume : Act

        data object Restart : Act

        data object Next : Act

        data object Pause : Act

        data object Increment : Act
    }

    private sealed interface Ev : Event

    private fun hierarchy(deep: Boolean) =
        typedStateMachine<Context, Phase, Act, String, Ev>(
            DefinitionId("hierarchy"),
            DefinitionVersion("1"),
            Phase.Paused,
            readState = { it.phase },
            writeState = { context, phase -> context.copy(phase = phase) },
        ) {
            state(Phase.Paused) {
                onHistory<Act.Resume, Phase.Active>()
                on<Act.Restart, Phase.Active>()
            }
            compound<Phase.Active>(initial = Phase.Nested) {
                history(deep = deep)
                transitions {
                    on<Act.Pause, Phase.Paused>()
                    // Child handler has priority over this transition.
                    on<Act.Next, Phase.Paused>()
                }
                compound<Phase.Nested>(initial = Phase.First) {
                    state(Phase.First) {
                        onEnter {
                            check(context.phase == Phase.First)
                            command("load")
                        }
                        on<Act.Next, Phase.Second>()
                    }
                    state(Phase.Second) {
                        internal<Act.Increment> { context, _ -> context.copy(count = context.count + 1) }
                    }
                }
            }
        }

    private fun Machine<Context, Act, String, Ev>.step(
        snapshot: MachineSnapshot<Context>,
        action: Act,
    ): Decision<Context, String, Ev> =
        decide(snapshot, MachineInput.Dispatch(action, MachineTime.Zero)).also {
            assertIs<DecisionOutcome.Handled>(it.outcome)
            assertTrue(checkInvariants(it.snapshot).isEmpty())
        }

    @Test
    fun shallowAndDeepHistoryRestoreConfigurationAndKeepBusinessData() {
        for (deep in listOf(false, true)) {
            val machine = hierarchy(deep)
            var snapshot = machine.decide(machine.initialSnapshot(Context()), MachineInput.Start(MachineTime.Zero)).snapshot
            // No saved history yet: enter the initial nested configuration.
            snapshot = machine.step(snapshot, Act.Resume).snapshot
            assertEquals(Phase.First, snapshot.context.phase)
            assertTrue(snapshot.isActive(StateId("Active")))
            val next = machine.step(snapshot, Act.Next)
            assertEquals(Phase.Second, next.snapshot.context.phase)
            assertTrue(next.cancelledScopes.isNotEmpty())
            snapshot = machine.step(next.snapshot, Act.Increment).snapshot
            snapshot = machine.step(snapshot, Act.Pause).snapshot
            assertEquals(Phase.Paused, snapshot.context.phase)
            snapshot = machine.step(snapshot, Act.Resume).snapshot
            assertEquals(if (deep) Phase.Second else Phase.First, snapshot.context.phase)
            assertEquals(1, snapshot.context.count)
            snapshot = machine.step(snapshot, Act.Pause).snapshot
            snapshot = machine.step(snapshot, Act.Restart).snapshot
            assertEquals(Phase.First, snapshot.context.phase)
        }
    }

    @Test
    fun finalChildCompletesParentInOneDecision() {
        val machine =
            typedStateMachine<Context, Phase, Act, String, Ev>(
                DefinitionId("completion"),
                DefinitionVersion("1"),
                Phase.Active,
                readState = { it.phase },
                writeState = { context, phase -> context.copy(phase = phase) },
            ) {
                compound<Phase.Active>(initial = Phase.First) {
                    onDone<Phase.Finished>()
                    state(Phase.First) { on<Act.Next, Phase.Done>() }
                    final(Phase.Done)
                }
                final(Phase.Finished)
            }
        val start = machine.decide(machine.initialSnapshot(Context(Phase.Active)), MachineInput.Start(MachineTime.Zero)).snapshot
        assertEquals(Phase.First, start.context.phase)
        val done = machine.step(start, Act.Next)
        assertEquals(Phase.Finished, done.snapshot.context.phase)
        assertEquals(2, done.transitions.size)
        assertEquals(start.revision + 1, done.snapshot.revision)
        assertTrue(machine.isComplete(done.snapshot))
    }

    @Test
    fun guardedAutomaticTransitionUsesUpdatedContext() {
        val machine =
            typedStateMachine<Context, Phase, Act, String, Ev>(
                DefinitionId("automatic"),
                DefinitionVersion("1"),
                Phase.First,
                readState = { it.phase },
                writeState = { context, phase -> context.copy(phase = phase) },
            ) {
                state(Phase.First) {
                    internal<Act.Increment> { context, _ -> context.copy(count = context.count + 1) }
                    always<Phase.Finished>(guard = { it.count == 1 })
                }
                final(Phase.Finished)
            }
        val start = machine.decide(machine.initialSnapshot(Context(Phase.First)), MachineInput.Start(MachineTime.Zero)).snapshot
        val input = MachineInput.Dispatch(Act.Increment, MachineTime.Zero)
        val done = machine.decide(start, input)
        assertEquals(Phase.Finished, done.snapshot.context.phase)
        assertEquals(done, machine.decide(start, input))
    }

    @Test
    fun hierarchyWorksInsideParallelRegionsAndCompletionWaitsForBoth() {
        data class ParallelContext(
            val left: Phase = Phase.Active,
            val right: Phase = Phase.Active,
        )
        val machine =
            parallelStateMachine<ParallelContext, Act, String, Ev>(DefinitionId("parallel-final"), DefinitionVersion("1")) {
                region("Left", Phase.Active, readState = { it.left }, writeState = { context, phase -> context.copy(left = phase) }) {
                    compound<Phase.Active>(Phase.First) {
                        onDone<Phase.Finished>()
                        state(Phase.First) { on<Act.Next, Phase.Done>() }
                        final(Phase.Done)
                    }
                    final(Phase.Finished)
                }
                region("Right", Phase.Active, readState = { it.right }, writeState = { context, phase -> context.copy(right = phase) }) {
                    compound<Phase.Active>(Phase.First) {
                        onDone<Phase.Finished>()
                        state(Phase.First) { on<Act.Pause, Phase.Done>() }
                        final(Phase.Done)
                    }
                    final(Phase.Finished)
                }
            }
        val start = machine.decide(machine.initialSnapshot(ParallelContext()), MachineInput.Start(MachineTime.Zero)).snapshot
        val left = machine.decide(start, MachineInput.Dispatch(Act.Next, MachineTime.Zero)).snapshot
        assertEquals(Phase.Finished, left.context.left)
        assertEquals(Phase.First, left.context.right)
        assertFalse(machine.isComplete(left))
        val both = machine.decide(left, MachineInput.Dispatch(Act.Pause, MachineTime.Zero)).snapshot
        assertEquals(Phase.Finished, both.context.right)
        assertTrue(machine.isComplete(both))
    }

    @Test
    fun historyEntryFactoryUsesCurrentBusinessDataBeforeUserHooks() {
        val machine =
            typedStateMachine<Context, Phase, Act, String, Ev>(
                DefinitionId("history-data"),
                DefinitionVersion("1"),
                Phase.Paused,
                readState = { it.phase },
                writeState = { context, phase -> context.copy(phase = phase) },
            ) {
                state(Phase.Paused) {
                    onHistory<Act.Resume, Phase.Active>(reduce = { context, _ -> context.copy(count = context.count + 1) })
                }
                compound<Phase.Active>(Phase.Result(0)) {
                    history(deep = true)
                    on<Act.Pause, Phase.Paused>()
                    state<Phase.Result>(enter = { Phase.Result(it.count) }) {
                        onEnter { command(context.phase.toString()) }
                    }
                }
            }
        val start = machine.decide(machine.initialSnapshot(Context()), MachineInput.Start(MachineTime.Zero)).snapshot
        val first = machine.step(start, Act.Resume)
        assertEquals(Phase.Result(1), first.snapshot.context.phase)
        val paused = machine.step(first.snapshot, Act.Pause).snapshot
        val resumed = machine.step(paused, Act.Resume)
        assertEquals(Phase.Result(2), resumed.snapshot.context.phase)
        assertEquals(Phase.Result(2).toString(), resumed.commands.single().command)
    }

    @Test
    fun entryFactoryCannotSilentlyOverwriteExplicitTargetData() {
        assertFailsWith<IllegalArgumentException> {
            typedStateMachine<Context, Phase, Act, String, Ev>(
                DefinitionId("ambiguous-value"),
                DefinitionVersion("1"),
                Phase.First,
                readState = { it.phase },
                writeState = { context, phase -> context.copy(phase = phase) },
            ) {
                state(Phase.First) { on<Act.Next, Phase.Result> { _, _ -> Phase.Result(10) } }
                state<Phase.Result>(enter = { Phase.Result(20) })
            }
        }
    }

    @Test
    fun missingHistoryAndMissingEntryFactoryFailAtDefinitionTime() {
        assertFailsWith<IllegalArgumentException> {
            typedStateMachine<Context, Phase, Act, String, Ev>(
                DefinitionId("missing-history"),
                DefinitionVersion("1"),
                Phase.Paused,
                readState = { it.phase },
                writeState = { context, phase -> context.copy(phase = phase) },
            ) {
                state(Phase.Paused) { onHistory<Act.Resume, Phase.Active>() }
                compound<Phase.Active>(Phase.First) { state(Phase.First) }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            typedStateMachine<Context, Phase, Act, String, Ev>(
                DefinitionId("missing-factory"),
                DefinitionVersion("1"),
                Phase.Active,
                readState = { it.phase },
                writeState = { context, phase -> context.copy(phase = phase) },
            ) {
                compound<Phase.Active>(Phase.First) { state<Phase.First>() }
            }
        }
    }
}
