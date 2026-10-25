package actron.statechart

import kotlinx.coroutines.Job

/** Resolves restore compatibility and allocates every initial activation before hooks run. */
internal class ChartStoreStartup<C : Any>(
    definition: StateChartDefinition,
    declared: ChartState<C>,
    restored: ChartState<C>,
) {
    // Incompatible active nodes restart with restored context; history is pruned independently.
    private val restart = restored !== declared && !definition.hasRestorableActiveNodes(restored.configuration)
    val fresh: Boolean = restored === declared || restart
    val state: ChartState<C> =
        when {
            restart -> {
                declared.copy(context = restored.context)
            }

            restored === declared -> {
                restored
            }

            else -> {
                val consistent = definition.pruneInvalidHistory(restored.configuration)
                if (consistent == restored.configuration) restored else restored.copy(configuration = consistent)
            }
        }
    val active: List<StateId> = definition.inEntryOrder(state.configuration.active)

    // A failing outer hook must not leave later nodes without an activation.
    val entered: Map<StateId, Job> = active.associateWith { Job() }
}
