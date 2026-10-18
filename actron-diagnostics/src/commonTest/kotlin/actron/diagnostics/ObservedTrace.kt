package actron.diagnostics

internal val CausalTraceEvent.failureType: String get() {
    val observations =
        buildList<String> {
            withFailureType { add(it) }
        }
    return observations.single()
}
internal val CausalTraceEvent.failureSource: FailureSource get() = assessment.faults.first().source
internal val CausalTraceEvent.selection: actron.statechart.machine.DecisionExplanation get() {
    val observations =
        buildList<actron.statechart.machine.DecisionExplanation> {
            assessment.withSelection { add(it) }
        }
    return observations.single()
}
