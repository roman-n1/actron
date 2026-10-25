package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.HistoryState
import actron.statechart.StateId
import actron.statechart.Trigger
import actron.statechart.machine.Machine
import actron.statechart.machine.TransitionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Repository-relative navigation only. Source mapping is authored by the application. */
data class ModelSource(
    val path: String,
    val line: Int,
) {
    init {
        require(
            line > 0 && path.isNotBlank() && path.none { it < ' ' } && !path.startsWith('/') && !path.contains('\\') &&
                path.split('/').none { it == ".." || it == "." || it.isBlank() } && !path.contains(':'),
        ) { "[Actron] Source must be a repository-relative path and positive line" }
    }
}

/** Versioned, metadata-only CI/IDE artifact. It cannot reconstruct executable Kotlin rules. */
class BehaviouralSnapshot internal constructor(
    private val data: JsonObject,
) {
    val definition: String get() = data.getValue("definition").jsonPrimitive.content
    val version: String get() = data.getValue("version").jsonPrimitive.content
    val mermaid: String get() = data.getValue("mermaid").jsonPrimitive.content

    fun toJson(): String = data.toString()

    internal val model: JsonObject get() = data.getValue("model").jsonObject
    internal val coverage: JsonObject get() = data.getValue("coverage").jsonObject

    companion object {
        /** Rejects unsupported formats and malformed transition identities before comparison/navigation. */
        fun fromJson(text: String): BehaviouralSnapshot {
            val data = Json.parseToJsonElement(text).jsonObject
            require(data["format"]?.jsonPrimitive?.int == 1) { "[Actron] Unsupported behavioural artifact format" }
            require(
                data
                    .getValue("definition")
                    .jsonPrimitive.content
                    .isNotBlank() &&
                    data
                        .getValue("version")
                        .jsonPrimitive.content
                        .isNotBlank(),
            )
            val model = data.getValue("model").jsonObject
            val transitions = model.getValue("transitions").jsonArray
            require(
                transitions.map {
                    it.jsonObject
                        .getValue("index")
                        .jsonPrimitive.int
                } == transitions.indices.toList(),
            ) { "[Actron] Invalid transition identity/order" }
            val states = model.getValue("states").jsonArray.map { it.jsonObject }
            val ids = states.map { it.getValue("id").jsonPrimitive.content }
            require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size && model.getValue("initial").jsonPrimitive.content in ids)
            require(
                transitions.all {
                    it.jsonObject
                        .getValue("source")
                        .jsonPrimitive.content in ids && it.jsonObject
                        .getValue("target")
                        .jsonPrimitive.content in ids
                },
            )
            val coverage = data.getValue("coverage").jsonObject
            require(coverage.getValue("coveredStates").jsonArray.all { it.jsonPrimitive.content in ids })
            require(
                coverage.getValue("guardOutcomes").jsonArray.all {
                    val parts = it.jsonPrimitive.content.split(':')
                    parts.size == 2 && parts[1] in setOf("true", "false") && parts[0].toIntOrNull()?.let { index ->
                        transitions
                            .getOrNull(index)
                            ?.jsonObject
                            ?.get("guard")
                            ?.let { guard -> guard != JsonNull } == true
                    } == true
                },
            )
            require(coverage.getValue("coveredTransitions").jsonArray.all { it.jsonPrimitive.int in transitions.indices }) { "[Actron] Coverage refers to an undeclared transition" }
            data.getValue("mermaid").jsonPrimitive.content
            data.getValue("sources").jsonObject.values.forEach { value ->
                val source = value.jsonObject
                ModelSource(source.getValue("path").jsonPrimitive.content, source.getValue("line").jsonPrimitive.int)
            }
            return BehaviouralSnapshot(data)
        }
    }
}

/**
 * Stable action identities are supplied when different typed matchers share a display name.
 * No action/context/command payloads, Kotlin class names or rule bodies are exported.
 */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.behaviouralSnapshot(
    coverage: MachineCoverage,
    sources: Map<String, ModelSource> = emptyMap(),
    active: Set<StateId> = emptySet(),
    selectedTransitions: Set<TransitionId> = emptySet(),
    actionKey: (ActionMatcher) -> String = { it.name },
): BehaviouralSnapshot {
    require(coverage.definition == id && coverage.version == version) { "[Actron] Coverage belongs to another definition/version" }
    val expected = chart.transitions.indices.mapTo(linkedSetOf(), ::TransitionId)
    require(coverage.transitions.expected == expected && coverage.transitions.covered.all { it in expected }) { "[Actron] Coverage/model declarations differ" }
    val stateIds = chart.states.filter { it !is HistoryState }.mapTo(linkedSetOf()) { it.id }
    require(coverage.states.expected == stateIds && coverage.states.covered.all { it in stateIds } && active.all { it in stateIds }) { "[Actron] State coverage/active nodes differ from declarations" }
    require(selectedTransitions.all { it in expected }) { "[Actron] Selected transition is undeclared" }
    val matchers =
        chart.transitions
            .map { it.trigger }
            .filterIsInstance<actron.statechart.Trigger.OnAction>()
            .map { it.matcher }
            .distinct()
    val keys = matchers.associateWith(actionKey)
    require(keys.values.all { it.isNotBlank() } && keys.values.distinct().size == keys.size) { "[Actron] Typed matchers need distinct stable action keys" }
    val model = BehaviouralModelWriter(chart, keys).write()
    return BehaviouralSnapshot(snapshotData(coverage, sources, active, selectedTransitions, model))
}

/** Data-only artifacts; callers write/upload them with ordinary build tooling. */
fun behaviouralReviewArtifacts(after: BehaviouralSnapshot): Map<String, String> =
    reviewArtifacts(
        after,
        JsonObject(emptyMap()),
        JsonObject(emptyMap()),
        "absent",
        "stateDiagram-v2\n",
        false,
    )

fun behaviouralReviewArtifacts(
    before: BehaviouralSnapshot,
    after: BehaviouralSnapshot,
): Map<String, String> {
    require(before.definition == after.definition) { "[Actron] Review requires one logical definition" }
    return reviewArtifacts(after, before.model, before.coverage, before.version, before.mermaid, true)
}

private fun reviewArtifacts(
    after: BehaviouralSnapshot,
    beforeModel: JsonObject,
    beforeCoverage: JsonObject,
    beforeVersion: String,
    beforeMermaid: String,
    hasBefore: Boolean,
): Map<String, String> = BehaviouralReview(after, beforeModel, beforeCoverage, beforeVersion, beforeMermaid, hasBefore).artifacts()
