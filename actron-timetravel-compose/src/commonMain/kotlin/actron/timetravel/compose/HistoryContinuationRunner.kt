package actron.timetravel.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** History is changed only after restoration succeeds; failure keeps the selected future intact. */
internal class HistoryContinuationRunner {
    var running by mutableStateOf(false)
        private set
    var failure by mutableStateOf("")
        private set

    fun continueFrom(
        scope: CoroutineScope,
        restore: suspend () -> Unit,
        completed: () -> Unit,
    ) {
        if (running) return
        running = true
        failure = ""
        scope.launch {
            try {
                runCatching { restore() }
                    .onSuccess { completed() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        failure = it.message.orEmpty()
                    }
            } finally {
                running = false
            }
        }
    }
}
