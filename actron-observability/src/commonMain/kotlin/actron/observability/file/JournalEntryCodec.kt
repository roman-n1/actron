package actron.observability.file

import actron.observability.JournalEntry

/** A fixed wire vocabulary: each family owns its tags and the fields under those tags. */
internal sealed interface JournalEntryCodec {
    fun tagOf(entry: JournalEntry<*, *, *>): Int

    // Only the current field vocabulary is supported.
    fun ByteWriter.entry(
        entry: JournalEntry<*, *, *>,
    )

    fun ByteReader.entry(
        tag: Int,
    ): JournalEntry<Nothing, Nothing, Nothing>
}

internal object JournalEntryCodecs {
    fun forEntry(entry: JournalEntry<*, *, *>): JournalEntryCodec =
        when (entry) {
            is JournalEntry.StoreRegistered,
            is JournalEntry.InputAccepted,
            is JournalEntry.InputDiscarded,
            is JournalEntry.ProcessingStarted,
            is JournalEntry.StateCommitted,
            is JournalEntry.EventEmitted,
            is JournalEntry.FailureReported,
            is JournalEntry.ProcessingFinished,
            JournalEntry.StoreClosed,
            is JournalEntry.InputRejected,
            -> JournalStoreEntryCodec

            is JournalEntry.DecisionCommitted,
            is JournalEntry.DecisionIgnored,
            is JournalEntry.CommandsAbandoned,
            -> JournalDecisionEntryCodec

            is JournalEntry.JournalGap,
            JournalEntry.RecordingStopped,
            is JournalEntry.CheckpointCreated,
            -> JournalSessionEntryCodec

            is JournalEntry.BridgeSent,
            is JournalEntry.BridgeReceived,
            is JournalEntry.ExternalReceived,
            is JournalEntry.BridgeDropped,
            -> JournalBridgeEntryCodec

            is JournalEntry.EffectQueued,
            is JournalEntry.EffectHandlingStarted,
            is JournalEntry.EffectAcknowledged,
            is JournalEntry.EffectDiscarded,
            -> JournalEffectEntryCodec
        }

    fun forTag(tag: Int): JournalEntryCodec =
        when (tag) {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10 -> JournalStoreEntryCodec
            11, 12, 23 -> JournalDecisionEntryCodec
            13, 14, 22 -> JournalSessionEntryCodec
            15, 16, 21, 24 -> JournalBridgeEntryCodec
            17, 18, 19, 20 -> JournalEffectEntryCodec
            else -> throw ByteReader.Malformed("unknown entry tag $tag")
        }
}
