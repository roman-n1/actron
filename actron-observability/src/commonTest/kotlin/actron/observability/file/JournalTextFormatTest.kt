package actron.observability.file

import actron.observability.JournalFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exact output captured before splitting the text formatter, independent of the binary writer. */
class JournalTextFormatTest {
    @Test
    fun everyEntryAndLineKeepsItsOriginalText() {
        val bytes = (JournalWireGolden.HEADER_V7 + JournalWireGolden.framesV7.joinToString("") + "0000000000000000").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val records = JournalFileFormat.decodeSegment("text", bytes).records
        assertEquals(entries, records.map { JournalFormat.entry(it.entry) })
        assertEquals(lines, records.map { JournalFormat.line(it) })
    }

    private val entries =
        listOf(
            "StoreRegistered InspectOnly",
            "InputAccepted #1 Startup",
            "InputAccepted #2 Dispatch Load(q=cats, n=3)",
            "InputAccepted #3 Transaction origin=#2",
            "InputAccepted #4 Recovery origin=Unattributed IOException: boom <- SocketException +1 suppressed",
            "InputDiscarded #4 Rejected IllegalStateException",
            "InputDiscarded #5 StoreClosed",
            "ProcessingStarted #2 ordinal=1",
            "StateCommitted #2 revision=1 ? <- -",
            "EventEmitted Unattributed Loaded(items=[tom, felix])",
            "FailureReported #2 failure",
            "ProcessingFinished #2 ordinal=1 Recovered commits=2 X in 812us",
            "ProcessingFinished #3 ordinal=2 Unchanged in 0s",
            "StoreClosed",
            "InputRejected Tap pending=8 limit=8",
            "DecisionCommitted #5 revision=3 active=[Root,Loading] transitions=[T0,T2] exited=[Idle/a2] entered=[Loading/a3] commands=[c1@a3 net/Latest Fetch(cats),c2@a3 -] cancelled=[a2] timers=[t1:T3@a3+10s] timersCancelled=[t7] effects=2",
            "DecisionIgnored Unattributed NoTransition",
            "JournalGap dropped=3",
            "BridgeSent #5 store-a/e1 -> root-1 reply-to=root-1/e4",
            "BridgeSent Unattributed store-a/e2 -> nobody undelivered",
            "BridgeReceived #6 root-1/e1",
            "EffectQueued #6 e4 Retained Navigate(to=chat)",
            "EffectHandlingStarted e4 attempt=2",
            "EffectAcknowledged e4",
            "EffectDiscarded e5 Superseded",
            "ExternalReceived #7 from paging:chat-1",
            "CheckpointCreated members=[store-a,root-1] sources=[paging:chat-1] inFlight=1",
            "RecordingStopped",
            "CommandsAbandoned StoreClosed queued=[c3,c4] running=[c2]",
            "BridgeDropped root-1/e3 -> store-a StoreClosed",
            "FailureReported Unattributed failure: ошибка 🌍",
            "EventEmitted #7 данные(z=末, a=)",
        )

    private val lines =
        listOf(
            "[s1 g store-a #1/1 +1ms] StoreRegistered InspectOnly",
            "[s1 g store-a #2/2 +2ms] InputAccepted #1 Startup",
            "[s1 g store-a #3/3 +3ms] InputAccepted #2 Dispatch Load(q=cats, n=3)",
            "[s1 g store-a #4/4 +4ms] InputAccepted #3 Transaction origin=#2",
            "[s1 g store-a #5/5 +5ms] InputAccepted #4 Recovery origin=Unattributed IOException: boom <- SocketException +1 suppressed",
            "[s1 g store-a #6/6 +6ms] InputDiscarded #4 Rejected IllegalStateException",
            "[s1 g store-a #7/7 +7ms] InputDiscarded #5 StoreClosed",
            "[s1 g store-a #8/8 +8ms] ProcessingStarted #2 ordinal=1",
            "[s1 g store-a #9/9 +9ms] StateCommitted #2 revision=1 ? <- -",
            "[s1 g store-a #10/10 +10ms] EventEmitted Unattributed Loaded(items=[tom, felix])",
            "[s1 g store-a #11/11 +11ms] FailureReported #2 failure",
            "[s1 g store-a #12/12 +12ms] ProcessingFinished #2 ordinal=1 Recovered commits=2 X in 812us",
            "[s1 g store-a #13/13 +13ms] ProcessingFinished #3 ordinal=2 Unchanged in 0s",
            "[s1 g store-a #14/14 +14ms] StoreClosed",
            "[s1 g store-a #15/15 +15ms] InputRejected Tap pending=8 limit=8",
            "[s1 g store-a #16/16 +16ms] DecisionCommitted #5 revision=3 active=[Root,Loading] transitions=[T0,T2] exited=[Idle/a2] entered=[Loading/a3] commands=[c1@a3 net/Latest Fetch(cats),c2@a3 -] cancelled=[a2] timers=[t1:T3@a3+10s] timersCancelled=[t7] effects=2",
            "[s1 g store-a #17/17 +17ms] DecisionIgnored Unattributed NoTransition",
            "[s1 g #18 +18ms] JournalGap dropped=3",
            "[s1 g store-a #19/19 +19ms] BridgeSent #5 store-a/e1 -> root-1 reply-to=root-1/e4",
            "[s1 g store-a #20/20 +20ms] BridgeSent Unattributed store-a/e2 -> nobody undelivered",
            "[s1 g store-a #21/21 +21ms] BridgeReceived #6 root-1/e1",
            "[s1 g store-a #22/22 +22ms] EffectQueued #6 e4 Retained Navigate(to=chat)",
            "[s1 g store-a #23/23 +23ms] EffectHandlingStarted e4 attempt=2",
            "[s1 g store-a #24/24 +24ms] EffectAcknowledged e4",
            "[s1 g store-a #25/25 +25ms] EffectDiscarded e5 Superseded",
            "[s1 g store-a #26/26 +26ms] ExternalReceived #7 from paging:chat-1",
            "[s1 g #27 +27ms] CheckpointCreated members=[store-a,root-1] sources=[paging:chat-1] inFlight=1",
            "[s1 g #28 +28ms] RecordingStopped",
            "[s1 g store-a #29/29 +29ms] CommandsAbandoned StoreClosed queued=[c3,c4] running=[c2]",
            "[s1 g store-a #30/30 +30ms] BridgeDropped root-1/e3 -> store-a StoreClosed",
            "[s1 g store-a #31/31 +31ms] FailureReported Unattributed failure: ошибка 🌍",
            "[s1 g store-a #32/32 +32ms] EventEmitted #7 данные(z=末, a=)",
        )
}
