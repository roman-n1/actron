package actron.observability.file

private fun SegmentEnding.observedIssue(): SegmentMark {
    val observations =
        buildList<SegmentMark> {
            kotlin.test.assertTrue(withIssue { add(it) })
        }
    return observations.single()
}

internal val FramesRead.mark: SegmentMark get() = ending.observedIssue()
internal val DecodedSegment.mark: SegmentMark get() = ending.observedIssue()
internal val DecodedSegment.header: SegmentHeader get() {
    val observations =
        buildList<SegmentHeader> {
            prefix.withHeader { add(it) }
        }
    return observations.single()
}
