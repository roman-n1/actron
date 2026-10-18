package actron.statechart

import actron.core.Action
import actron.core.ExperimentalActronApi

/**
 * One transition fired by [HierarchyReference]: nodes exited and entered, and the configuration
 * after it.
 */
@OptIn(ExperimentalActronApi::class)
internal class HierarchyDecision(
    val transitions: List<Transition>,
    val exited: List<StateId>,
    val entered: List<StateId>,
    val after: Set<StateId>,
) {
    constructor(transition: Transition, exited: List<StateId>, entered: List<StateId>, after: Set<StateId>) : this(listOf(transition), exited, entered, after)

    val transition get() = transitions.single()
}

/**
 * A naive re-statement of the design note's semantics for compound states, independent of the
 * library's own implementation: plain parent maps, ancestor chains, a scan over all transitions
 * per node, the LCCA from chains, deepest-first exit and recursive default entry. Only meant for
 * well-formed charts built from [RandomAction]s.
 */
@OptIn(ExperimentalActronApi::class)
internal class HierarchyReference(
    val chart: StateChartDefinition,
) {
    private val first = LinkedHashMap<StateId, StateNode>().also { map -> chart.states.forEach { if (it.id !in map) map[it.id] = it } }

    fun parent(id: StateId): StateParent = if (id in first) first.getValue(id).parent.let { if (it is StateId && it in first) it else StateParent.Root } else StateParent.Root

    /** [id], its parent, ..., up to the top; stops before repeating. */
    fun chain(id: StateId): List<StateId> {
        val chain = mutableListOf(id)
        var p = parent(id)
        while (p is StateId && p !in chain) {
            chain += p
            p = parent(p)
        }
        return chain
    }

    fun isProperAncestor(
        ancestor: StateId,
        of: StateId,
    ) = ancestor in chain(of).drop(1)

    fun depth(id: StateId) = chain(id).size - 1

    fun defaultEntry(id: StateId): List<StateId> =
        if (id !in first) {
            emptyList()
        } else {
            when (val node = first.getValue(id)) {
                is CompoundState -> listOf(node.initial) + defaultEntry(node.initial)
                else -> emptyList()
            }
        }

    fun initialConfiguration(): Set<StateId> = (chain(chart.initial).reversed() + defaultEntry(chart.initial)).toSet()

    fun leaf(configuration: Set<StateId>): StateId = configuration.single { c -> configuration.none { it != c && parent(it) == c } }

    fun fire(
        configuration: Set<StateId>,
        t: Transition,
    ): HierarchyDecision {
        val lcca: StateParent = chain(t.source).drop(1).firstOrNull { first[it] is CompoundState && isProperAncestor(it, t.target) } ?: StateParent.Root
        val exited = configuration.filter { lcca is StateParent.Root || isProperAncestor(lcca as StateId, it) }.sortedByDescending { depth(it) }
        val entered = chain(t.target).takeWhile { it != lcca }.reversed() + defaultEntry(t.target)
        return HierarchyDecision(t, exited, entered, configuration - exited.toSet() + entered)
    }

    fun step(
        configuration: Set<StateId>,
        action: RandomAction,
        guard: (String, RandomAction) -> Boolean,
    ): HierarchyDecision {
        for (node in chain(leaf(configuration))) {
            for (t in chart.transitions) {
                if (t.source != node || !matches(t.trigger, action)) continue
                if (t.guard is GuardKey && !guard((t.guard as actron.statechart.GuardKey).name, action)) continue
                return fire(configuration, t)
            }
        }
        return HierarchyDecision(emptyList(), emptyList(), emptyList(), configuration)
    }

    /** Guard calls the runtime should make, in order: lazily, in priority order. */
    fun expectedGuardCalls(
        configuration: Set<StateId>,
        action: RandomAction,
        guard: (String, RandomAction) -> Boolean,
    ): List<Pair<String, Action>> {
        val calls = mutableListOf<Pair<String, Action>>()
        for (node in chain(leaf(configuration))) {
            for (t in chart.transitions) {
                if (t.source != node || !matches(t.trigger, action)) continue
                val guard = (t.guard as? actron.statechart.GuardKey)?.name ?: return calls
                calls += guard to action
                if (guard(guard, action)) return calls
            }
        }
        return calls
    }

    /** Shortest distances to every configuration, by relaxation until nothing changes. */
    fun distances(): Map<Set<StateId>, Int> {
        val distance = mutableMapOf(initialConfiguration() to 0)
        var changed = true
        while (changed) {
            changed = false
            for ((configuration, d) in distance.toList()) {
                for (t in chart.transitions) {
                    if (t.source !in configuration) continue
                    val next = fire(configuration, t).after
                    if (d + 1 < (distance[next] ?: Int.MAX_VALUE)) {
                        distance[next] = d + 1
                        changed = true
                    }
                }
            }
        }
        return distance
    }

    fun nodeDistances(): Map<StateId, Int> {
        val result = mutableMapOf<StateId, Int>()
        for ((configuration, d) in distances()) for (id in configuration) if (d < (result[id] ?: Int.MAX_VALUE)) result[id] = d
        return result
    }

    /** The configuration whose active leaf is [leaf]. */
    fun configurationOf(leaf: StateId): Set<StateId> = chain(leaf).reversed().toSet()

    /** Transitions that may fire from [leaf], in priority order: the leaf's, then outwards. */
    fun priority(leaf: StateId): List<Transition> = chain(leaf).flatMap { node -> chart.transitions.filter { it.source == node } }

    companion object {
        private val simpleNames = mapOf(RandomAction.Ping to "Ping", RandomAction.Pong to "Pong", RandomAction.Reset to "Reset")

        /** Independent matcher semantics; non-action triggers never consume an action. */
        fun matches(
            trigger: Trigger,
            action: RandomAction,
        ): Boolean {
            if (trigger !is Trigger.OnAction) return false
            val matcher = trigger.matcher
            return when (val matching = matcher.matching) {
                ActionMatching.ByName -> {
                    matcher.name == (simpleNames[action] ?: "Go")
                }

                is ActionMatching.ByType -> {
                    when (matching.type) {
                        RandomAction::class -> true
                        RandomAction.Go::class -> action is RandomAction.Go
                        RandomAction.Ping::class -> action == RandomAction.Ping
                        RandomAction.Pong::class -> action == RandomAction.Pong
                        else -> error("unexpected matcher $matcher")
                    }
                }
            }
        }
    }
}

/** A configuration of [HistoryReference]: active nodes and what each history state remembers. */
@OptIn(ExperimentalActronApi::class)
internal data class HistoryConfiguration(
    val active: Set<StateId>,
    val history: Map<StateId, Set<StateId>> = emptyMap(),
)

/** One transition fired by [HistoryReference]. */
@OptIn(ExperimentalActronApi::class)
internal class HistoryDecision(
    val transitions: List<Transition>,
    val exited: List<StateId>,
    val entered: List<StateId>,
    val after: HistoryConfiguration,
) {
    constructor(transition: Transition, exited: List<StateId>, entered: List<StateId>, after: HistoryConfiguration) : this(listOf(transition), exited, entered, after)

    val transition get() = transitions.single()
}

/**
 * [HierarchyReference] extended with history states, again restated naively from the design note:
 * before a node is exited, every history state whose parent it is remembers the node's active
 * children (shallow) or its active descendants without active children (deep); a transition into a
 * history state enters, below the LCCA, the history's ancestors and then, for each remembered node
 * (or else its default, or else its parent's initial child), the chain from the parent down to it
 * and its initial descendants. Only meant for well-formed charts.
 */
@OptIn(ExperimentalActronApi::class)
internal class HistoryReference(
    val chart: StateChartDefinition,
) {
    val tree = HierarchyReference(chart)
    private val nodes = chart.states.associateBy { it.id }
    private val histories = chart.states.filterIsInstance<HistoryState>()

    fun initialConfiguration() = HistoryConfiguration(tree.initialConfiguration())

    fun leaf(configuration: HistoryConfiguration): StateId = tree.leaf(configuration.active)

    /** What [history] would remember if its parent were exited in [active]. */
    fun record(
        history: HistoryState,
        active: Set<StateId>,
    ): Set<StateId> =
        if (history.deep) {
            active.filter { tree.isProperAncestor(history.parent, it) && active.none { c -> tree.parent(c) == it } }.toSet()
        } else {
            active.filter { tree.parent(it) == history.parent }.toSet()
        }

    fun fire(
        configuration: HistoryConfiguration,
        t: Transition,
    ): HistoryDecision {
        val lcca: StateParent = tree.chain(t.source).drop(1).firstOrNull { nodes[it] is CompoundState && tree.isProperAncestor(it, t.target) } ?: StateParent.Root
        val exited = configuration.active.filter { lcca is StateParent.Root || tree.isProperAncestor(lcca as StateId, it) }.sortedByDescending { tree.depth(it) }
        val history = configuration.history.toMutableMap()
        for (x in exited) for (h in histories) if (h.parent == x) history[h.id] = record(h, configuration.active)
        val target = nodes.getValue(t.target)
        val entered =
            if (target is HistoryState) {
                val above =
                    tree
                        .chain(t.target)
                        .drop(1)
                        .takeWhile { it != lcca }
                        .reversed()
                val restore = history[target.id] ?: setOf((target.default as? StateId) ?: (nodes.getValue(target.parent) as CompoundState).initial)
                above + restore.flatMap { r -> tree.chain(r).takeWhile { it != target.parent }.reversed() + tree.defaultEntry(r) }
            } else {
                tree.chain(t.target).takeWhile { it != lcca }.reversed() + tree.defaultEntry(t.target)
            }
        return HistoryDecision(t, exited, entered, HistoryConfiguration(configuration.active - exited.toSet() + entered, history))
    }

    fun step(
        configuration: HistoryConfiguration,
        action: RandomAction,
        guard: (String, RandomAction) -> Boolean,
    ): HistoryDecision {
        for (node in tree.chain(leaf(configuration))) {
            for (t in chart.transitions) {
                if (t.source != node || !HierarchyReference.matches(t.trigger, action)) continue
                if (t.guard is GuardKey && !guard((t.guard as actron.statechart.GuardKey).name, action)) continue
                return fire(configuration, t)
            }
        }
        return HistoryDecision(emptyList(), emptyList(), emptyList(), configuration)
    }

    /** Shortest distances to every configuration, by a plain breadth-first search. */
    fun distances(): Map<HistoryConfiguration, Int> {
        val distance = linkedMapOf(initialConfiguration() to 0)
        val queue = ArrayDeque(listOf(initialConfiguration()))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val d = distance.getValue(current)
            for (t in chart.transitions) {
                if (t.source !in current.active) continue
                val next = fire(current, t).after
                if (next !in distance) {
                    distance[next] = d + 1
                    queue.addLast(next)
                }
            }
        }
        return distance
    }

    fun nodeDistances(): Map<StateId, Int> {
        val result = mutableMapOf<StateId, Int>()
        for ((configuration, d) in distances().toList()) for (id in configuration.active) if (d < (result[id] ?: Int.MAX_VALUE)) result[id] = d
        return result
    }
}
