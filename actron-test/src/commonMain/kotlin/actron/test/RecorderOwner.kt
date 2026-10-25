package actron.test

import actron.core.Action
import actron.core.PluginScope
import actron.core.State
import kotlinx.coroutines.flow.MutableStateFlow

/** Atomically claims one Store scope before any history is recorded; clear never releases it. */
internal class RecorderOwner<S : State, A : Action> {
    private val ownership = MutableStateFlow<RecorderOwnership<S, A>>(RecorderOwnership.Unclaimed())

    fun claim(scope: PluginScope<S, A>) {
        while (true) {
            when (val current = ownership.value) {
                is RecorderOwnership.Unclaimed -> {
                    if (ownership.compareAndSet(current, RecorderOwnership.Claimed(scope))) return
                }

                is RecorderOwnership.Claimed -> {
                    check(current.scope === scope) { "[Actron] A StoreRecorder records one Store; create one per Store with createRecorder()" }
                    return
                }
            }
        }
    }
}

private sealed interface RecorderOwnership<S : State, A : Action> {
    class Unclaimed<S : State, A : Action> : RecorderOwnership<S, A>

    class Claimed<S : State, A : Action>(
        val scope: PluginScope<S, A>,
    ) : RecorderOwnership<S, A>
}
