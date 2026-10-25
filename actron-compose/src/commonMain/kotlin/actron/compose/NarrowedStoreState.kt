package actron.compose

import androidx.compose.runtime.State as ComposeState

/**
 * Reads [source] (so Compose still tracks it) and returns its value while it is of type [N],
 * otherwise the last value that was.
 */
internal class NarrowedStoreState<T : Any, N : T>(
    private val source: ComposeState<T>,
    private val isNarrowed: (T) -> Boolean,
) : ComposeState<N> {
    // Seeded at creation: a callback that never read `state` during composition must still
    // find the last narrowed value after the Store moved on.
    private var last: N = source.value.narrowed()

    override val value: N
        get() {
            val current = source.value
            if (isNarrowed(current)) last = current.narrowed()
            return last
        }

    @Suppress("UNCHECKED_CAST")
    private fun T.narrowed(): N = this as N
}
