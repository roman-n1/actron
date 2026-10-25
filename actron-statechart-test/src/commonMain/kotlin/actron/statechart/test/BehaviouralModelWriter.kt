package actron.statechart.test

import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.FinalState
import actron.statechart.HistoryState
import actron.statechart.ParallelState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.StateNode
import actron.statechart.StateParent
import actron.statechart.Transition
import actron.statechart.Trigger
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Declaration-only wire representation; insertion order is part of the existing export. */
internal class BehaviouralModelWriter(
    private val chart: StateChartDefinition,
    private val actionKeys: Map<ActionMatcher, String>,
) {
    fun write(): JsonObject =
        buildJsonObject {
            put("initial", chart.initial.value)
            putJsonArray("states") { chart.states.forEach { add(state(it)) } }
            putJsonArray("transitions") { chart.transitions.forEachIndexed { index, value -> add(transition(index, value)) } }
        }

    private fun state(node: StateNode): JsonObject =
        buildJsonObject {
            put("id", node.id.value)
            put(
                "parent",
                when (val parent = node.parent) {
                    is StateId -> JsonPrimitive(parent.value)
                    StateParent.Root -> JsonNull
                },
            )
            put("kind", kind(node))
            if (node is CompoundState) put("initial", node.initial.value)
            if (node is HistoryState) {
                put("deep", node.deep)
                put(
                    "default",
                    when (val fallback = node.default) {
                        is StateId -> JsonPrimitive(fallback.value)
                        else -> JsonNull
                    },
                )
            }
        }

    private fun kind(node: StateNode): String =
        when (node) {
            is AtomicState -> "atomic"
            is FinalState -> "final"
            is CompoundState -> "compound"
            is ParallelState -> "parallel"
            is HistoryState -> "history"
        }

    private fun transition(
        index: Int,
        value: Transition,
    ): JsonObject =
        buildJsonObject {
            put("index", index)
            put("id", "transition:$index")
            put("source", value.source.value)
            put("target", value.target.value)
            put("trigger", trigger(value.trigger))
            put(
                "guard",
                when (val guard = value.guard) {
                    is actron.statechart.GuardKey -> JsonPrimitive(guard.name)
                    else -> JsonNull
                },
            )
            put(
                "effect",
                when (val effect = value.effect) {
                    is actron.statechart.EffectKey -> JsonPrimitive(effect.name)
                    else -> JsonNull
                },
            )
            put("kind", value.kind.name)
        }

    private fun trigger(trigger: Trigger): String =
        when (trigger) {
            is Trigger.OnAction -> "action:${actionKeys.getValue(trigger.matcher)}"
            is Trigger.After -> "after:${trigger.delay}"
            Trigger.Eventless -> "always"
            Trigger.Completion -> "onDone"
        }
}
