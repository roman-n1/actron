package actron.observability.file

import actron.core.InputId
import actron.observability.Capability
import actron.observability.JournalEntry
import actron.observability.file.JournalExecutionCodec.duration
import actron.observability.file.JournalInputCodec.attribution
import actron.observability.file.JournalInputCodec.discard
import actron.observability.file.JournalInputCodec.input
import actron.observability.file.JournalInputCodec.outcome
import actron.observability.file.JournalPayloadCodec.failure
import actron.observability.file.JournalPayloadCodec.payload

/** Store processing boundaries and their payloads. */
internal object JournalStoreEntryCodec : JournalEntryCodec {
    override fun tagOf(entry: JournalEntry<*, *, *>): Int =
        when (entry) {
            is JournalEntry.StoreRegistered -> 1
            is JournalEntry.InputAccepted -> 2
            is JournalEntry.InputDiscarded -> 3
            is JournalEntry.ProcessingStarted -> 4
            is JournalEntry.StateCommitted -> 5
            is JournalEntry.EventEmitted -> 6
            is JournalEntry.FailureReported -> 7
            is JournalEntry.ProcessingFinished -> 8
            JournalEntry.StoreClosed -> 9
            is JournalEntry.InputRejected -> 10
            else -> error("Not a JournalStoreEntryCodec entry: $entry")
        }

    override fun ByteWriter.entry(
        entry: JournalEntry<*, *, *>,
        recordFormatVersion: Int,
    ) {
        when (entry) {
            is JournalEntry.StoreRegistered -> {
                string(entry.capability.name)
            }

            is JournalEntry.InputAccepted -> {
                i64(entry.input.value)
                input(entry.kind)
            }

            is JournalEntry.InputDiscarded -> {
                i64(entry.input.value)
                string(entry.reason.kind.name)
                bool(entry.reason.hasFailure)
                entry.reason.withFailure { failure(it) }
            }

            is JournalEntry.ProcessingStarted -> {
                i64(entry.input.value)
                i64(entry.ordinal)
            }

            is JournalEntry.StateCommitted -> {
                i64(entry.input.value)
                i64(entry.revision)
                payload(entry.previous)
                payload(entry.state)
            }

            is JournalEntry.EventEmitted -> {
                attribution(entry.input)
                payload(entry.event)
            }

            is JournalEntry.FailureReported -> {
                attribution(entry.input)
                failure(entry.failure)
            }

            is JournalEntry.ProcessingFinished -> {
                processingFinished(entry)
            }

            JournalEntry.StoreClosed -> {
                Unit
            }

            is JournalEntry.InputRejected -> {
                payload(entry.action)
                string(entry.reason)
            }

            else -> {
                error("Not a JournalStoreEntryCodec entry: $entry")
            }
        }
    }

    override fun ByteReader.entry(
        tag: Int,
        recordFormatVersion: Int,
    ): JournalEntry<Nothing, Nothing, Nothing> =
        when (tag) {
            1 -> {
                JournalEntry.StoreRegistered(enumNamed<Capability>(string()))
            }

            2 -> {
                JournalEntry.InputAccepted(InputId(i64()), input())
            }

            3 -> {
                JournalEntry.InputDiscarded(InputId(i64()), discard())
            }

            4 -> {
                JournalEntry.ProcessingStarted(InputId(i64()), i64())
            }

            5 -> {
                JournalEntry.StateCommitted(InputId(i64()), i64(), payload(), payload())
            }

            6 -> {
                JournalEntry.EventEmitted(attribution(), payload())
            }

            7 -> {
                JournalEntry.FailureReported(attribution(), failure())
            }

            8 -> {
                JournalEntry.ProcessingFinished(InputId(i64()), i64(), outcome(), duration())
            }

            9 -> {
                JournalEntry.StoreClosed
            }

            10 -> {
                JournalEntry.InputRejected(payload(), string())
            }

            else -> {
                throw ByteReader.Malformed("unknown entry tag $tag")
            }
        }

    private fun ByteWriter.processingFinished(entry: JournalEntry.ProcessingFinished) {
        i64(entry.input.value)
        i64(entry.ordinal)
        string(entry.outcome.kind.name)
        i32(entry.outcome.commits)
        bool(entry.outcome.hasFailure)
        entry.outcome.withFailure { failure(it) }
        duration(entry.duration)
    }
}
