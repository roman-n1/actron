@file:OptIn(ExperimentalActronApi::class, ExperimentalTestApi::class)

package actron.timetravel.compose

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.observability.Capability
import actron.observability.ExecutionMode
import actron.observability.GroupSeq
import actron.observability.InputDescriptor
import actron.observability.JOURNAL_FORMAT_VERSION
import actron.observability.JournalEntry
import actron.observability.JournalRecord
import actron.observability.MachineGroupId
import actron.observability.OutcomeDescriptor
import actron.observability.OutcomeKind
import actron.observability.Payload
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.observability.StoreSeq
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.LaneId
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.timetravel.GroupBranch
import actron.timetravel.RecordedStep
import actron.timetravel.Recording
import actron.timetravel.ReplaySession
import actron.timetravel.inspect.Inspector
import actron.timetravel.recordInputs
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The inspector screen over a hand-made journal and a recording replayed by the pure machine:
 * the Stores with their completeness and reasons, the timeline, a selected position's detail,
 * the replay bar stepping and telling why forward is disabled, the definition drawn with the
 * replay's active states, and the branch panel deciding scripted inputs.
 */
class InspectorScreenTest {
    private val store = StoreInstanceId("chat-7")

    private fun journal(): List<JournalRecord<*, *, *>> {
        var seq = 0L

        fun record(
            entry: JournalEntry<*, *, *>,
            forStore: actron.observability.RecordSubject = store,
        ) = JournalRecord(JOURNAL_FORMAT_VERSION, RuntimeSessionId("run"), MachineGroupId("chat"), forStore, ExecutionMode.Live, GroupSeq(++seq), if (forStore is StoreInstanceId) StoreSeq(seq) else actron.observability.RecordOrdinal.Session, seq.milliseconds, entry)
        return listOf(
            record(JournalEntry.StoreRegistered(Capability.InspectOnly)),
            record(JournalEntry.InputAccepted(InputId(1), InputDescriptor.Startup)),
            record(JournalEntry.ProcessingStarted(InputId(1), 1)),
            record(JournalEntry.StateCommitted(InputId(1), 1, Payload.Omitted, Payload.Omitted)),
            record(JournalEntry.ProcessingFinished(InputId(1), 1, OutcomeDescriptor(OutcomeKind.Handled, 1), 1.milliseconds)),
            record(JournalEntry.InputAccepted(InputId(2), InputDescriptor.Dispatch(Payload.Projected("Send", mapOf("chars" to "12"))))),
            record(JournalEntry.ProcessingStarted(InputId(2), 2)),
            record(JournalEntry.ProcessingFinished(InputId(2), 2, OutcomeDescriptor(OutcomeKind.Unchanged), 2.milliseconds)),
            record(JournalEntry.JournalGap(3), forStore = actron.observability.RecordSubject.Session),
            record(JournalEntry.StoreClosed),
        )
    }

    @Test
    fun storesTimelineAndCompleteness_areShown_andAPositionOpensItsDetail() =
        runComposeUiTest {
            val state = InspectorState(Inspector.of(journal()))
            setContent { InspectorScreen(state) }

            onNodeWithTag("mode-bar").assertTextContains("INSPECT", substring = true)
            onNodeWithTag("completeness", useUnmergedTree = true).assertTextContains("partial", substring = true)
            onNodeWithTag("store-chat-7").assertTextContains("chat-7", substring = true)
            onNodeWithTag("store-chat-7").assertTextContains("payloads omitted", substring = true)
            onNodeWithTag("store-chat-7").assertTextContains("replay unavailable", substring = true)
            onNodeWithText("3 records dropped", substring = true).assertExists()
            onNodeWithTag("position").assertTextContains("select a position", substring = true)

            onNodeWithTag("timeline-1").performClick()

            onNodeWithTag("position").assertTextContains("Processing #1 Startup", substring = true)
            onNodeWithTag("position").assertTextContains("commit revision=1", substring = true)
            onNodeWithTag("position").assertTextContains("attach the Store's recording", substring = true)
        }

    // --- replay ---

    data class Ctx(
        val n: Int = 0,
    )

    sealed interface Act : Action {
        data object Inc : Act
    }

    data object Nothing : Event

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val machine =
        Machine<Ctx, Act, Nothing, Nothing>(
            DefinitionId("counter"),
            DefinitionVersion("1"),
            StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), listOf(Transition(idle, idle, ActionMatcher.of<Act.Inc>("Inc"), effect = actron.statechart.EffectKey("inc")))),
        ) { effect("inc") { c, _ -> c.copy(n = c.n + 1) } }

    private fun recording(): Recording<Ctx, Act, Nothing, Nothing> {
        val inputs = listOf(MachineInput.Start(MachineTime.Zero), MachineInput.Dispatch(Act.Inc, MachineTime(10.milliseconds)), MachineInput.Dispatch(Act.Inc, MachineTime(20.milliseconds)))
        var snapshot = machine.initialSnapshot(Ctx())
        val steps =
            inputs.map { input ->
                val decision = machine.decide(snapshot, input)
                snapshot = decision.snapshot
                RecordedStep.Committed<Ctx, Act, Nothing, Nothing>(input, decision)
            }
        return Recording(machine.id, machine.version, machine.initialSnapshot(Ctx()), steps)
    }

    @Test
    fun theReplayBar_stepsSeeksAndVerifies_andSaysWhyForwardIsDisabled() =
        runComposeUiTest {
            val recording = recording()
            val controls = ReplayControls(store, ReplaySession(machine, recording))
            val state = InspectorState(Inspector.of(journal(), recordings = mapOf(store to recording)), InspectorMode.Replay)
            setContent { InspectorScreen(state, replay = controls) }

            onNodeWithTag("mode-bar").assertTextContains("REPLAY", substring = true)
            onNodeWithTag("replay-position").assertTextContains("0 / 3", substring = true)
            onNodeWithTag("replay-back").assertIsNotEnabled()

            onNodeWithTag("replay-forward").performClick()
            onNodeWithTag("replay-position").assertTextContains("1 / 3", substring = true)
            onNodeWithTag("replay-back").assertIsEnabled()
            onNodeWithTag("replay-forward").performClick()
            onNodeWithTag("replay-forward").performClick()
            onNodeWithTag("replay-position").assertTextContains("3 / 3", substring = true)
            onNodeWithTag("replay-forward").assertIsNotEnabled()
            onNodeWithTag("replay-why").assertTextContains("at the end of the recording", substring = true)

            onNodeWithTag("replay-back").performClick()
            onNodeWithTag("replay-position").assertTextContains("2 / 3", substring = true)
            onNodeWithTag("replay-verify").performClick()
            onNodeWithTag("replay-verdict").assertTextContains("no divergence in 3 steps", substring = true)
            onNode(hasTestTag("store-chat-7")).assertTextContains("mismatch", substring = true)
            onNodeWithTag("definition").assertTextContains("stateDiagram-v2", substring = true)
            onNodeWithTag("definition").assertTextContains("class Root,Idle actron_active", substring = true)
        }

    @Test
    fun theGroupReplay_restoresEveryMember_evenWhenTheTimelineIsFiltered() =
        runComposeUiTest {
            val controls = GroupReplayFixture.controls()
            val recording = controls.session.recording
            val state = InspectorState(Inspector.of(GroupReplayFixture.journal(recording), recording.members), InspectorMode.Replay)
            setContent { InspectorScreen(state, groupReplay = controls) }
            onNodeWithTag("group-replay-position").assertTextContains("0 / 6", substring = true)
            onNodeWithTag("group-replay-back").assertIsNotEnabled()
            onNodeWithTag("store-chat-7").performClick()
            onNodeWithTag("group-step-1").assertDoesNotExist()
            repeat(4) { onNodeWithTag("group-replay-forward").performClick() }
            onNodeWithTag("group-replay-position").assertTextContains("4 / 6", substring = true)
            onNodeWithTag("group-member-chat-7").assertTextContains("Ctx(n=1)", substring = true)
            onNodeWithTag("group-member-root-2").assertTextContains("Ctx(n=11)", substring = true)
            onNodeWithTag("group-step-4").performClick()
            onNodeWithTag("group-replay-position").assertTextContains("5 / 6", substring = true)
            onNodeWithTag("group-position").assertTextContains("recorded after: Ctx(n=2)", substring = true)
            onNodeWithTag("group-replay-verify").performClick()
            onNodeWithTag("group-replay-verdict").assertTextContains("no divergence in 6 group steps", substring = true)
            onNodeWithTag("group-definition-root-2").assertTextContains("stateDiagram-v2", substring = true)
        }

    @Test
    fun aGroupDivergence_disablesForward_andShowsTheFailingInput() =
        runComposeUiTest {
            val controls = GroupReplayFixture.controls(changed = true)
            val recording = controls.session.recording
            val state = InspectorState(Inspector.of(GroupReplayFixture.journal(recording), recording.members), InspectorMode.Replay)
            setContent { InspectorScreen(state, groupReplay = controls) }
            repeat(4) { onNodeWithTag("group-replay-forward").performClick() }
            onNodeWithTag("group-replay-forward").assertIsNotEnabled()
            onNodeWithTag("group-replay-position").assertTextContains("3 / 6", substring = true)
            onNodeWithTag("group-replay-why").assertTextContains("root-2", substring = true)
            onNodeWithTag("group-replay-why").assertTextContains("context", substring = true)
            onNodeWithTag("group-position").assertTextContains("#3 root-2", substring = true)
            onNodeWithTag("group-replay-back").performClick()
            onNodeWithTag("group-replay-forward").assertIsEnabled()
        }

    @Test
    fun theExperimentWorkflow_pausesReplay_thenReturnsToTheOriginalPosition() =
        runComposeUiTest {
            val replay = GroupReplayFixture.controls()
            replay.seek(4)
            val recording = replay.session.recording
            val state = InspectorState(Inspector.of(GroupReplayFixture.journal(recording), recording.members))
            val controls = GroupTimeTravelControls(replay, inputs = listOf(BranchInput.Dispatch(GroupReplayFixture.b, "Inc", GroupReplayFixture.Inc)))
            setContent { InspectorScreen(state, timeTravel = controls) }
            onNodeWithTag("mode-bar").assertTextContains("REPLAY", substring = true)
            onNodeWithTag("time-travel-branch").performClick()
            onNodeWithTag("mode-bar").assertTextContains("BRANCH", substring = true)
            onNodeWithTag("time-travel-origin").assertTextContains("group position 4", substring = true)
            onNodeWithTag("group-replay-bar").assertDoesNotExist()
            onNodeWithTag("group-timeline").assertDoesNotExist()
            onNodeWithTag("branch-input-root-2-Inc").performClick()
            onNodeWithTag("branch-root-2").assertTextContains("Ctx(n=12)", substring = true)
            onNodeWithTag("time-travel-return").performClick()
            onNodeWithTag("mode-bar").assertTextContains("REPLAY", substring = true)
            onNodeWithTag("group-replay-position").assertTextContains("4 / 6", substring = true)
            onNodeWithTag("group-member-root-2").assertTextContains("Ctx(n=11)", substring = true)
            onNodeWithTag("group-replay-forward").performClick()
            onNodeWithTag("group-replay-position").assertTextContains("5 / 6", substring = true)
        }

    @Test
    fun theBranchPanel_decidesAScriptedInput_andDrawsTheDefinition() =
        runComposeUiTest {
            val recording = recording()
            val session = ReplaySession(machine, recording).also { it.seek(1) }
            val branch = BranchControls(GroupBranch(mapOf(store to session.branch()), emptyList()), mapOf(store to machine), listOf(BranchInput.Dispatch(store, "Inc", Act.Inc)))
            val state = InspectorState(Inspector.of(journal(), recordings = mapOf(store to recording)), InspectorMode.Branch)
            setContent { InspectorScreen(state, branch = branch) }

            onNodeWithTag("mode-bar").assertTextContains("BRANCH", substring = true)
            onNodeWithTag("branch-chat-7").assertTextContains("revision 1", substring = true)
            onNodeWithTag("branch-definition-chat-7").assertTextContains("class Root,Idle actron_active", substring = true)

            onNodeWithTag("branch-input-chat-7-Inc").performClick()

            onNodeWithTag("branch-chat-7").assertTextContains("revision 2", substring = true)
            onNodeWithTag("branch-chat-7").assertTextContains("Ctx(n=1)", substring = true)
            onNodeWithTag("branch-decision-0").assertTextContains("#0 chat-7 Inc: handled revision=2; context", substring = true)
            onNodeWithTag("branch-advance").performClick()
            onNodeWithTag("branch-chat-7").assertTextContains("now 1s", substring = true)
        }

    @Test
    fun combinedGroupAndBranchPreserveTheirDistinctWideAndStackedContent() =
        runComposeUiTest {
            val replay = GroupReplayFixture.controls().also { it.seek(4) }
            val recorded = replay.session.recording
            val state = InspectorState(Inspector.of(GroupReplayFixture.journal(recorded), recorded.members), InspectorMode.Branch)
            val branch = BranchControls(replay.session.branch(emptyList()), replay.session.memberViews.mapValues { it.value.machine }, listOf(BranchInput.Dispatch(GroupReplayFixture.b, "Inc", GroupReplayFixture.Inc)))
            var width by mutableStateOf(500.dp)
            setContent { MaterialTheme { InspectorScreen(state, groupReplay = replay, branch = branch, modifier = Modifier.size(width, 1000.dp)) } }
            onNodeWithTag("group-timeline").assertExists()
            onNodeWithTag("group-position").assertExists()
            onNodeWithTag("branch").assertExists()
            onNodeWithTag("branch").performScrollTo()
            onNodeWithTag("branch-input-root-2-Inc").performScrollTo().assertIsDisplayed().performClick()
            onNodeWithTag("branch-root-2").assertTextContains("Ctx(n=12)", substring = true)
            runOnIdle { width = 1200.dp }
            onNodeWithTag("group-timeline").assertExists()
            onNodeWithTag("group-position").assertDoesNotExist()
            onNodeWithTag("branch").assertExists()
            runOnIdle {
                assertEquals(4, replay.position)
                assertEquals(GroupReplayFixture.Ctx(11), replay.snapshot(GroupReplayFixture.b).context)
            }
        }

    @Test
    fun aStalePositionKeepsItsHeadingButShowsNoUnrelatedJournalDetail() =
        runComposeUiTest {
            val state = InspectorState(Inspector.of(journal()))
            state.selection = InspectorSelection.Position(999)
            setContent { MaterialTheme { PositionPanel(state, Modifier.size(600.dp, 600.dp)) } }
            onNodeWithTag("position").assertTextContains("Position 999", substring = true)
            onNodeWithTag("position").assertTextContains("select a position", substring = true)
            runOnIdle { state.select(1) }
            onNodeWithTag("position").assertTextContains("Processing #1 Startup", substring = true)
            onNodeWithTag("position").assertTextContains("attach the Store's recording", substring = true)
            runOnIdle { state.refresh(Inspector.of(emptyList())) }
            onNodeWithTag("position").assertTextContains("select a position", substring = true)
            onNodeWithText("snapshots are what the journal kept", substring = true).assertDoesNotExist()
        }

    @Test
    fun mappedBranchAnswerTargetsTheAwaitingCommandAndLeavesReplayUntouched() =
        runComposeUiTest {
            val loading = StateId("Loading")
            val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root)), listOf(Transition(idle, loading, ActionMatcher.of<Act.Inc>("Inc")), Transition(loading, idle, ActionMatcher.of<Act.Inc>("Inc"), effect = actron.statechart.EffectKey("inc"))))
            val fetching =
                Machine<Ctx, Act, String, Nothing>(DefinitionId("fetching"), DefinitionVersion("1"), chart) {
                    effect("inc") { c, _ -> c.copy(n = c.n + 1) }
                    onEnter(loading) { command("fetch", LaneId("load"), ConcurrencyPolicy.Latest) }
                }
            val recorded = fetching.recordInputs(fetching.initialSnapshot(Ctx()), listOf(MachineInput.Start(MachineTime.Zero), MachineInput.Dispatch(Act.Inc, MachineTime(10.milliseconds))))
            val replay = ReplaySession(fetching, recorded).also { it.seek(it.length) }
            val checkpoint = replay.checkpoint
            val answer = BranchInput.Answer(store, "Loaded", Act.Inc)
            val branch = BranchControls(GroupBranch(mapOf(store to replay.branch()), emptyList()), mapOf(store to fetching), listOf(answer))
            val command = branch.awaiting(store).single().id
            setContent { MaterialTheme { BranchPanel(branch, Modifier.size(1000.dp, 800.dp)) } }
            onNodeWithTag("branch-awaiting-chat-7-${command.value}").assertExists()
            onNodeWithText("awaiting c${command.value} fetch in load", substring = true).assertExists()
            onNodeWithTag("branch-answer-chat-7-${command.value}-Loaded").performClick()
            onNodeWithTag("branch-awaiting-chat-7-${command.value}").assertDoesNotExist()
            onNodeWithTag("branch-chat-7").assertTextContains("Ctx(n=1)", substring = true)
            runOnIdle {
                assertEquals(checkpoint, replay.checkpoint)
                assertEquals(1, branch.decisions.size)
            }
        }
}
