package actron.statechart

/** ChartState owns tokens. This runtime owns immutable timer rules and stale-firing diagnostics. */
internal class ChartStoreTimers(
    private val definition: StateChartDefinition,
) {
    private val bySource =
        definition.transitions
            .withIndex()
            .filter { it.value.isTimer }
            .groupBy({ it.value.source }, { it.index })
    private val delays =
        buildMap {
            for ((index, transition) in definition.transitions.withIndex()) {
                val trigger = transition.trigger
                if (trigger is Trigger.After) put(index, trigger.delay)
            }
        }
    var staleFirings: Int = 0
        private set

    fun indicesOf(states: List<StateId>): List<Int> = states.flatMap { bySource[it].orEmpty() }

    fun sourceOf(index: Int): StateId = definition.transitions[index].source

    fun delayOf(index: Int): kotlin.time.Duration = delays.getValue(index)

    fun issue(
        timers: ChartTimers,
        indices: List<Int>,
    ): ChartTimers {
        var issued = timers.issued
        val running = timers.running.toMutableMap()
        for (index in indices) running[index] = ++issued
        return ChartTimers(running, issued)
    }

    fun accepts(
        timers: ChartTimers,
        index: Int,
        token: Long,
    ): Boolean {
        if (timers.running[index] == token) return true
        staleFirings++
        return false
    }
}
