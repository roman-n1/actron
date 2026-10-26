package actron.observability.file

import actron.observability.JournalEntry
import actron.observability.file.JournalInputCodec.attribution
import actron.observability.file.JournalPayloadCodec.payload

/** Effect mailbox delivery and acknowledgement. */
internal object JournalEffectEntryCodec : JournalEntryCodec {
    override fun tagOf(entry: JournalEntry<*, *, *>): Int =
        when (entry) {
            is JournalEntry.EffectQueued -> 17
            is JournalEntry.EffectHandlingStarted -> 18
            is JournalEntry.EffectAcknowledged -> 19
            is JournalEntry.EffectDiscarded -> 20
            else -> error("Not a JournalEffectEntryCodec entry: $entry")
        }

    override fun ByteWriter.entry(
        entry: JournalEntry<*, *, *>,
    ) {
        when (entry) {
            is JournalEntry.EffectQueued -> {
                attribution(entry.input)
                i64(entry.effect)
                string(entry.policy)
                payload(entry.event)
            }

            is JournalEntry.EffectHandlingStarted -> {
                i64(entry.effect)
                i32(entry.attempt)
            }

            is JournalEntry.EffectAcknowledged -> {
                i64(entry.effect)
            }

            is JournalEntry.EffectDiscarded -> {
                i64(entry.effect)
                string(entry.reason)
            }

            else -> {
                error("Not a JournalEffectEntryCodec entry: $entry")
            }
        }
    }

    override fun ByteReader.entry(
        tag: Int,
    ): JournalEntry<Nothing, Nothing, Nothing> =
        when (tag) {
            17 -> {
                JournalEntry.EffectQueued(attribution(), i64(), string(), payload())
            }

            18 -> {
                JournalEntry.EffectHandlingStarted(i64(), i32())
            }

            19 -> {
                JournalEntry.EffectAcknowledged(i64())
            }

            20 -> {
                JournalEntry.EffectDiscarded(i64(), string())
            }

            else -> {
                throw ByteReader.Malformed("unknown entry tag $tag")
            }
        }
}
