package actron.statechart.test

import actron.core.Action

/** FIFO schedule frontier and its finite execution/depth budgets. */
internal class ScheduleFrontier<A : Action>(
    private val maxDepth: Int,
    private val maxRuns: Int,
) {
    private val queue = ArrayDeque<List<ScheduleOperation<A>>>().apply { add(emptyList()) }
    private val completed = mutableListOf<List<ScheduleOperation<A>>>()
    private var runs = 0
    private var truncated = false

    val canRun: Boolean get() = queue.isNotEmpty() && runs < maxRuns

    fun next(): List<ScheduleOperation<A>> {
        val path = queue.removeFirst()
        runs++
        return path
    }

    fun record(
        path: List<ScheduleOperation<A>>,
        next: List<ScheduleOperation<A>>,
    ) {
        when {
            next.isEmpty() -> completed += path
            path.size >= maxDepth -> truncated = true
            else -> enqueue(path, next)
        }
    }

    private fun enqueue(
        path: List<ScheduleOperation<A>>,
        next: List<ScheduleOperation<A>>,
    ) {
        for (operation in next) {
            if (queue.size + runs >= maxRuns) {
                truncated = true
                break
            }
            queue.add(path + operation)
        }
    }

    fun report(failures: List<ScheduleFailure<A>>): ScheduleReport<A> = ScheduleReport(runs, completed.toList(), failures.toList(), truncated || (queue.isNotEmpty() && failures.isEmpty()))
}
