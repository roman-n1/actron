package actron.diagnostics

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.observability.FailureDescriptor
import actron.observability.FailureDetails
import actron.observability.FailureRelation
import actron.observability.StoreInstanceId
import actron.statechart.AtomicState
import actron.statechart.GuardCheck
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.ActionHandling
import actron.statechart.machine.CandidateDisposition
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.GuardEvaluation
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TransitionCandidate
import actron.statechart.machine.TransitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiagnosticSelectionSnapshotTest {
    @Test
    fun observationCapturesCollectionsBeforeSendingToSink() {
        val fixture = SelectionFixture()
        val active = mutableSetOf(fixture.idle)
        val candidate = TransitionCandidate(TransitionId(0), CandidateDisposition.Selected)
        val candidates = mutableListOf(candidate)
        val checks = listOf(GuardCheck.Unconditional, GuardCheck.Allowed, GuardCheck.Rejected)
        val guards = checks.mapIndexed { index, check -> GuardEvaluation(TransitionId(index), "guard-$index", check) }.toMutableList()
        val handling = ActionHandling.Handler(fixture.idle)
        val expectedGuards = guards.toList()
        val explained = fixture.explained.copy(explanation = fixture.explained.explanation.copy(active = active, candidates = candidates, guards = guards, handledBy = handling))
        val delivered = mutableListOf<CausalTraceEvent>()
        val hub = CausalTraceHub(sink = DiagnosticSink { delivered += it })
        hub.observer(fixture.machine, fixture.store).onDecided(InputId(1), fixture.input, explained)

        active.clear()
        candidates.clear()
        guards.clear()

        for (event in listOf(hub.snapshot().single(), delivered.single())) {
            val selection = event.selection
            assertEquals(setOf(fixture.idle), selection.active)
            assertEquals(listOf(candidate), selection.candidates)
            assertEquals(expectedGuards, selection.guards)
            assertEquals(handling, selection.handledBy)
        }
    }

    @Test
    fun guardFailureCopiesRelationsAndDiscardsMessagesAtEveryDepth() {
        val fixture = SelectionFixture()
        val nested = FailureDescriptor(FailureDetails.AnonymousMessage("nested-secret"))
        val cause = FailureDescriptor("Cause", "cause-secret", nested)
        val related =
            mutableListOf<FailureRelation>(
                FailureRelation.Cause(cause),
                FailureRelation.Suppressed(FailureDescriptor("Suppressed", "suppressed-secret")),
                FailureRelation.Suppressed(FailureDescriptor.Unavailable),
            )
        val failure = FailureDescriptor(FailureDetails.Detailed("GuardFailure", "root-secret"), related)
        val guard = GuardEvaluation(TransitionId(0), "guard", GuardCheck.Failed(failure))
        val explained = fixture.explained.copy(explanation = fixture.explained.explanation.copy(guards = listOf(guard)))
        val delivered = mutableListOf<CausalTraceEvent>()
        val hub = CausalTraceHub(sink = DiagnosticSink { delivered += it })
        hub.observer(fixture.machine, fixture.store).onDecided(InputId(1), fixture.input, explained)
        val expected =
            FailureDescriptor(
                FailureDetails.MetadataOnly("GuardFailure"),
                listOf(
                    FailureRelation.Cause(FailureDescriptor("Cause", FailureDescriptor.Unavailable)),
                    FailureRelation.Suppressed(FailureDescriptor("Suppressed")),
                    FailureRelation.Suppressed(FailureDescriptor.Unavailable),
                ),
            )

        related.clear()

        for (event in listOf(hub.snapshot().single(), delivered.single())) {
            val retained =
                assertIs<GuardCheck.Failed>(
                    event.selection.guards
                        .single()
                        .result,
                ).failure
            assertEquals(expected, retained)
            assertTrue("secret" !in event.toString())
            retained.withMessage { error("A diagnostic failure must not retain a message: $it") }
        }
        failure.withMessage { assertEquals("root-secret", it, "redaction must not mutate the caller's descriptor") }
    }

    @Test
    fun cyclicFailureRelationsDoNotEscapeIntoDiagnosticHistory() {
        val fixture = SelectionFixture()
        val related = mutableListOf<FailureRelation>()
        val failure = FailureDescriptor(FailureDetails.Detailed("Failure", "secret"), related)
        related += FailureRelation.Cause(failure)
        val guard = GuardEvaluation(TransitionId(0), "guard", GuardCheck.Failed(failure))
        val explained = fixture.explained.copy(explanation = fixture.explained.explanation.copy(guards = listOf(guard)))
        val hub = CausalTraceHub()
        hub.observer(fixture.machine, fixture.store).onDecided(InputId(1), fixture.input, explained)

        val retained =
            assertIs<GuardCheck.Failed>(
                hub
                    .snapshot()
                    .single()
                    .selection.guards
                    .single()
                    .result,
            ).failure
        assertTrue(retained.related.isEmpty(), "a cyclic back-edge must not retain the input graph")
        assertIs<FailureDetails.MetadataOnly>(retained.details)
    }
}

private class SelectionFixture {
    val idle = StateId("idle")
    val store = StoreInstanceId("selection")
    val machine =
        Machine<Int, Action, String, Event>(
            DefinitionId("selection"),
            DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList()),
        )
    val input = MachineInput.Start(MachineTime.Zero)
    val explained = machine.decideExplained(machine.initialSnapshot(0), input)
}
