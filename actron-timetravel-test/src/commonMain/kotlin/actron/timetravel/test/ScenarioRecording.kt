package actron.timetravel.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.TransitionCoverage
import actron.statechart.test.ScenarioStep
import actron.statechart.test.ScenarioTrace
import actron.timetravel.HistoryDefinition
import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryExportSettings
import actron.timetravel.HistoryMachine
import actron.timetravel.HistoryRecord
import actron.timetravel.HistoryRecording
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Projects a recorded test into the existing view-only history format. The host chooses display
 * snapshots and redacts fields; no commands, handlers or resumable checkpoints are serialized.
 * Logical input times are offsets from [startedAt]. Failed/ignored decisions remain visible.
 */
fun <C : Any, A : Action, CMD : Any, E : Event, S : Any> ScenarioTrace<C, A, CMD, E>.recording(
    schema: String,
    definitions: List<HistoryDefinition>,
    describe: (C) -> JsonElement,
    snapshot: (ScenarioStep<C, A, CMD, E>, TransitionCoverage) -> S,
    settings: HistoryExportSettings = HistoryExportSettings(),
    machineName: String = initial.definition.value,
    startedAt: Instant = Clock.System.now(),
    details: (HistoryDetails) -> HistoryDetails = { it },
): HistoryRecording<S> {
    require(definitions.any { it.id == initial.definition.value && it.version == initial.version.value }) {
        "[Actron] Recording requires the tested machine's definition/version"
    }
    val identity = HistoryMachine(1, machineName, initial.definition.value)
    var coverage = TransitionCoverage(initial.definition, initial.version)

    val projection = ScenarioHistoryDetails(describe)
    val entries =
        steps.mapIndexed { index, step ->
            val decision = step.explained.decision
            coverage = coverage.record(decision)
            val input = step.input
            HistoryRecord(
                (index + 1).toLong(),
                identity,
                0,
                (startedAt + input.now.sinceStart).toString(),
                details(projection.project(step)),
                snapshot(step, coverage),
            )
        }
    return HistoryRecording(schema, entries, settings.metadata(), definitions = definitions)
}
