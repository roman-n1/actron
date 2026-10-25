package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.StateId
import actron.statechart.machine.Machine
import actron.statechart.machine.TransitionId
import actron.statechart.toMermaid
import actron.statechart.transitionMatrix
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Stable artifact envelope and coverage/source projections, independent of model declarations. */
internal fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.snapshotData(
    coverage: MachineCoverage,
    sources: Map<String, ModelSource>,
    active: Set<StateId>,
    selectedTransitions: Set<TransitionId>,
    model: JsonObject,
): JsonObject =
    buildJsonObject {
        put("format", 1)
        put("definition", id.value)
        put("version", version.value)
        put("model", model)
        putJsonArray("active") { active.sortedBy { it.value }.forEach { add(it.value) } }
        putJsonArray("selectedTransitions") { selectedTransitions.sortedBy { it.index }.forEach { add("transition:${it.index}") } }
        put("mermaid", chart.toMermaid(active))
        put("matrix", chart.transitionMatrix().toMarkdown())
        putJsonObject("coverage") {
            putJsonArray("coveredStates") {
                coverage.states.covered
                    .sortedBy { it.value }
                    .forEach { add(it.value) }
            }
            putJsonArray("coveredTransitions") {
                coverage.transitions.covered
                    .sortedBy { it.index }
                    .forEach { add(it.index) }
            }
            putJsonArray("guardOutcomes") {
                coverage.guards.covered
                    .sortedWith(compareBy({ it.transition.index }, { it.result }))
                    .forEach { add("${it.transition.index}:${it.result}") }
            }
        }
        putJsonObject("sources") {
            sources.entries.sortedBy { it.key }.forEach { (key, source) ->
                putJsonObject(key) {
                    put("path", source.path)
                    put("line", source.line)
                }
            }
        }
    }
