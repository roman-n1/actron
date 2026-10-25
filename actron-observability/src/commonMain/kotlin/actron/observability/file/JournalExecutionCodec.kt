package actron.observability.file

import actron.observability.ActivationRef
import actron.observability.CommandExecution
import actron.observability.MessageRef
import actron.observability.StoreInstanceId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Machine identities, command execution and monotonic durations on the wire. */
internal object JournalExecutionCodec {
    fun ByteWriter.message(message: MessageRef) {
        string(message.from.value)
        i64(message.effect)
    }

    fun ByteReader.message(): MessageRef = MessageRef(StoreInstanceId(string()), i64())

    fun ByteWriter.commandExecution(execution: CommandExecution) {
        when (execution) {
            CommandExecution.Independent -> {
                bool(false)
                bool(false)
            }

            is CommandExecution.InLane -> {
                bool(true)
                string(execution.lane)
                bool(true)
                string(execution.policy)
            }
        }
    }

    fun ByteReader.commandExecution(): CommandExecution {
        if (!bool()) {
            if (bool()) throw ByteReader.Malformed("command policy without a lane")
            return CommandExecution.Independent
        }
        val lane = string()
        if (!bool()) throw ByteReader.Malformed("command lane without a policy")
        return CommandExecution.InLane(lane, string())
    }

    fun ByteWriter.activation(ref: ActivationRef) {
        string(ref.node)
        i64(ref.activation)
    }

    fun ByteReader.activation(): ActivationRef = ActivationRef(string(), i64())

    fun ByteWriter.duration(duration: Duration) = i64(duration.inWholeNanoseconds)

    fun ByteReader.duration(): Duration = i64().nanoseconds
}
