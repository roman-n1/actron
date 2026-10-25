package actron.statechart.test

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Compares declaration identities and observed coverage; never infers executable rule bodies. */
internal class BehaviouralReview(
    private val after: BehaviouralSnapshot,
    private val beforeModel: JsonObject,
    private val beforeCoverage: JsonObject,
    private val beforeVersion: String,
    private val beforeMermaid: String,
    private val hasBefore: Boolean,
) {
    private val oldRows = declarations(beforeModel, "transitions")
    private val newRows = declarations(after.model, "transitions")
    private val oldCovered = covered(beforeCoverage)
    private val newCovered = covered(after.coverage)

    fun artifacts(): Map<String, String> =
        linkedMapOf(
            "behaviour.diff" to behaviour(),
            "coverage.diff" to coverage(),
            "before.mmd" to beforeMermaid,
            "after.mmd" to after.mermaid,
            "matrix.md" to
                Json
                    .parseToJsonElement(after.toJson())
                    .jsonObject
                    .getValue("matrix")
                    .jsonPrimitive.content,
            "model.actron.json" to after.toJson(),
        )

    private fun behaviour(): String =
        buildString {
            append("Definition ${after.definition}: $beforeVersion -> ${after.version}\n")
            for (key in listOf("states", "transitions")) {
                val old = declarations(beforeModel, key)
                val new = declarations(after.model, key)
                (old - new.toSet()).forEach { append("- $key $it\n") }
                (new - old.toSet()).forEach { append("+ $key $it\n") }
            }
            val initialBefore = beforeModel["initial"] ?: JsonNull
            val initialAfter = after.model.getValue("initial")
            if (initialBefore != initialAfter) append("~ initial $initialBefore -> $initialAfter\n")
            if (hasBefore && beforeModel != after.model && beforeVersion == after.version) append("VERSION BUMP REQUIRED\n")
            append("Rule bodies and runtime settings are opaque; review explicitly.\n")
        }

    private fun coverage(): String =
        buildString {
            val before = if (hasBefore) percent(oldCovered.size, oldRows.size) else "absent"
            append("Transition coverage: $before -> ${percent(newCovered.size, newRows.size)}\n")
            newRows.forEachIndexed { index, row ->
                if (index !in newCovered) append(uncovered(row)).append(row).append('\n')
            }
            append(guards())
        }

    private fun uncovered(row: JsonElement): String {
        val oldIndex = oldRows.indexOf(row)
        return when {
            oldIndex < 0 -> "NEW UNCOVERED "
            oldIndex in oldCovered -> "REGRESSED "
            else -> "UNCOVERED "
        }
    }

    private fun guards(): String =
        buildString {
            val old = guardOutcomes(beforeCoverage)
            val new = guardOutcomes(after.coverage)
            newRows.forEachIndexed { index, row ->
                if (row.jsonObject["guard"] == JsonNull) return@forEachIndexed
                for (value in listOf(false, true)) {
                    if ("$index:$value" in new) continue
                    append(guardPrefix(row, value, old))
                        .append("T$index ${row.jsonObject["guard"]} $value\n")
                }
            }
        }

    private fun guardPrefix(
        row: JsonElement,
        value: Boolean,
        old: Set<String>,
    ): String {
        val oldIndex = oldRows.indexOf(row)
        return if (oldIndex >= 0 && "$oldIndex:$value" in old) "GUARD REGRESSED " else "GUARD UNCOVERED "
    }

    private fun guardOutcomes(coverage: JsonObject): Set<String> = if ("guardOutcomes" in coverage) coverage.getValue("guardOutcomes").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content } else emptySet()

    private fun declarations(
        model: JsonObject,
        key: String,
    ): List<JsonElement> = if (key in model) model.getValue(key).jsonArray.toList() else emptyList()

    private fun covered(coverage: JsonObject): Set<Int> = if ("coveredTransitions" in coverage) coverage.getValue("coveredTransitions").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.int } else emptySet()

    private fun percent(
        count: Int,
        total: Int,
    ): String {
        // Integer formatting stays identical on JVM, JS, Wasm and Native.
        val tenths = if (total == 0) 1000L else (1000L * count + total / 2) / total
        return "${tenths / 10}.${tenths % 10}%"
    }
}
