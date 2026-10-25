package actron.observability

/**
 * One-line text rendering of records, for loggers and quick inspection. It prints only what the
 * records hold: a [Payload.Retained] payload prints its `toString()`, because the policy that
 * retained it decided the object may be shown; an omitted payload prints as `-`.
 */
object JournalFormat {
    /**
     * `[session group store #groupSeq/storeSeq +elapsed] entry`; the store part is absent for
     * records of the session itself.
     */
    fun line(record: JournalRecord<*, *, *>): String =
        buildString {
            append('[').append(record.session.value).append(' ').append(record.group.value)
            record.store.withStore { append(' ').append(it.value) }
            append(' ').append(record.groupSeq)
            record.storeSeq.appendTo(this)
            if (record.mode != ExecutionMode.Live) append(' ').append(record.mode)
            append(" +").append(record.elapsed).append("] ")
            append(entry(record.entry))
        }

    /**
     * The entry alone, without the envelope.
     */
    fun entry(entry: JournalEntry<*, *, *>): String =
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
            -> JournalEntryText.store(entry)

            is JournalEntry.DecisionCommitted,
            is JournalEntry.DecisionIgnored,
            is JournalEntry.BridgeSent,
            is JournalEntry.BridgeReceived,
            is JournalEntry.BridgeDropped,
            is JournalEntry.EffectQueued,
            is JournalEntry.EffectHandlingStarted,
            is JournalEntry.EffectAcknowledged,
            is JournalEntry.EffectDiscarded,
            is JournalEntry.ExternalReceived,
            is JournalEntry.CommandsAbandoned,
            -> JournalEntryText.machine(entry)

            is JournalEntry.CheckpointCreated,
            is JournalEntry.JournalGap,
            JournalEntry.RecordingStopped,
            -> JournalEntryText.session(entry)
        }

    /**
     * A payload: the retained object's `toString()` or the text a file kept of it, a projection's
     * label and fields, `-` when omitted, `?` when unavailable.
     */
    fun payload(payload: Payload<*>): String =
        when (payload) {
            is Payload.Retained -> payload.value.toString()
            is Payload.Described -> payload.text
            is Payload.Projected -> if (payload.fields.isEmpty()) payload.label else payload.label + payload.fields.entries.joinToString(prefix = "(", postfix = ")") { "${it.key}=${it.value}" }
            Payload.Omitted -> "-"
            Payload.Unavailable -> "?"
        }

    /**
     * A failure: `Type: message <- Cause` with `+n suppressed` when there are any.
     */
    fun failure(failure: FailureDescriptor): String =
        buildString {
            var label = "failure"
            failure.withType { label = it }
            append(label)
            failure.withMessage { append(": ").append(it) }
            failure.withCause { append(" <- ").append(failure(it)) }
            if (failure.suppressed.isNotEmpty()) append(" +").append(failure.suppressed.size).append(" suppressed")
        }
}
