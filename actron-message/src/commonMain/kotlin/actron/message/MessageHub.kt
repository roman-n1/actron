package actron.message

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Owns the process-wide, non-replaying bus and its shared backpressure policy. */
internal object MessageHub {
    // Keep 64 pending messages in addition to whatever each callback is currently handling.
    private const val BUFFER_CAPACITY = 64
    private val mutableMessages = MutableSharedFlow<Message>(extraBufferCapacity = BUFFER_CAPACITY)
    val messages: SharedFlow<Message> get() = mutableMessages

    suspend fun send(message: Message) {
        mutableMessages.emit(message)
    }
}
