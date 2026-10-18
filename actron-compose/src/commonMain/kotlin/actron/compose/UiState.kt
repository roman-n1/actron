package actron.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * Observes immutable store state on the UI dispatcher, independently of the machine's
 * processing dispatcher. Collection belongs to the composition and stops when it leaves.
 * The host must provide Dispatchers.Main (Android, Swing, iOS, or browser).
 * This does not relocate a composition: create/render the UI on its platform UI thread.
 */
@Composable
fun <S : Any> StateFlow<S>.collectAsUiState(): State<S> = collectAsState(context = Dispatchers.Main.immediate)
