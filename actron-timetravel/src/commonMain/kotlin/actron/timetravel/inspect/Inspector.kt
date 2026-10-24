package actron.timetravel.inspect

import actron.core.ExperimentalActronApi
import actron.observability.ExecutionMode
import actron.observability.JournalRecord
import actron.observability.JournalStats
import actron.observability.MachineGroupId
import actron.observability.RecordSubject
import actron.observability.RecordingSession
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.observability.file.JournalFileEvent
import actron.observability.file.JournalFiles
import actron.timetravel.Recording

/**
 * The read model of an inspected history (handoff §11, v1): the Stores of one group with their
 * capabilities, the timeline of what happened in the group's order, each position with its
 * input and cause, commits, decision, commands, timers, events and failures, and, above all,
 * an explicit [completeness]: what the journal does not hold, named, so that a full recording
 * is told from a partial one.
 *
 * The journal is the source of order and of what the policy kept. A Store whose
 * [actron.timetravel.MachineRecorder] recording is attached and matches the journal's decisions
 * gets its machine snapshots before and after every decision and their difference; a recording
 * that does not match is reported and not used. The inspector holds no Store, machine or
 * scope: it is data over data, for a text renderer ([InspectorText]) or a debug UI.
 *
 * @param events The journal, in order: records and, from files, the marks of what could not be read
 * @param recordings Recordings to attach, by Store
 * @param stats The session's counters, when inspecting a live session
 */
@ExperimentalActronApi
class Inspector(
    val events: List<JournalFileEvent>,
    recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap(),
    private val statistics: InspectorStatistics = InspectorStatistics.FileHistory,
) {
    /** The journal's records, in order. */
    val records: List<JournalRecord<*, *, *>> = events.filterIsInstance<JournalFileEvent.Record>().map { it.record }

    constructor(events: List<JournalFileEvent>, recordings: Map<StoreInstanceId, Recording<*, *, *, *>>, stats: JournalStats) :
        this(events, recordings, InspectorStatistics.Live(stats))

    /** Identifies the observed run only when at least one journal record establishes it. */
    fun withRun(accept: (RuntimeSessionId, MachineGroupId, ExecutionMode) -> Unit): Boolean {
        if (records.isEmpty()) return false
        val first = records.first()
        accept(first.session, first.group, first.mode)
        return true
    }

    /** Everything that happened, in the group's order. */
    val timeline: List<TimelineItem>

    /** The Stores of the group, in the order they first appear. */
    val stores: List<StoreView>

    /** What the whole history lacks; complete when nothing. */
    val completeness: Completeness

    init {
        val journal = InspectorTimelineBuilder(events).build()
        val attached = InspectorRecordings(journal, recordings)
        timeline = attached.timeline
        val summary = InspectorSummary(journal, timeline, attached.statuses, statistics)
        stores = summary.stores
        completeness = summary.completeness
    }

    /** The timeline of one Store, with the session's own items (gaps, the stop, damage) between. */
    fun timelineOf(store: StoreInstanceId): List<TimelineItem> = timeline.filter { it.subject == store || it.subject == RecordSubject.Session }

    /**
     * Whether [store]'s history can be replayed by a `ReplaySession`, and if not, why: replay
     * decides a recording again, so it needs one that matches the journal; the journal alone is
     * `InspectOnly`.
     */
    fun replayability(store: StoreInstanceId): Availability {
        val view = stores.firstOrNull { it.id == store } ?: return Availability.Unavailable(listOf("the journal has no records of $store"))
        val reasons = mutableListOf<String>()
        when (val status = view.recording) {
            RecordingStatus.Unrecorded -> reasons += "no recording is attached for $store; the journal's capability is ${view.capabilityLabel("unknown")}, which can be shown, not replayed"
            is RecordingStatus.Mismatch -> reasons += "the recording attached for $store does not match the journal: ${status.reason}"
            is RecordingStatus.Attached -> Unit
        }
        return if (reasons.isEmpty()) Availability.Available else Availability.Unavailable(reasons)
    }

    companion object {
        /** An inspector of [records] as they are, with no marks. */
        fun of(
            records: List<JournalRecord<*, *, *>>,
            recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap(),
            statistics: InspectorStatistics = InspectorStatistics.FileHistory,
        ): Inspector = Inspector(records.map { JournalFileEvent.Record(it as JournalRecord<Nothing, Nothing, Nothing>) }, recordings, statistics)

        /** An inspector of a live session's retained records, with its counters. */
        fun of(
            session: RecordingSession,
            recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap(),
        ): Inspector = of(session.records(), recordings, InspectorStatistics.Live(session.stats))

        /** An inspector of a session read from its files, with the marks of what could not be read. */
        fun of(
            files: JournalFiles,
            session: RuntimeSessionId,
            recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap(),
        ): Inspector = Inspector(files.read(session).events, recordings)
    }
}

@ExperimentalActronApi
sealed interface InspectorStatistics {
    fun withLive(accept: (JournalStats) -> Unit)

    data object FileHistory : InspectorStatistics {
        override fun withLive(accept: (JournalStats) -> Unit) {}
    }

    data class Live(
        val stats: JournalStats,
    ) : InspectorStatistics {
        override fun withLive(accept: (JournalStats) -> Unit) {
            accept(stats)
        }
    }
}

@OptIn(ExperimentalActronApi::class)
internal fun TimelineItem.Processing.hasRevision(revision: Long): Boolean {
    var matches = false
    withRevision { matches = it == revision }
    return matches
}

@OptIn(ExperimentalActronApi::class)
internal fun TimelineItem.Processing.revisionLabel(fallback: String): String {
    var text = fallback
    withRevision { text = it.toString() }
    return text
}

@OptIn(ExperimentalActronApi::class)
internal fun StoreView.capabilityLabel(fallback: String): String {
    var text = fallback
    withCapability { text = it.toString() }
    return text
}
