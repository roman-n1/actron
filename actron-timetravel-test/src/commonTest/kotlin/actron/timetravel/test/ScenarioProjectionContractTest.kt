package actron.timetravel.test

import actron.core.Action
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.EffectKey
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import actron.statechart.machine.CommandId
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TimerId
import actron.statechart.machine.TransitionCoverage
import actron.statechart.test.MachineScenario
import actron.statechart.test.scenarios
import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryRecordingCodec
import actron.timetravel.historyDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ScenarioProjectionContractTest {
    private sealed interface Act : Action {
        data object Go : Act

        data object Unknown : Act

        data object Crash : Act
    }

    private sealed interface Ev : Event {
        data object Started : Ev

        data object Done : Ev
    }

    private val idle = StateId("idle")
    private val done = StateId("done")
    private val machine =
        Machine<Int, Act, String, Ev>(
            DefinitionId("projection"),
            DefinitionVersion("1"),
            StateChartDefinition(
                idle,
                listOf(AtomicState(idle), AtomicState(done)),
                listOf(
                    Transition(idle, done, ActionMatcher.of<Act.Go>("go"), effect = EffectKey("increment")),
                    Transition(idle, done, Trigger.After(10.milliseconds)),
                ),
            ),
        ) {
            effect("increment") { value, _ -> value + 1 }
            onEnter(idle) {
                command("fetch")
                event(Ev.Started)
            }
            onEnter(done) { event(Ev.Done) }
            onAction(done, ActionMatcher.of<Act.Crash>("crash")) { error("broken reducer") }
        }
    private val trace =
        machine.scenarios(0) {}.record(
            MachineScenario(
                "projection",
                listOf(
                    MachineInput.Start(MachineTime.Zero),
                    MachineInput.CommandResult(CommandId(1), Act.Unknown, MachineTime.Zero),
                    MachineInput.Dispatch(Act.Go, MachineTime(1.milliseconds)),
                    MachineInput.Dispatch(Act.Unknown, MachineTime(2.milliseconds)),
                    MachineInput.Dispatch(Act.Crash, MachineTime(3.milliseconds)),
                ),
            ),
        )
    private val definition = machine.chart.historyDefinition(machine.id.value, machine.version.value)

    @Test
    fun timerInputKeepsItsSourceDeadlineAndTakenTransitionCoverage() {
        val timerTrace =
            machine.scenarios(0) {}.record(
                MachineScenario(
                    "timer",
                    listOf(
                        MachineInput.Start(MachineTime.Zero),
                        MachineInput.TimerFired(TimerId(1), MachineTime(10.milliseconds)),
                    ),
                ),
            )
        val coverage = mutableListOf<TransitionCoverage>()
        val started = Instant.parse("2026-10-20T00:00:00Z")
        val recording =
            timerTrace.recording(
                "projection/1",
                listOf(definition),
                describe = { buildJsonObject { put("value", it) } },
                snapshot = { _, prefix ->
                    coverage += prefix
                    buildJsonObject {}
                },
                startedAt = started,
            )
        val timer = recording.entries.last()
        assertEquals("Timer", timer.details.source)
        assertEquals((started + 10.milliseconds).toString(), timer.at)
        assertEquals(mapOf(actron.statechart.machine.TransitionId(1) to 1L), coverage.last().hits)
        assertEquals(listOf("Cancel timer t1", "Cancel scope a1", "Emit Done"), timer.details.operations)
    }

    @Test
    fun projectionPreservesCallbackOrderPrefixCoverageOperationsAndRedaction() {
        val calls = mutableListOf<String>()
        val prefixes = mutableListOf<TransitionCoverage>()
        val originalDetails = mutableListOf<HistoryDetails>()
        val started = Instant.parse("2026-10-20T00:00:00Z")
        val recording =
            trace.recording(
                "projection/1",
                listOf(definition),
                describe = { value ->
                    calls += "describe:$value"
                    buildJsonObject { put("value", value) }
                },
                snapshot = { step, coverage ->
                    prefixes += coverage
                    calls += "snapshot:${coverage.hits.values.sum()}"
                    buildJsonObject { put("value", step.after.context) }
                },
                startedAt = started,
                details = { value ->
                    calls += "details:${value.source}"
                    originalDetails += value
                    value.copy(input = "redacted", operations = emptyList())
                },
            )
        assertEquals(
            listOf(
                "describe:0",
                "describe:0",
                "details:Runtime",
                "snapshot:0",
                "describe:0",
                "describe:0",
                "details:Command result",
                "snapshot:0",
                "describe:0",
                "describe:1",
                "details:Action",
                "snapshot:1",
                "describe:1",
                "describe:1",
                "details:Action",
                "snapshot:1",
                "describe:1",
                "describe:1",
                "details:Action",
                "snapshot:1",
            ),
            calls,
        )
        assertEquals(listOf(0L, 0L, 1L, 1L, 1L), prefixes.map { it.hits.values.sum() })
        assertEquals(listOf("Start", "Schedule", "Emit"), originalDetails.first().operations.map { it.substringBefore(' ') })
        assertEquals(listOf("Cancel timer t1", "Cancel scope a1", "Emit Done"), originalDetails[2].operations)
        assertTrue(originalDetails.last().outcome.contains("Failed"))
        assertTrue(recording.entries.all { it.details.input == "redacted" && it.details.operations.isEmpty() })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), recording.entries.map { it.id })
        assertEquals(listOf(0L, 0L, 1L, 2L, 3L).map { (started + it.milliseconds).toString() }, recording.entries.map { it.at })
        val codec = HistoryRecordingCodec("projection/1", JsonObject.serializer(), definitions = listOf(definition))
        assertEquals(recording, codec.decode(codec.encode(recording)))
    }

    @Test
    fun wrongDefinitionVersionFailsBeforeInvokingProjectionCallbacks() {
        var calls = 0
        val wrong = machine.chart.historyDefinition(machine.id.value, "other")
        assertFailsWith<IllegalArgumentException> {
            trace.recording(
                "projection/1",
                listOf(wrong),
                describe = {
                    calls++
                    buildJsonObject {}
                },
                snapshot = { _, _ ->
                    calls++
                    buildJsonObject {}
                },
                details = {
                    calls++
                    it
                },
            )
        }
        assertEquals(0, calls)
    }
}
