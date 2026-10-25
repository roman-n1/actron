package actron.diagnostics

import actron.observability.FailureDescriptor
import actron.observability.FailureDetails
import actron.observability.FailureRelation
import actron.statechart.GuardCheck
import actron.statechart.machine.DecisionExplanation

/** Takes ownership of observed collections and strips private failure details before retention. */
internal fun DecisionExplanation.diagnosticSnapshot(): DecisionExplanation =
    copy(
        active = active.toSet(),
        candidates = candidates.toList(),
        guards =
            guards.map { guard ->
                val result = guard.result
                guard.copy(result = if (result is GuardCheck.Failed) GuardCheck.Failed(result.failure.diagnosticSnapshot(emptyList())) else result)
            },
    )

private fun FailureDescriptor.diagnosticSnapshot(ancestors: List<FailureDescriptor>): FailureDescriptor {
    var details: FailureDetails = FailureDetails.Redacted
    withType { details = FailureDetails.MetadataOnly(it) }
    val path = ancestors + this
    val relations =
        related.filter { relation -> path.none { it === relation.failure } }.map { relation ->
            val failure = relation.failure.diagnosticSnapshot(path)
            when (relation) {
                is FailureRelation.Cause -> FailureRelation.Cause(failure)
                is FailureRelation.Suppressed -> FailureRelation.Suppressed(failure)
            }
        }
    // Compare ancestors by identity: public descriptors may contain mutable, cyclic relation lists.
    // Do not retain a back-edge to the caller's graph or disclose an anonymous failure's type.
    return FailureDescriptor(details, relations)
}
