package actron.statechart

internal fun StateChartDefinition.shortestPathTo(target: StateId): StateChartPath {
    val observations = buildList<StateChartPath> {
        kotlin.test.assertTrue(shortestPathTo(target) { add(it) })
    }
    return observations.single()
}
