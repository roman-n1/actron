@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateConfiguration
import actron.statechart.StateId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResumeValidationTest {
    private data object Tick : Action

    private data object Note : Event

    private val node = StateId("active")
    private val handler = CommandHandler<String, Tick> { _, _ -> error("invalid checkpoints must not execute commands") }

    private fun machine(
        name: String,
        invariant: (MachineSnapshot<Int>) -> Boolean = { true },
    ) = Machine<Int, Tick, String, Note>(
        DefinitionId(name),
        DefinitionVersion("1"),
        StateChartDefinition(node, listOf(AtomicState(node)), emptyList()),
    ) { invariant("valid", invariant) }

    private fun started(machine: Machine<Int, Tick, String, Note>) = machine.decide(machine.initialSnapshot(1), MachineInput.Start(MachineTime.Zero)).snapshot

    @Test
    fun invalidStructureIsRejectedBeforeClockConfigurationOrExecution() =
        runTest {
            val machine = machine("single")
            val valid = started(machine)
            val invalid =
                listOf(
                    valid.copy(configuration = StateConfiguration(setOf(StateId("unknown")))),
                    valid.copy(activations = emptyMap()),
                    valid.copy(activations = mapOf(node to ActivationId(0))),
                    valid.copy(counters = valid.counters.copy(effects = -1)),
                    valid.copy(timers = mapOf(TimerId(1) to TimerRecord(TransitionId(99), valid.activations.getValue(node), MachineTime.Zero)), counters = valid.counters.copy(timers = 1)),
                )
            var clockReads = 0
            var configured = 0
            val clock =
                object : MachineClock {
                    override fun now(): MachineTime {
                        clockReads++
                        return MachineTime.Zero
                    }

                    override suspend fun delayUntil(deadline: MachineTime) = awaitCancellation()
                }
            for (snapshot in invalid) {
                assertFailsWith<IllegalArgumentException> {
                    resumeMachineStore(machine, ExecutorCheckpoint<Int, String>(snapshot, MachineTime.Zero), handler, backgroundScope, clock = clock) { configured++ }.close()
                }
            }
            assertEquals(0, clockReads)
            assertEquals(0, configured)
        }

    @Test
    fun aPartitionedCheckpointStillRejectsACommandWithAnUnknownScope() =
        runTest {
            val machine = machine("commands")
            val id = CommandId(1)
            val scope = ActivationId(99)
            val snapshot = started(machine).let { it.copy(commands = mapOf(id to CommandRecord(scope)), counters = it.counters.copy(commands = 1)) }
            val checkpoint = ExecutorCheckpoint(snapshot, MachineTime.Zero, Lanes(running = mapOf(id to CommandRegistration(id, "work", scope))))
            assertFailsWith<IllegalArgumentException> { resumeMachineStore(machine, checkpoint, handler, backgroundScope).close() }
        }

    @Test
    fun invocationRejectsMalformedParentEvenWhenChildOwnershipMatches() =
        runTest {
            val parent = machine("parent")
            val child = machine("child")
            val parentSnapshot = started(parent)
            val checkpoint =
                InvocationCheckpoint(
                    ExecutorCheckpoint<Int, String>(parentSnapshot.copy(configuration = StateConfiguration(setOf(StateId("unknown")))), MachineTime.Zero),
                    ChildCheckpoint.Active(parentSnapshot.activations.getValue(node), ExecutorCheckpoint<Int, String>(started(child), MachineTime.Zero)),
                )
            val composition = InvokedMachine(parent, node, child, { it.context })
            assertFailsWith<IllegalArgumentException> { resumeInvokedMachineStore(composition, checkpoint, handler, handler, backgroundScope).close() }
        }

    @Test
    fun invocationRejectsMalformedChildEvenWhenParentAndOwnershipAreValid() =
        runTest {
            val parent = machine("parent")
            val child = machine("child")
            val parentSnapshot = started(parent)
            val checkpoint =
                InvocationCheckpoint(
                    ExecutorCheckpoint<Int, String>(parentSnapshot, MachineTime.Zero),
                    ChildCheckpoint.Active(parentSnapshot.activations.getValue(node), ExecutorCheckpoint<Int, String>(started(child).copy(activations = emptyMap()), MachineTime.Zero)),
                )
            val composition = InvokedMachine(parent, node, child, { it.context })
            assertFailsWith<IllegalArgumentException> { resumeInvokedMachineStore(composition, checkpoint, handler, handler, backgroundScope).close() }
        }

    @Test
    fun anUnstartedInvocationParentStillHasToSatisfyItsUserInvariant() =
        runTest {
            val parent = machine("parent") { it.context > 0 }
            val child = machine("child")
            val composition = InvokedMachine(parent, node, child, { it.context })
            val valid = InvocationCheckpoint<Int, String, Int, String>(ExecutorCheckpoint(parent.initialSnapshot(1), MachineTime.Zero))
            resumeInvokedMachineStore(composition, valid, handler, handler, backgroundScope).close()
            val invalid = valid.copy(parent = valid.parent.copy(snapshot = parent.initialSnapshot(0)))
            assertFailsWith<IllegalArgumentException> {
                resumeInvokedMachineStore(composition, invalid, handler, handler, backgroundScope).close()
            }
        }

    @Test
    fun validResumeChecksUserInvariantsOnceForEachMachine() =
        runTest {
            var parentChecks = 0
            var childChecks = 0
            val parent =
                machine("parent") {
                    parentChecks++
                    true
                }
            val child =
                machine("child") {
                    childChecks++
                    true
                }
            val parentSnapshot = started(parent)
            val childSnapshot = started(child)
            parentChecks = 0
            childChecks = 0
            val checkpoint = ExecutorCheckpoint<Int, String>(parentSnapshot, MachineTime.Zero)
            resumeMachineStore(parent, checkpoint, handler, backgroundScope).close()
            assertEquals(1, parentChecks)
            parentChecks = 0
            val invocation = InvocationCheckpoint(checkpoint, ChildCheckpoint.Active(parentSnapshot.activations.getValue(node), ExecutorCheckpoint<Int, String>(childSnapshot, MachineTime.Zero)))
            resumeInvokedMachineStore(InvokedMachine(parent, node, child, { it.context }), invocation, handler, handler, backgroundScope).close()
            assertEquals(1, parentChecks)
            assertEquals(1, childChecks)
        }
}
