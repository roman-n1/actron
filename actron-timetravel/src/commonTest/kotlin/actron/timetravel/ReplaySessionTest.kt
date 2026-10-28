@file:OptIn(ExperimentalCoroutinesApi::class, InternalActronApi::class)

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.InputAttribution
import actron.core.InternalActronApi
import actron.observability.FailureDescriptor
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.LaneId
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineBuilder
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TimerId
import actron.statechart.test.VirtualMachineClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Stage 5, single-store replay: a run of a `MachineStore` is recorded, decided again by the pure
 * machine and compared step by step; the cursor moves back and forward over checkpoints; a
 * changed machine diverges at the first affected step; a foreign version is inspectable only.
 *
 * ```
 * [*] --> Idle
 * Idle --Load(query)--> Loading            onEnter: command Fetch(query) in lane "load", Latest; timer 10s to Idle
 * Loading --Loaded / store--> Content
 * Loading --Load / remember--> Loading
 * Content --Refresh--> Loading
 * Idle --Boom [boom]--> Idle
 * ```
 */
class ReplaySessionTest {
    data class Ctx(
        val query: String = "",
        val items: List<String> = emptyList(),
        val timeouts: Int = 0,
    )

    sealed interface Act : Action {
        data class Load(
            val query: String,
        ) : Act

        data class Loaded(
            val items: List<String>,
        ) : Act

        data object Refresh : Act

        data object Boom : Act
    }

    sealed interface Ev : Event {
        data object Shown : Ev
    }

    data class Fetch(
        val query: String,
    )

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")

    private val chart =
        StateChartDefinition(
            root,
            listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root), AtomicState(content, parent = root)),
            listOf(
                Transition(idle, loading, ActionMatcher.of<Act.Load>("Load"), effect = actron.statechart.EffectKey("remember")),
                Transition(loading, content, ActionMatcher.of<Act.Loaded>("Loaded"), effect = actron.statechart.EffectKey("store")),
                Transition(loading, loading, ActionMatcher.of<Act.Load>("Load"), effect = actron.statechart.EffectKey("remember")),
                Transition(loading, idle, Trigger.After(10.seconds), effect = actron.statechart.EffectKey("timeout")),
                Transition(content, loading, ActionMatcher.of<Act.Refresh>("Refresh")),
                Transition(idle, idle, ActionMatcher.of<Act.Boom>("Boom"), guard = actron.statechart.GuardKey("boom")),
            ),
        )

    private fun machine(
        version: String = "1",
        tweak: MachineBuilder<Ctx, Act, Fetch, Ev>.() -> Unit = {},
    ) = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("replayable"), DefinitionVersion(version), chart) {
        guard("boom") { _, _ -> throw IllegalStateException("boom") }
        effect("remember") { c, a -> c.copy(query = (a as Act.Load).query) }
        effect("store") { c, a -> c.copy(items = (a as Act.Loaded).items) }
        effect("timeout") { c, _ -> c.copy(timeouts = c.timeouts + 1) }
        onEnter(loading) { command(Fetch(context.query), LaneId("load"), ConcurrencyPolicy.Latest) }
        onEnter(content) { event(Ev.Shown) }
        tweak()
    }

    /** Records a live run: a load, a stale answer, a refresh, a timeout, a failing guard. */
    private class Run(
        val recording: Recording<Ctx, Act, Fetch, Ev>,
        val final: MachineSnapshot<Ctx>,
        val handlerCalls: Int,
    )

    private suspend fun TestScope.record(machine: Machine<Ctx, Act, Fetch, Ev>): Run {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        var handlerCalls = 0
        // A handler that answers even when cancelled, as a careless one would: its late answers
        // are stale results for the machine.
        val handler =
            CommandHandler<Fetch, Act> { command, results ->
                handlerCalls++
                try {
                    delay(if (command.command.query == "slow") 30.seconds else 100.milliseconds)
                    results.result(Act.Loaded(listOf("${command.command.query}-item")))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    results.result(Act.Loaded(listOf("late-${command.command.query}")))
                    throw e
                }
            }
        val recorder = MachineRecorder(machine, Ctx())
        val store =
            MachineStore(machine, Ctx(), handler, scope, VirtualMachineClock(testScheduler), dispatcher, observers = listOf(recorder)) {
                exceptionHandler(ExceptionHandler.Ignore)
            }
        store.start()
        runCurrent()
        store.dispatch(Act.Load("cats"))
        advanceTimeBy(50.milliseconds)
        runCurrent()
        store.dispatch(Act.Load("dogs")) // supersedes cats; the cats handler answers on cancellation: stale
        advanceTimeBy(150.milliseconds)
        runCurrent()
        assertTrue(store.currentState.isActive(content))
        store.dispatch(Act.Refresh)
        runCurrent()
        store.dispatch(Act.Load("slow"))
        advanceTimeBy(11.seconds)
        runCurrent() // the 10s timer fires before the slow answer
        assertTrue(store.currentState.isActive(idle))
        store.dispatch(Act.Boom) // the guard throws: a Failed step
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent() // the slow answer arrives for a cancelled command
        val final = store.currentState
        store.close()
        scope.cancel()
        return Run(recorder.recording(), final, handlerCalls)
    }

    @Test
    fun aRecordedRun_replaysToTheSameSnapshots_andNoHandlerRuns() =
        runTest {
            val machine = machine()
            val run = record(machine)
            val recording = run.recording
            assertTrue(recording.steps.any { it is RecordedStep.Ignored && it.reason == IgnoreReason.StaleCommand })
            assertTrue(recording.steps.any { it is RecordedStep.Failed })
            assertTrue(recording.steps.any { it.input is MachineInput.TimerFired })

            val session = ReplaySession(machine, recording)
            kotlin.test.assertFalse(session.verify { error("Unexpected replay mismatch: $it") })
            var matched = 0
            while (true) {
                val step = session.stepForward().takeUnless { it is ReplayStep.Finished } ?: break
                assertIs<ReplayStep.Matched<Ctx, Act, Fetch, Ev>>(step)
                matched++
            }
            assertEquals(recording.length, matched)
            assertEquals(run.final, session.snapshot)
            assertEquals(4, run.handlerCalls, "the live run called the handler for cats, dogs, dogs again after Refresh and slow; the replay called nothing")
        }

    @Test
    fun seekAndSteppingBack_landOnCheckpoints_thatSequentialReplayReproduces() =
        runTest {
            val machine = machine()
            val recording = record(machine).recording
            val session = ReplaySession(machine, recording)

            // Every position, reached by seek, equals the snapshot a sequential replay produces.
            var sequential = recording.initial
            for (position in 0..recording.length) {
                session.seek(position)
                assertEquals(sequential, session.snapshot, "position $position")
                recording.steps.getOrNull(position)?.let { step ->
                    val decision = machine.decide(sequential, step.input)
                    if (decision.isHandled) sequential = decision.snapshot
                }
            }
            // Back and forward from the end.
            session.seek(recording.length)
            repeat(3) { assertTrue(session.stepBackward()) }
            assertEquals(recording.snapshotAt(recording.length - 3), session.snapshot)
            repeat(3) { assertIs<ReplayStep.Matched<Ctx, Act, Fetch, Ev>>(session.observedStepForward()) }
            assertEquals(recording.snapshotAt(recording.length), session.snapshot)
            kotlin.test.assertTrue(session.stepForward() is ReplayStep.Finished)
            session.seek(0)
            assertTrue(!session.stepBackward())
            assertFailsWith<IllegalArgumentException> { session.seek(recording.length + 1) }
        }

    @Test
    fun aChangedMachine_divergesAtTheFirstAffectedStep_andTheSessionStaysThere() =
        runTest {
            val recorded = machine()
            val recording = record(recorded).recording
            // The same version string, but the store effect keeps only the first item: a rule changed without a new version.
            val changed =
                Machine<Ctx, Act, Fetch, Ev>(DefinitionId("replayable"), DefinitionVersion("1"), chart) {
                    guard("boom") { _, _ -> throw IllegalStateException("boom") }
                    effect("remember") { c, a -> c.copy(query = (a as Act.Load).query) }
                    effect("store") { c, a -> c.copy(items = (a as Act.Loaded).items.take(0)) }
                    effect("timeout") { c, _ -> c.copy(timeouts = c.timeouts + 1) }
                    onEnter(loading) { command(Fetch(context.query), LaneId("load"), ConcurrencyPolicy.Latest) }
                    onEnter(content) { event(Ev.Shown) }
                }

            val session = ReplaySession(changed, recording)
            val mismatch = session.verify()
            assertTrue(mismatch.differences.isNotEmpty(), "the changed effect must show")
            val firstLoaded = recording.steps.indexOfFirst { it.input is MachineInput.CommandResult<*> && it is RecordedStep.Committed }
            assertEquals(firstLoaded, mismatch.position)
            assertTrue(mismatch.differences.any { it.startsWith("context:") }, mismatch.describe())
            assertTrue(mismatch.describe().startsWith("Replay diverged at step $firstLoaded"))

            session.seek(firstLoaded)
            val step = session.observedStepForward()
            assertIs<ReplayStep.Diverged<Ctx, Act, Fetch, Ev>>(step)
            assertEquals(firstLoaded, session.position, "a divergence does not advance")
            assertIs<ReplayStep.Diverged<Ctx, Act, Fetch, Ev>>(session.observedStepForward())
            kotlin.test.assertFalse(session.verify(0, firstLoaded) { error("Unexpected replay mismatch: $it") }, "everything before it still matches")
        }

    @Test
    fun aTimerRecordedAsFiringBeforeItsDeadline_diverges() =
        runTest {
            val machine = machine()
            val recording = record(machine).recording
            val timerIndex = recording.steps.indexOfFirst { it.input is MachineInput.TimerFired }
            val timerStep = recording.steps[timerIndex] as RecordedStep.Committed
            val fired = timerStep.input as MachineInput.TimerFired
            val early = MachineInput.TimerFired(fired.timer, MachineTime(fired.now.sinceStart - 5.seconds))
            val tampered = Recording(recording.definition, recording.version, recording.initial, recording.steps.toMutableList().also { it[timerIndex] = RecordedStep.Committed(early, timerStep.decision) })

            val mismatch = ReplaySession(machine, tampered).verify()

            assertTrue(mismatch.position == timerIndex)
            assertTrue(mismatch.differences.any { "before its deadline" in it }, mismatch.describe())
        }

    @Test
    fun compatibility_versionIsInspectableOnly_andAnotherDefinitionIsUnsupported() =
        runTest {
            val recording = record(machine("1")).recording

            assertEquals(Compatibility.Replayable, recording.compatibilityWith(machine("1")))
            assertIs<Compatibility.InspectableOnly>(recording.compatibilityWith(machine("2")))
            val other =
                Machine<Ctx, Act, Fetch, Ev>(DefinitionId("other"), DefinitionVersion("1"), chart) {
                    guard("boom") { _, _ -> false }
                    effect("remember") { c, _ -> c }
                    effect("store") { c, _ -> c }
                    effect("timeout") { c, _ -> c }
                }
            assertIs<Compatibility.Unsupported>(recording.compatibilityWith(other))
            assertFailsWith<IllegalArgumentException> { ReplaySession(machine("2"), recording) }
            // Inspectable: the snapshots are there without deciding anything.
            assertTrue(recording.snapshotAt(recording.length).revision > 0)
        }

    @Test
    fun aRunThatDidNotStartFromTheRecorderInitial_isFlagged() =
        runTest {
            val machine = machine()
            val recorder = MachineRecorder(machine, Ctx(query = "other"))
            val start = machine.decide(machine.initialSnapshot(Ctx()), MachineInput.Start(MachineTime.Zero))

            recorder.onCommitted(InputAttribution.Unattributed, MachineInput.Start(MachineTime.Zero), start)

            assertIs<RecordingOrigin.UnknownBeginning>(recorder.origin)
        }

    @Test
    fun recording_refusesAnInitialSnapshotOfAnotherMachineOrRevision() {
        val machine = machine()
        val started = machine.decide(machine.initialSnapshot(Ctx()), MachineInput.Start(MachineTime.Zero)).snapshot
        assertFailsWith<IllegalArgumentException> { Recording<Ctx, Act, Fetch, Ev>(machine.id, machine.version, started, emptyList()) }
        assertFailsWith<IllegalArgumentException> { Recording<Ctx, Act, Fetch, Ev>(DefinitionId("other"), machine.version, machine.initialSnapshot(Ctx()), emptyList()) }
    }

    @Test
    fun replayOfARecordedFailure_matchesByFailureType() =
        runTest {
            val machine = machine()
            val recording = record(machine).recording
            val failed = recording.steps.indexOfFirst { it is RecordedStep.Failed }
            val session = ReplaySession(machine, recording)
            session.seek(failed)
            val step = session.observedStepForward()
            val matched = assertIs<ReplayStep.Matched<Ctx, Act, Fetch, Ev>>(step)
            assertIs<DecisionOutcome.Failed>(matched.decision.outcome)
            assertEquals(FailureDescriptor(type = "IllegalStateException"), (recording.steps[failed] as RecordedStep.Failed).failure)
        }

    @Test
    fun explicitVerificationReportsPassedForCleanAndEmptyRangesWithoutMoving() =
        runTest {
            val machine = machine()
            val recording = record(machine).recording
            val replay = ReplaySession(machine, recording)
            replay.seek(2)
            assertEquals(ReplayVerification.Passed, replay.verification())
            assertEquals(ReplayVerification.Passed, replay.verification(2, 2))
            assertEquals(ReplayVerification.Passed, replay.verification(replay.length, replay.length))
            assertEquals(2, replay.position)
            assertEquals(recording.snapshotAt(2), replay.snapshot)
            for ((from, to) in listOf(-1 to 0, 2 to 1, 0 to replay.length + 1)) {
                val explicit = assertFailsWith<IllegalArgumentException> { replay.verification(from, to) }
                val legacy = assertFailsWith<IllegalArgumentException> { replay.verify(from, to) {} }
                assertEquals(legacy.message, explicit.message)
            }
        }

    @Test
    fun explicitVerificationReportsTheFirstMismatchAndRespectsRangeBoundaries() =
        runTest {
            val machine = machine()
            val original = record(machine).recording
            val steps =
                original.steps.map { step ->
                    if (step is RecordedStep.Committed) {
                        step.copy(decision = step.decision.copy(snapshot = step.decision.snapshot.copy(revision = 999)))
                    } else {
                        step
                    }
                }
            val replay = ReplaySession(machine, Recording(original.definition, original.version, original.start, steps))
            replay.seek(replay.length)
            val first = assertIs<ReplayVerification.Diverged<Ctx, Act, Fetch, Ev>>(replay.verification()).mismatch
            assertEquals(0, first.position)
            val next = steps.indexOfFirst { it is RecordedStep.Committed && it != steps[0] }
            val ranged = assertIs<ReplayVerification.Diverged<Ctx, Act, Fetch, Ev>>(replay.verification(next, next + 1)).mismatch
            assertEquals(next, ranged.position)
            assertEquals(listOf("revision: recorded 999, now 1"), first.differences)
            assertEquals(replay.length, replay.position)
        }

    @Test
    fun snapshotDifferencesPrecedeIntentDifferencesInStepAndRangeVerification() =
        runTest {
            val machine = machine()
            val original = record(machine).recording
            val at = original.steps.indexOfFirst { it is RecordedStep.Committed && it.decision.commands.isNotEmpty() }
            val steps = original.steps.toMutableList()
            val step = assertIs<RecordedStep.Committed<Ctx, Act, Fetch, Ev>>(steps[at])
            steps[at] =
                step.copy(
                    decision =
                        step.decision.copy(
                            snapshot = step.decision.snapshot.copy(revision = 999, context = Ctx(query = "tampered")),
                            transitions = emptyList(),
                            entered = emptyList(),
                        ),
                )
            val replay = ReplaySession(machine, Recording(original.definition, original.version, original.start, steps))
            replay.seek(at)
            val mismatch = assertIs<ReplayStep.Diverged<Ctx, Act, Fetch, Ev>>(replay.stepForward()).mismatch
            assertEquals(listOf("revision", "context", "transitions", "entered"), mismatch.differences.map { it.substringBefore(":") })
            var callbacks = 0
            assertTrue(
                replay.verify(at, at + 1) {
                    callbacks++
                    assertEquals(mismatch.differences, it.differences)
                    assertEquals(at, it.position)
                },
            )
            assertEquals(1, callbacks)
            assertEquals(at, replay.position)
        }

    @Test
    fun aThrowingMismatchCallbackPropagatesItsFailureWithoutMovingTheCursor() =
        runTest {
            val machine = machine()
            val original = record(machine).recording
            val steps = original.steps.toMutableList()
            val first = assertIs<RecordedStep.Committed<Ctx, Act, Fetch, Ev>>(steps[0])
            steps[0] = first.copy(decision = first.decision.copy(snapshot = first.decision.snapshot.copy(context = Ctx(query = "changed"))))
            val replay = ReplaySession(machine, Recording(original.definition, original.version, original.start, steps))
            replay.seek(replay.length)
            val failure = IllegalStateException("consumer failed")
            var callbacks = 0
            val thrown =
                assertFailsWith<IllegalStateException> {
                    replay.verify {
                        callbacks++
                        throw failure
                    }
                }
            kotlin.test.assertSame(failure, thrown)
            assertEquals(1, callbacks)
            assertEquals(replay.length, replay.position)
            assertEquals(original.snapshotAt(original.length), replay.snapshot)
        }

    @Test
    fun anEarlyTimerDiagnosticPrecedesTheOutcomeOrFieldDifferences() =
        runTest {
            val machine = machine()
            val original = record(machine).recording
            val at = original.steps.indexOfFirst { it.input is MachineInput.TimerFired }
            val steps = original.steps.toMutableList()
            val step = assertIs<RecordedStep.Committed<Ctx, Act, Fetch, Ev>>(steps[at])
            val fired = assertIs<MachineInput.TimerFired>(step.input)
            val early = MachineInput.TimerFired(fired.timer, MachineTime(fired.now.sinceStart - 5.seconds))
            steps[at] = step.copy(input = early, decision = step.decision.copy(snapshot = step.decision.snapshot.copy(context = Ctx(query = "tampered"))))
            val replay = ReplaySession(machine, Recording(original.definition, original.version, original.start, steps))
            replay.seek(at)
            val mismatch = assertIs<ReplayStep.Diverged<Ctx, Act, Fetch, Ev>>(replay.stepForward()).mismatch
            assertTrue(mismatch.differences.size > 1)
            assertTrue(mismatch.differences.first().startsWith("timer ${fired.timer} fired at "))
            assertEquals(at, replay.position)
            assertEquals(original.snapshotAt(at), replay.snapshot)
        }

    @Test
    fun failureMessagesAndCausesDoNotChangeTypeBasedReplayMatching() =
        runTest {
            val machine = machine()
            val original = record(machine).recording
            val at = original.steps.indexOfFirst { it is RecordedStep.Failed }
            val steps = original.steps.toMutableList()
            val step = assertIs<RecordedStep.Failed<Ctx, Act, Fetch, Ev>>(steps[at])
            steps[at] = step.copy(failure = FailureDescriptor(step.failure.typeLabel, "different detail", FailureDescriptor("OtherCause")))
            val replay = ReplaySession(machine, Recording(original.definition, original.version, original.start, steps))
            replay.seek(at)
            assertIs<ReplayStep.Matched<Ctx, Act, Fetch, Ev>>(replay.stepForward())
            assertEquals(at + 1, replay.position)
            steps[at] = step.copy(failure = FailureDescriptor("OtherFailure"))
            val different = ReplaySession(machine, Recording(original.definition, original.version, original.start, steps))
            different.seek(at)
            val mismatch = assertIs<ReplayStep.Diverged<Ctx, Act, Fetch, Ev>>(different.stepForward()).mismatch
            assertEquals(listOf("failure: recorded OtherFailure, now IllegalStateException"), mismatch.differences)
        }
}
