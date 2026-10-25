package actron.message

import actron.core.StoreScope

/**
 * Marker interface for process-wide messages sent through Actron's shared message bus.
 */
interface Message

/**
 * Sends a [Message] to Actron's process-wide shared message bus.
 *
 * Any DSL scope that implements [StoreScope] can call this, including enter, action, exit, recover,
 * launch, and transaction scopes.
 * Messages are not replayed, so receivers that are not actively collecting when a message is sent
 * will not receive that past message.
 * Sending returns once the message is buffered for every active receiver; it suspends only when a
 * receiver has fallen 64 messages behind, so keep `receiveMessages {}` blocks short and move slow
 * work into a dispatched action.
 *
 * @param message The message to send
 */
suspend fun StoreScope.message(message: Message) {
    MessageHub.send(message)
}
