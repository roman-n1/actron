package actron.example.weather.core.component

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Parent owns the bridge; the child receives only its endpoint. No replayed events. */
class ParentBridge<TAction : Any, TNews : Any> {
    private val actions = MutableSharedFlow<TAction>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    private val news = MutableSharedFlow<TNews>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    val endpoint = BridgeEndpoint<TAction, TNews>(actions, news)

    suspend fun send(action: TAction) {
        actions.emit(action)
    }

    suspend fun sendWhenSubscribed(action: TAction) {
        actions.subscriptionCount.first { it > 0 }
        send(action)
    }

    fun trySend(action: TAction): Boolean = actions.tryEmit(action)

    fun subscribe(
        scope: CoroutineScope,
        handler: suspend (TNews) -> Unit,
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { news.collect { handler(it) } }
    }
}

data class BridgeEndpoint<TAction : Any, TNews : Any>(
    val actions: Flow<TAction>,
    val news: MutableSharedFlow<TNews>,
)
