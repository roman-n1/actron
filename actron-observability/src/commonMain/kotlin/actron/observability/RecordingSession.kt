@file:OptIn(InternalActronApi::class)

package actron.observability

import actron.core.Action
import actron.core.Event
import actron.core.InternalActronApi
import actron.core.State
import actron.core.StoreProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Receives the records of a [RecordingSession], in [GroupSeq] order, from the session's writer
 * coroutine. A sink may block or suspend on I/O: it delays the sinks after it and the queue, never
 * a Store. An exception thrown by [write] is counted and reported to the session's failure
 * handler; the record is skipped for that sink only, and nothing is written about the failure.
 */
fun interface JournalSink {
    suspend fun write(record: JournalRecord<*, *, *>)
}

/**
 * A failure of the recording itself. It is reported to [JournalConfig.onFailure], never recorded
 * as a journal entry and never sent to a Store's exception handler, so a broken sink or policy
 * cannot feed on its own reports.
 */
sealed interface JournalFailure {
    data class SinkFailed(
        val sink: JournalSink,
        val error: Throwable,
    ) : JournalFailure

    data class PolicyFailed(
        val store: StoreInstanceId,
        val error: Throwable,
    ) : JournalFailure
}

/**
 * Budgets of a [RecordingSession].
 *
 * The defaults come from `JournalBudgetJvmTest` (2026-09-30, JVM): a retained record under the
 * production policy (envelope, entry, descriptors, no payload) costs about 120 bytes, a publish
 * about 0.3 µs, and a Store's dispatch about 12 % more with the journal than without (it
 * publishes four records per dispatch). So the default ring of 4 000 records is about 0.5 MB and
 * holds roughly the last 1 000 dispatches of a group, and a full writer queue of 4 096 records is
 * about 0.5 MB more. A policy that retains payloads changes the arithmetic; measure with
 * [JournalStats] on the target devices and set the budgets in the application.
 *
 * @property retainedRecords How many records the session keeps in memory for inspection; the
 * oldest are evicted first, and [JournalStats.evicted] counts them
 * @property writerQueueCapacity How many records may wait for the sinks; a record that finds the
 * queue full is dropped for the sinks, counted in [JournalStats.droppedForSinks], and the next
 * record that fits is preceded by a [JournalEntry.JournalGap]
 * @property onFailure Called for every [JournalFailure]; must not throw (an exception it throws
 * is swallowed)
 */
data class JournalConfig(
    val retainedRecords: Int = 4_000,
    val writerQueueCapacity: Int = 4_096,
    val onFailure: (JournalFailure) -> Unit = {},
) {
    init {
        require(retainedRecords >= 0) { "[Actron] retainedRecords must not be negative" }
        require(writerQueueCapacity >= 1) { "[Actron] writerQueueCapacity must be at least 1" }
    }
}

/**
 * Counters of a [RecordingSession]. Loss is visible here even when no [JournalEntry.JournalGap]
 * could be queued.
 *
 * @property published Records that received a [GroupSeq]
 * @property retained Records in memory now
 * @property evicted Records pushed out of memory by [JournalConfig.retainedRecords]
 * @property droppedForSinks Records the writer's queue had no room for, gap records included
 * @property sinkFailures Exceptions thrown by sinks
 * @property policyFailures Exceptions thrown by payload policies
 * @property publishedAfterStop Records offered after [RecordingSession.close]; they were not kept
 */
data class JournalStats(
    val published: Long,
    val retained: Int,
    val evicted: Long,
    val droppedForSinks: Long,
    val sinkFailures: Long,
    val policyFailures: Long,
    val publishedAfterStop: Long,
)

/**
 * The journal of one [MachineGroupId] in one [RuntimeSessionId]: assigns [GroupSeq] and
 * [StoreSeq] at publication, keeps the last [JournalConfig.retainedRecords] records for
 * inspection, and hands every record to the [sinks] from one writer coroutine, in order.
 *
 * Publishing is the hot path: a short critical section that assigns the sequence numbers, appends
 * to the retained records and offers the record to the writer's bounded queue. No callback, I/O
 * or Store lock is touched inside it. The section is entered from the Stores' threads and does
 * not suspend, so it spins on contention; keep the number of Stores per session and the size of
 * what a policy retains within reason.
 *
 * The writer belongs to [scope], not to any Store, so records of a Store that closed are still
 * written. Closing the session ([close]) publishes [JournalEntry.RecordingStopped], lets the
 * writer drain the queue and stops it; records published after that are counted and dropped.
 *
 * @param scope Runs the writer coroutine; choose a dispatcher fit for the sinks' I/O
 * @param id The session; a new one per process run
 * @param group The group this journal orders
 * @param mode Whether the recorded Stores run live or replay
 * @param config Budgets and the failure handler
 * @param sinks Receive every record in [GroupSeq] order
 * @param timeSource Source of [JournalRecord.elapsed] and of processing durations
 */
interface JournalPublisher {
    val boundary: JournalBoundary

    fun publish(
        store: StoreInstanceId,
        entry: JournalEntry<*, *, *>,
    )

    fun publish(entry: JournalEntry<*, *, *>)

    data object Disabled : JournalPublisher {
        override val boundary: JournalBoundary = JournalBoundary.Beginning

        override fun publish(
            store: StoreInstanceId,
            entry: JournalEntry<*, *, *>,
        ) {}

        override fun publish(entry: JournalEntry<*, *, *>) {}
    }
}

class RecordingSession(
    scope: CoroutineScope,
    val id: RuntimeSessionId = RuntimeSessionId.random(),
    val group: MachineGroupId = MachineGroupId("default"),
    val mode: ExecutionMode = ExecutionMode.Live,
    private val config: JournalConfig = JournalConfig(),
    private val sinks: List<JournalSink> = emptyList(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : JournalPublisher {
    private val recordLog = JournalRecordLog(id, group, mode, config.retainedRecords, timeSource.markNow())
    private val lifecycle = JournalSessionLifecycle()
    private val probed = mutableSetOf<StoreInstanceId>()
    private var sinkFailures = 0L
    private var policyFailures = 0L
    private val delivery = JournalDeliveryQueue(scope, config.writerQueueCapacity, ::deliver)

    /**
     * Creates the probe that records a Store into this session as [store], and publishes its
     * [JournalEntry.StoreRegistered]. Prefer `StoreBuilder.recordTo` or
     * `StorePatchBuilder.recordTo`, which register the probe for you; calling this directly
     * means handling the internal probe API yourself.
     *
     * @throws IllegalArgumentException if [store] is already recorded by this session
     */
    @InternalActronApi
    fun <S : State, A : Action, E : Event> probe(
        store: StoreInstanceId,
        policy: PayloadPolicy<S, A, E>,
    ): StoreProbe<S, A, E> {
        lifecycle.locked {
            require(probed.add(store)) { "[Actron] Store $store is already recorded by this session; give every instance its own StoreInstanceId" }
        }
        publishRecord(store, JournalEntry.StoreRegistered(Capability.InspectOnly))
        return JournalProbe(this, store, policy)
    }

    /**
     * The retained records, oldest first, in [GroupSeq] order. A copy; safe to read while Stores
     * keep publishing.
     */
    fun records(): List<JournalRecord<*, *, *>> = lifecycle.locked { recordLog.snapshot() }

    /**
     * The counters as of now.
     */
    val stats: JournalStats
        get() =
            lifecycle.locked {
                JournalStats(
                    published = recordLog.published,
                    retained = recordLog.retained,
                    evicted = recordLog.evicted,
                    droppedForSinks = delivery.droppedForSinks,
                    sinkFailures = sinkFailures,
                    policyFailures = policyFailures,
                    publishedAfterStop = lifecycle.publishedAfterStop,
                )
            }

    override val boundary: JournalBoundary get() =
        lifecycle.locked {
            if (recordLog.published == 0L) JournalBoundary.Beginning else GroupSeq(recordLog.published)
        }

    /**
     * Publishes [JournalEntry.RecordingStopped], stops accepting records, writes what is queued
     * and ends the writer. Idempotent; concurrent calls await the same writer. Cancelling a caller cancels
     * only its wait; delivery continues while the writer's owning scope remains active.
     */
    suspend fun close() {
        lifecycle.close(recordLog, delivery)
    }

    internal fun now(): TimeMark = timeSource.markNow()

    internal fun reportPolicyFailure(
        store: StoreInstanceId,
        error: Throwable,
    ) {
        lifecycle.locked { policyFailures++ }
        notify(JournalFailure.PolicyFailed(store, error))
    }

    /**
     * Publishes [entry] about [store]: assigns the sequence numbers ([StoreSeq] starts with the
     * store's first record, from a probe or from here), retains the record and offers it to the
     * writer, all in the session's short critical section. For records that no probe produces,
     * such as the decisions of a replay-ready machine. After [close], counts the publication
     * without retaining or writing it.
     */
    override fun publish(
        store: StoreInstanceId,
        entry: JournalEntry<*, *, *>,
    ) = publishRecord(store, entry)

    /**
     * Publishes [entry] as a record of the session itself, about no Store: a group's cut, for
     * example. After [close], counts the publication without retaining or writing it.
     */
    override fun publish(entry: JournalEntry<*, *, *>) = publishRecord(RecordSubject.Session, entry)

    /**
     * Assigns the sequence numbers, retains the record and offers it to the writer, all under the
     * lock. [RecordSubject.Session] identifies session records. After [close], only increments the
     * after-stop counter.
     */
    internal fun publishRecord(
        store: RecordSubject,
        entry: JournalEntry<*, *, *>,
    ) {
        lifecycle.publish {
            delivery.offer(recordLog, store, entry)
        }
    }

    private suspend fun deliver(record: JournalRecord<*, *, *>) {
        for (sink in sinks) {
            try {
                sink.write(record)
            } catch (e: Exception) {
                // The writer's own cancellation ends it; any other exception is the sink's failure.
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                lifecycle.locked { sinkFailures++ }
                notify(JournalFailure.SinkFailed(sink, e))
            }
        }
    }

    private fun notify(failure: JournalFailure) {
        try {
            config.onFailure(failure)
        } catch (_: Exception) {
            // The failure handler is the last resort; its own failure has nowhere to go.
        }
    }
}
