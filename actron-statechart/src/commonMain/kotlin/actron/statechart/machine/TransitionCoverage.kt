package actron.statechart.machine

import actron.core.Event

/** Immutable counts for one definition/version. Record committed decisions exactly once, in order. */
data class TransitionCoverage(
    val definition: DefinitionId,
    val version: DefinitionVersion,
    val hits: Map<TransitionId, Long> = emptyMap(),
) {
    init {
        require(hits.values.all { it > 0 }) { "Coverage counts must be positive" }
    }

    /** Failed/ignored decisions never count. Repeated/self/parallel transitions retain their actual identities. */
    fun <C : Any, CMD : Any, E : Event> record(decision: Decision<C, CMD, E>): TransitionCoverage {
        require(decision.snapshot.definition == definition && decision.snapshot.version == version) { "Coverage belongs to another machine or version" }
        if (!decision.isHandled) return this
        val updated = hits.toMutableMap()
        for (id in decision.transitions) {
            val count = updated.getOrElse(id) { 0 }
            updated[id] = if (count == Long.MAX_VALUE) count else count + 1
        }
        return copy(hits = updated.toMap())
    }
}
