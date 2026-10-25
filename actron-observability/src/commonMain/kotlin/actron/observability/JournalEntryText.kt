package actron.observability

import actron.observability.JournalFormat.failure
import actron.observability.JournalFormat.payload

/** Text vocabulary grouped by the processing boundaries it describes; independent of wire tags. */
internal object JournalEntryText {
    fun store(entry: JournalEntry<*, *, *>): String =
        when (entry) {
            is JournalEntry.StoreRegistered -> {
                "StoreRegistered ${entry.capability}"
            }

            is JournalEntry.InputAccepted -> {
                "InputAccepted ${entry.input} ${input(entry.kind)}"
            }

            is JournalEntry.InputDiscarded -> {
                "InputDiscarded ${entry.input} ${entry.reason.kind}${entry.reason.detailSuffix}"
            }

            is JournalEntry.ProcessingStarted -> {
                "ProcessingStarted ${entry.input} ordinal=${entry.ordinal}"
            }

            is JournalEntry.StateCommitted -> {
                "StateCommitted ${entry.input} revision=${entry.revision} ${payload(entry.state)} <- ${payload(entry.previous)}"
            }

            is JournalEntry.EventEmitted -> {
                "EventEmitted ${entry.input} ${payload(entry.event)}"
            }

            is JournalEntry.FailureReported -> {
                "FailureReported ${entry.input} ${failure(entry.failure)}"
            }

            is JournalEntry.ProcessingFinished -> {
                "ProcessingFinished ${entry.input} ordinal=${entry.ordinal} ${outcome(entry.outcome)} in ${entry.duration}"
            }

            JournalEntry.StoreClosed -> {
                "StoreClosed"
            }

            is JournalEntry.InputRejected -> {
                "InputRejected ${payload(entry.action)} ${entry.reason}"
            }

            else -> {
                error("Not a store entry: $entry")
            }
        }

    fun machine(entry: JournalEntry<*, *, *>): String =
        when (entry) {
            is JournalEntry.DecisionCommitted -> {
                decision(entry)
            }

            is JournalEntry.DecisionIgnored -> {
                "DecisionIgnored ${entry.input} ${entry.reason}"
            }

            is JournalEntry.BridgeSent -> {
                "BridgeSent ${entry.input} ${entry.message} -> ${entry.to}${if (entry.delivered) "" else " undelivered"}${entry.cause.replySuffix}"
            }

            is JournalEntry.BridgeReceived -> {
                "BridgeReceived ${entry.input} ${entry.message}"
            }

            is JournalEntry.BridgeDropped -> {
                "BridgeDropped ${entry.message} -> ${entry.to} ${entry.reason}"
            }

            is JournalEntry.EffectQueued -> {
                "EffectQueued ${entry.input} e${entry.effect} ${entry.policy} ${payload(entry.event)}"
            }

            is JournalEntry.EffectHandlingStarted -> {
                "EffectHandlingStarted e${entry.effect} attempt=${entry.attempt}"
            }

            is JournalEntry.EffectAcknowledged -> {
                "EffectAcknowledged e${entry.effect}"
            }

            is JournalEntry.EffectDiscarded -> {
                "EffectDiscarded e${entry.effect} ${entry.reason}"
            }

            is JournalEntry.ExternalReceived -> {
                "ExternalReceived ${entry.input} from ${entry.source}"
            }

            is JournalEntry.CommandsAbandoned -> {
                "CommandsAbandoned ${entry.reason} queued=${entry.queued.joinToString(",", "[", "]") { "c$it" }} running=${entry.running.joinToString(",", "[", "]") { "c$it" }}"
            }

            else -> {
                error("Not a machine entry: $entry")
            }
        }

    fun session(entry: JournalEntry<*, *, *>): String =
        when (entry) {
            is JournalEntry.CheckpointCreated -> {
                "CheckpointCreated members=${entry.members.joinToString(",", "[", "]")} sources=${entry.sources.joinToString(",", "[", "]")} inFlight=${entry.inFlight}"
            }

            is JournalEntry.JournalGap -> {
                "JournalGap dropped=${entry.dropped}"
            }

            JournalEntry.RecordingStopped -> {
                "RecordingStopped"
            }

            else -> {
                error("Not a session entry: $entry")
            }
        }

    private fun decision(entry: JournalEntry.DecisionCommitted): String =
        buildString {
            append("DecisionCommitted ").append(entry.input).append(" revision=").append(entry.revision)
            configuration(entry)
            execution(entry)
        }

    private fun StringBuilder.configuration(entry: JournalEntry.DecisionCommitted) {
        append(" active=").append(entry.active.joinToString(",", "[", "]"))
        if (entry.transitions.isNotEmpty()) append(" transitions=").append(entry.transitions.joinToString(",", "[", "]") { "T$it" })
        if (entry.exited.isNotEmpty()) append(" exited=").append(entry.exited.joinToString(",", "[", "]") { "${it.node}/a${it.activation}" })
        if (entry.entered.isNotEmpty()) append(" entered=").append(entry.entered.joinToString(",", "[", "]") { "${it.node}/a${it.activation}" })
    }

    private fun StringBuilder.execution(entry: JournalEntry.DecisionCommitted) {
        if (entry.commands.isNotEmpty()) append(" commands=").append(entry.commands.joinToString(",", "[", "]") { "c${it.id}@a${it.scope}${it.execution.journalSuffix} ${payload(it.command)}" })
        if (entry.cancelledScopes.isNotEmpty()) append(" cancelled=").append(entry.cancelledScopes.joinToString(",", "[", "]") { "a$it" })
        if (entry.timersScheduled.isNotEmpty()) append(" timers=").append(entry.timersScheduled.joinToString(",", "[", "]") { "t${it.id}:T${it.transition}@a${it.activation}+${it.deadline}" })
        if (entry.timersCancelled.isNotEmpty()) append(" timersCancelled=").append(entry.timersCancelled.joinToString(",", "[", "]") { "t$it" })
        if (entry.effects > 0) append(" effects=").append(entry.effects)
    }

    private fun input(kind: InputDescriptor<*>): String =
        when (kind) {
            InputDescriptor.Startup -> "Startup"
            is InputDescriptor.Dispatch -> "Dispatch ${payload(kind.action)}"
            is InputDescriptor.Transaction -> "Transaction origin=${kind.origin}"
            is InputDescriptor.Recovery -> "Recovery origin=${kind.origin} ${failure(kind.failure)}"
        }

    private fun outcome(outcome: OutcomeDescriptor): String =
        buildString {
            append(outcome.kind)
            if (outcome.commits > 0) append(" commits=").append(outcome.commits)
            outcome.withFailure { append(' ').append(failure(it)) }
        }
}
