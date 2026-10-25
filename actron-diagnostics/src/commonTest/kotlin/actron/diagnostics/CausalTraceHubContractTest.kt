package actron.diagnostics

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.observability.FailureDescriptor
import actron.observability.StoreInstanceId
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.ActivationId
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.DurableCheckpoint
import actron.statechart.machine.DurableCommit
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TimerId
import actron.statechart.machine.TimerSchedule
import actron.statechart.machine.TransitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CausalTraceHubContractTest {
    @Test
    fun rejectsNonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { CausalTraceHub(0) }
        assertFailsWith<IllegalArgumentException> { CausalTraceHub(-1) }
    }

    @Test
    fun sinkSeesRecordedEventAndItsFailureDoesNotRollbackHistoryOrCommandIndex() {
        val fixture = TraceFixture()
        val owners = mutableListOf<CausalTraceHub>()
        val received = mutableListOf<CausalTraceEvent>()
        val failure = IllegalStateException("sink failure")
        val hub =
            CausalTraceHub(
                sink =
                    DiagnosticSink { event ->
                        // A sink can inspect committed metadata: it must run after the history lock is released.
                        assertSame(event, owners.single().snapshot().last())
                        received += event
                        if (received.size == 1) throw failure
                    },
            )
        owners += hub
        val observer = hub.observer(fixture.machine, fixture.store)
        val sent = fixture.machine.decideExplained(fixture.base, fixture.dispatch)
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                observer.onDecided(InputId(1), fixture.dispatch, sent)
            },
        )
        val completed =
            MachineInput.CommandCompleted(
                sent.decision.commands
                    .single()
                    .id,
                MachineTime.Zero,
            )
        observer.onDecided(InputId(2), completed, fixture.machine.decideExplained(sent.decision.snapshot, completed))
        assertEquals(listOf(1L, 2L), received.map { it.origin.sequence })
        assertEquals(received, hub.snapshot())
        assertEquals(received.first().origin, received.last().parent)
        assertFalse(received.last().incomplete)
    }

    @Test
    fun persistedSinkFailureAlsoLeavesReceiptAndRegistrationsAvailable() {
        val fixture = TraceFixture()
        val received = mutableListOf<CausalTraceEvent>()
        val failure = IllegalStateException("sink failure")
        val hub =
            CausalTraceHub(
                sink =
                    DiagnosticSink {
                        received += it
                        if (received.size == 1) throw failure
                    },
            )
        val decision = fixture.machine.decide(fixture.base, fixture.dispatch)
        val commit = DurableCommit(DurableCheckpoint(1, decision.snapshot, MachineTime.Zero), listOf(decision), emptyList())
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                hub.recordPersisted(fixture.machine, fixture.store, InputId(1), commit)
            },
        )
        assertSame(received.single(), hub.snapshot().single())
        val completed = MachineInput.CommandCompleted(decision.commands.single().id, MachineTime.Zero)
        hub.observer(fixture.machine, fixture.store).onDecided(InputId(2), completed, fixture.machine.decideExplained(decision.snapshot, completed))
        assertEquals(received.first().origin, received.last().parent)
        assertEquals(listOf(1L, 2L), received.map { it.origin.sequence })
        assertEquals("persisted", received.first().outcome)
    }

    @Test
    fun evictionKeepsIndependentCauseIndexesAndMarksMissingAncestors() {
        val fixture = TraceFixture()
        val hub = CausalTraceHub(capacity = 2)
        val observer = hub.observer(fixture.machine, fixture.store)
        val sent = fixture.machine.decideExplained(fixture.base, fixture.dispatch)
        observer.onDecided(InputId(1), fixture.dispatch, sent)
        val originalSnapshot = hub.snapshot()
        val start = MachineInput.Start(MachineTime.Zero)
        repeat(2) { observer.onDecided(InputId(it + 2L), start, fixture.machine.decideExplained(fixture.base, start)) }
        val completed =
            MachineInput.CommandCompleted(
                sent.decision.commands
                    .single()
                    .id,
                MachineTime.Zero,
            )
        observer.onDecided(InputId(4), completed, fixture.machine.decideExplained(sent.decision.snapshot, completed))
        val result = hub.snapshot().last()
        assertEquals(listOf(3L, 4L), hub.snapshot().map { it.origin.sequence })
        assertEquals(1, originalSnapshot.size, "snapshot must be a detached list")
        assertEquals(originalSnapshot.single().origin, result.parent)
        assertEquals(originalSnapshot.single().trace, result.trace)
        assertTrue(result.incomplete)
        assertIs<TraceLineage.Linked>(result.lineage)
    }

    @Test
    fun commandAndTimerIndexesAreScopedByStoreAndKeepTheirOwnCapacity() {
        val fixture = TraceFixture()
        val hub = CausalTraceHub(capacity = 1)
        val sent = fixture.machine.decideExplained(fixture.base, fixture.dispatch)
        val timer = TimerSchedule(TimerId(7), TransitionId(0), ActivationId(1), MachineTime.Zero)
        val withTimer = sent.copy(decision = sent.decision.copy(timersScheduled = listOf(timer)))
        hub.observer(fixture.machine, fixture.store).onDecided(InputId(1), fixture.dispatch, withTimer)
        val root = hub.snapshot().single()
        val completed =
            MachineInput.CommandCompleted(
                sent.decision.commands
                    .single()
                    .id,
                MachineTime.Zero,
            )
        hub.observer(fixture.machine, StoreInstanceId("other")).onDecided(InputId(1), completed, fixture.machine.decideExplained(sent.decision.snapshot, completed))
        assertIs<TraceLineage.Unresolved>(hub.snapshot().single().lineage)
        val fired = MachineInput.TimerFired(timer.id, MachineTime.Zero)
        hub.observer(fixture.machine, fixture.store).onDecided(InputId(2), fired, fixture.machine.decideExplained(fixture.base, fired))
        val timerEvent = hub.snapshot().single()
        assertEquals(root.origin, timerEvent.parent)
        assertEquals("timer", timerEvent.inputKind)
        assertEquals(TraceProvenance.Timer(timer.id), timerEvent.provenance)
        assertTrue(timerEvent.incomplete)
        hub.observer(fixture.machine, fixture.store).onDecided(InputId(3), completed, fixture.machine.decideExplained(sent.decision.snapshot, completed))
        assertEquals(root.origin, hub.snapshot().single().parent)
    }

    @Test
    fun rejectedDefinitionAndFailedCommitDoNotConsumeSequenceOrCallSink() {
        val fixture = TraceFixture()
        val delivered = mutableListOf<CausalTraceEvent>()
        val hub = CausalTraceHub(sink = DiagnosticSink { delivered += it })
        val observer = hub.observer(fixture.machine, fixture.store)
        val sent = fixture.machine.decideExplained(fixture.base, fixture.dispatch)
        val foreign = sent.decision.copy(snapshot = sent.decision.snapshot.copy(version = DefinitionVersion("foreign")))
        assertFailsWith<IllegalArgumentException> { observer.onDecided(InputId(1), fixture.dispatch, sent.copy(decision = foreign)) }
        val failed = sent.decision.copy(outcome = DecisionOutcome.Failed(FailureDescriptor("Failure"), IllegalStateException("private")))
        val checkpoint = DurableCheckpoint<Int, String>(1, sent.decision.snapshot, MachineTime.Zero)
        for (invalid in listOf(foreign, failed)) {
            val commit = DurableCommit(checkpoint, listOf(invalid), emptyList())
            assertFailsWith<IllegalArgumentException> { hub.recordPersisted(fixture.machine, fixture.store, InputId(1), commit) }
        }
        val foreignCheckpoint = checkpoint.copy(snapshot = foreign.snapshot)
        assertFailsWith<IllegalArgumentException> {
            hub.recordPersisted(fixture.machine, fixture.store, InputId(1), DurableCommit(foreignCheckpoint, listOf(sent.decision), emptyList()))
        }
        assertTrue(delivered.isEmpty())
        assertTrue(hub.snapshot().isEmpty())
        observer.onDecided(InputId(2), fixture.dispatch, sent)
        assertEquals(1L, delivered.single().origin.sequence)
    }

    @Test
    fun persistedReceiptAggregatesDecisionMetadataInOrderAndRegistersTimers() {
        val fixture = TraceFixture()
        val first = fixture.machine.decide(fixture.base, fixture.dispatch)
        val second = fixture.machine.decide(first.snapshot, fixture.dispatch)
        val timer = TimerSchedule(TimerId(7), TransitionId(0), ActivationId(1), MachineTime.Zero)
        val commit = DurableCommit(DurableCheckpoint(1, second.snapshot, MachineTime.Zero), listOf(first.copy(timersScheduled = listOf(timer)), second), emptyList())
        val hub = CausalTraceHub()
        val receipt = hub.recordPersisted(fixture.machine, fixture.store, InputId(1), commit)
        assertEquals(first.commands.map { it.id } + second.commands.map { it.id }, receipt.commands)
        assertEquals(first.effects.map { it.id } + second.effects.map { it.id }, receipt.effects)
        assertEquals("durable-commit", receipt.inputKind)
        receipt.assessment.withSelection { error("A receipt must not invent selection observations") }
        val fired = MachineInput.TimerFired(timer.id, MachineTime.Zero)
        hub.observer(fixture.machine, fixture.store).onDecided(InputId(2), fired, fixture.machine.decideExplained(second.snapshot, fired))
        assertEquals(receipt.origin, hub.snapshot().last().parent)
        assertFalse(hub.snapshot().last().incomplete)
    }
}

private class TraceFixture {
    private data object Go : Action

    private data object Changed : Event

    val store = StoreInstanceId("root")
    private val idle = StateId("idle")
    val machine =
        Machine<Int, Action, String, Event>(
            DefinitionId("trace"),
            DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList()),
        ) {
            onAction(idle, ActionMatcher.of<Go>("go")) {
                command("private-payload")
                event(Changed)
            }
        }
    val base = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
    val dispatch: MachineInput<Action> = MachineInput.Dispatch(Go, MachineTime.Zero)
}
