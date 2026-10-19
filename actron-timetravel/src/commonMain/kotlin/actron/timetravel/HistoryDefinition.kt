package actron.timetravel

import actron.statechart.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Explicit semantic version plus a portable structural description. Handler code requires a version bump. */
@Serializable
data class HistoryDefinition(
    val id: String,
    val version: String,
    val structure: JsonObject,
)

/** Preserves declaration order: transition indices in coverage refer to this exact structure. */
fun StateChartDefinition.historyDefinition(
    id: String,
    version: String,
): HistoryDefinition {
    fun parent(value: StateParent): String =
        when (value) {
            StateParent.Root -> ""
            is StateId -> value.value
        }
    return HistoryDefinition(
        id,
        version,
        buildJsonObject {
            put("initial", initial.value)
            putJsonArray("states") {
                states.forEach { state ->
                    add(
                        buildJsonObject {
                            put("id", state.id.value)
                            put("parent", parent(state.parent))
                            put(
                                "kind",
                                when (state) {
                                    is AtomicState -> "atomic"
                                    is FinalState -> "final"
                                    is CompoundState -> "compound"
                                    is ParallelState -> "parallel"
                                    is HistoryState -> "history"
                                },
                            )
                            when (state) {
                                is CompoundState -> {
                                    put("initial", state.initial.value)
                                }

                                is HistoryState -> {
                                    put("deep", state.deep)
                                    put(
                                        "default",
                                        when (val fallback = state.default) {
                                            HistoryFallback.InitialConfiguration -> ""
                                            is StateId -> fallback.value
                                        },
                                    )
                                }

                                else -> {
                                    Unit
                                }
                            }
                        },
                    )
                }
            }
            putJsonArray("transitions") {
                transitions.forEach { edge ->
                    add(
                        buildJsonObject {
                            put("source", edge.source.value)
                            put("target", edge.target.value)
                            put("kind", edge.kind.name)
                            put("guard", edge.guard.displayLabel)
                            put("guarded", edge.guard != GuardCondition.Unconditional)
                            put("effect", edge.effect.displayLabel)
                            put("hasEffect", edge.effect != TransitionEffect.NoEffect)
                            when (val trigger = edge.trigger) {
                                Trigger.Eventless -> {
                                    put("trigger", "eventless")
                                }

                                Trigger.Completion -> {
                                    put("trigger", "completion")
                                }

                                is Trigger.After -> {
                                    put("trigger", "after")
                                    put("delay", trigger.delay.toString())
                                }

                                is Trigger.OnAction -> {
                                    put("trigger", "action")
                                    put("name", trigger.matcher.name)
                                    put(
                                        "matching",
                                        when (trigger.matcher.matching) {
                                            ActionMatching.ByName -> "name"
                                            is ActionMatching.ByType -> "type"
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
    )
}
