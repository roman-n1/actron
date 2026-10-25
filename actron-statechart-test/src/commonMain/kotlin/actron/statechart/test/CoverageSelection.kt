package actron.statechart.test

/** Bounded exact set cover; a complete greedy incumbent preserves coverage on exhaustion. */
internal class CoverageSelection<T : Any>(
    private val candidates: List<Set<T>>,
    private val lengths: List<Int>,
    private val target: Set<T>,
    private val maxAttempts: Int,
) {
    private var best = greedy()
    private var attempts = 0
    private var exhausted = false
    private val visited = mutableMapOf<Set<T>, Int>()

    fun run(): Selection {
        if (target.isEmpty()) return Selection(emptyList(), 0, true)
        search(target, emptyList())
        return Selection(best.sorted(), attempts, !exhausted)
    }

    private fun greedy(): List<Int> {
        var remaining = target
        val selected = mutableListOf<Int>()
        while (remaining.isNotEmpty()) {
            val best =
                candidates.indices.maxWithOrNull(
                    compareBy<Int>(
                        { (candidates[it] intersect remaining).size },
                        { -lengths[it] },
                        { -it },
                    ),
                ) ?: error("[Actron] No scenario covers $remaining")
            check(candidates[best].any { it in remaining }) { "[Actron] Scenario coverage is inconsistent" }
            selected += best
            remaining = remaining - candidates[best]
        }
        return selected.toList()
    }

    private fun search(
        missing: Set<T>,
        chosen: List<Int>,
    ) {
        if (attempts >= maxAttempts) {
            exhausted = true
            return
        }
        attempts++
        if (missing.isEmpty()) {
            if (chosen.size < best.size) best = chosen
            return
        }
        if (!canImprove(missing, chosen.size)) return
        visited[missing] = chosen.size
        for (index in choices(missing)) {
            search(missing - candidates[index], chosen + index)
            if (exhausted) return
        }
    }

    private fun canImprove(
        missing: Set<T>,
        size: Int,
    ): Boolean {
        if (size >= best.size) return false
        return missing !in visited || visited.getValue(missing) > size
    }

    private fun choices(missing: Set<T>): List<Int> {
        val next = missing.minBy { id -> candidates.count { id in it } }
        return candidates.indices.filter { next in candidates[it] }.sortedWith(
            compareByDescending<Int> { (candidates[it] intersect missing).size }
                .thenBy { lengths[it] }
                .thenBy { it },
        )
    }
}
