package actron.compose

import actron.core.State
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.State as ComposeState

/**
 * Composition-owned projection: its identity follows the ViewStore, while the mapper stays
 * current without recreating the derived state. Reading the source inside the derivation keeps
 * snapshot dependencies out of the parent that merely passes the ViewStore to a child.
 */
@Composable
internal fun <S : State, T : Any> rememberStoreSelection(
    owner: ViewStore<S, *, *>,
    source: ComposeState<S>,
    mapper: (S) -> T,
): T {
    val currentMapper = rememberUpdatedState(mapper)
    val derived = remember(owner) { derivedStateOf { currentMapper.value(source.value) } }
    return derived.value
}
