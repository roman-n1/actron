package actron.message

/**
 * Marker interface for process-wide messages sent through Actron's shared message bus.
 */
interface Message

/**
 * Sends a [Message] to Actron's process-wide shared message bus.
 *
 * Call from a machine command handler or another application-owned coroutine.
 * Sending messages is I/O and must not run inside a pure machine decision.
 * Messages are not replayed, so receivers that are not actively collecting when a message is sent
 * will not receive that past message.
 * Sending returns once the message is buffered for every active receiver; it suspends only when a
 * receiver has fallen 64 messages behind, so keep `receiveMessages {}` blocks short and move slow
 * work into a dispatched action.
 *
 * @param message The message to send
 */
suspend fun message(message: Message) {
    MessageHub.send(message)
}
