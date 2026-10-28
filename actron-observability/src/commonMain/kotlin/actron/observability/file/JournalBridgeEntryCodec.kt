package actron.observability.file

import actron.observability.JournalEntry
import actron.observability.MessageCause
import actron.observability.StoreInstanceId
import actron.observability.file.JournalExecutionCodec.message
import actron.observability.file.JournalInputCodec.attribution

/** Bridge messages, their causal links and external inputs. */
internal object JournalBridgeEntryCodec : JournalEntryCodec {
    override fun tagOf(entry: JournalEntry<*, *, *>): Int =
        when (entry) {
            is JournalEntry.BridgeSent -> 15
            is JournalEntry.BridgeReceived -> 16
            is JournalEntry.ExternalReceived -> 21
            is JournalEntry.BridgeDropped -> 24
            else -> error("Not a JournalBridgeEntryCodec entry: $entry")
        }

    override fun ByteWriter.entry(
        entry: JournalEntry<*, *, *>,
    ) {
        when (entry) {
            is JournalEntry.BridgeSent -> {
                attribution(entry.input)
                message(entry.message)
                string(entry.to.value)
                bool(entry.delivered)
                bool(entry.cause.prompted)
                entry.cause.withMessage { message(it) }
            }

            is JournalEntry.BridgeReceived -> {
                attribution(entry.input)
                message(entry.message)
            }

            is JournalEntry.ExternalReceived -> {
                attribution(entry.input)
                string(entry.source)
            }

            is JournalEntry.BridgeDropped -> {
                message(entry.message)
                string(entry.to.value)
                string(entry.reason)
            }

            else -> {
                error("Not a JournalBridgeEntryCodec entry: $entry")
            }
        }
    }

    override fun ByteReader.entry(
        tag: Int,
    ): JournalEntry<Nothing, Nothing, Nothing> =
        when (tag) {
            15 -> {
                JournalEntry.BridgeSent(attribution(), message(), StoreInstanceId(string()), bool(), if (bool()) message() else MessageCause.Unprompted)
            }

            16 -> {
                JournalEntry.BridgeReceived(attribution(), message())
            }

            21 -> {
                JournalEntry.ExternalReceived(attribution(), string())
            }

            24 -> {
                JournalEntry.BridgeDropped(message(), StoreInstanceId(string()), string())
            }

            else -> {
                throw ByteReader.Malformed("unknown entry tag $tag")
            }
        }
}
