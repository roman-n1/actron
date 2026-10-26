package actron.observability.file

import actron.observability.CommandRef
import actron.observability.JournalEntry
import actron.observability.TimerRef
import actron.observability.file.JournalExecutionCodec.activation
import actron.observability.file.JournalExecutionCodec.commandExecution
import actron.observability.file.JournalExecutionCodec.duration
import actron.observability.file.JournalInputCodec.attribution
import actron.observability.file.JournalPayloadCodec.payload

/** Machine decisions, commands, activations and timers. */
internal object JournalDecisionEntryCodec : JournalEntryCodec {
    override fun tagOf(entry: JournalEntry<*, *, *>): Int =
        when (entry) {
            is JournalEntry.DecisionCommitted -> 11
            is JournalEntry.DecisionIgnored -> 12
            is JournalEntry.CommandsAbandoned -> 23
            else -> error("Not a JournalDecisionEntryCodec entry: $entry")
        }

    override fun ByteWriter.entry(
        entry: JournalEntry<*, *, *>,
    ) {
        when (entry) {
            is JournalEntry.DecisionCommitted -> {
                attribution(entry.input)
                i64(entry.revision)
                list(entry.active) { string(it) }
                list(entry.transitions) { i32(it) }
                list(entry.exited) { activation(it) }
                list(entry.entered) { activation(it) }
                list(entry.commands) {
                    i64(it.id)
                    i64(it.scope)
                    commandExecution(it.execution)
                    payload(it.command)
                }
                list(entry.cancelledScopes) { i64(it) }
                list(entry.timersScheduled) {
                    i64(it.id)
                    i32(it.transition)
                    i64(it.activation)
                    duration(it.deadline)
                }
                list(entry.timersCancelled) { i64(it) }
                i32(entry.effects)
            }

            is JournalEntry.DecisionIgnored -> {
                attribution(entry.input)
                string(entry.reason)
            }

            is JournalEntry.CommandsAbandoned -> {
                string(entry.reason)
                list(entry.queued) { i64(it) }
                list(entry.running) { i64(it) }
            }

            else -> {
                error("Not a JournalDecisionEntryCodec entry: $entry")
            }
        }
    }

    override fun ByteReader.entry(
        tag: Int,
    ): JournalEntry<Nothing, Nothing, Nothing> =
        when (tag) {
            11 -> {
                JournalEntry.DecisionCommitted(
                    input = attribution(),
                    revision = i64(),
                    active = list { string() },
                    transitions = list { i32() },
                    exited = list { activation() },
                    entered = list { activation() },
                    commands = list { CommandRef(i64(), i64(), commandExecution(), payload()) },
                    cancelledScopes = list { i64() },
                    timersScheduled = list { TimerRef(i64(), i32(), i64(), duration()) },
                    timersCancelled = list { i64() },
                    effects = i32(),
                )
            }

            12 -> {
                JournalEntry.DecisionIgnored(attribution(), string())
            }

            23 -> {
                JournalEntry.CommandsAbandoned(string(), list { i64() }, list { i64() })
            }

            else -> {
                throw ByteReader.Malformed("unknown entry tag $tag")
            }
        }
}
