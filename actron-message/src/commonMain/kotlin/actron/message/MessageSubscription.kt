package actron.message

import actron.core.Action
import actron.core.PluginLaunchScope
import actron.core.PluginScope
import actron.core.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onSubscription

/** Registers a Store-owned collector before startup continues; it survives state exits. */
internal class MessageSubscription<S : State, A : Action>(
    private val scope: PluginScope<S, A>,
    private val receive: suspend PluginLaunchScope<S, A>.(Message) -> Unit,
) {
    suspend fun start() {
        val subscribed = CompletableDeferred<Unit>()
        scope.launch {
            MessageHub.messages
                .onSubscription { subscribed.complete(Unit) }
                .collect { message -> receive(message) }
        }
        subscribed.await()
    }
}
