package actron.observability.file

import actron.observability.JournalEntry
import actron.observability.StoreInstanceId

/** Session-wide recording boundaries, gaps and checkpoints. */
internal object JournalSessionEntryCodec : JournalEntryCodec {
    override fun tagOf(entry: JournalEntry<*, *, *>): Int =
        when (entry) {
            is JournalEntry.JournalGap -> 13
            JournalEntry.RecordingStopped -> 14
            is JournalEntry.CheckpointCreated -> 22
            else -> error("Not a JournalSessionEntryCodec entry: $entry")
        }

    override fun ByteWriter.entry(
        entry: JournalEntry<*, *, *>,
    ) {
        when (entry) {
            is JournalEntry.JournalGap -> {
                i64(entry.dropped)
            }

            JournalEntry.RecordingStopped -> {
                Unit
            }

            is JournalEntry.CheckpointCreated -> {
                list(entry.members) { string(it.value) }
                list(entry.sources) { string(it) }
                i32(entry.inFlight)
            }

            else -> {
                error("Not a JournalSessionEntryCodec entry: $entry")
            }
        }
    }

    override fun ByteReader.entry(
        tag: Int,
    ): JournalEntry<Nothing, Nothing, Nothing> =
        when (tag) {
            13 -> {
                JournalEntry.JournalGap(i64())
            }

            14 -> {
                JournalEntry.RecordingStopped
            }

            22 -> {
                JournalEntry.CheckpointCreated(list { StoreInstanceId(string()) }, list { string() }, i32())
            }

            else -> {
                throw ByteReader.Malformed("unknown entry tag $tag")
            }
        }
}
