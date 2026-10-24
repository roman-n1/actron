package actron.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/** Private-layout knowledge stays here; retention tests still inspect the real runtime structures. */
internal object StoreRuntimeInspection {
    fun stateRuntimes(store: Store<*, *, *>): Map<Any, Any> = field(field<Any>(field<Any>(store, "processor"), "stateScopes"), "stateRuntimes")

    fun launchJobs(store: Store<*, *, *>): Map<Any, Job> = field(stateRuntimes(store).values.single(), "actionLaunchJobs")

    fun subscriptions(store: Store<*, *, *>): Set<Job> = field(stateRuntimes(store).values.single(), "subscriptions")

    fun dispatchScope(store: Store<*, *, *>): CoroutineScope = field<Lazy<CoroutineScope>>(queue(store), "dispatchScope\$delegate").value

    fun predecessor(store: Store<*, *, *>): MutableStateFlow<Job> = field(queue(store), "lastDispatchDone")

    private fun queue(store: Store<*, *, *>): Any = field(field<Any>(store, "processor"), "dispatchQueue")

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> field(
        target: Any,
        name: String,
    ): T {
        var cls: Class<out Any> = target.javaClass
        while (true) {
            try {
                val field = checkNotNull(cls.getDeclaredField(name))
                field.isAccessible = true
                return field.get(target) as T
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass ?: break
            }
        }
        error("no field $name")
    }
}
