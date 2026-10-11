package actron.timetravel.file

internal fun RecordingFileFormat.parseSegmentName(name: String): Pair<actron.observability.StoreInstanceId, Int> {
    val observations = buildList<Pair<actron.observability.StoreInstanceId, Int>> {
        kotlin.test.assertTrue(parseSegmentName(name) { id, index -> add(id to index) })
    }
    return observations.single()
}

internal fun GroupRecordingFileFormat.parseSegmentName(name: String): Pair<actron.observability.MachineGroupId, Int> {
    val observations = buildList<Pair<actron.observability.MachineGroupId, Int>> {
        kotlin.test.assertTrue(parseSegmentName(name) { id, index -> add(id to index) })
    }
    return observations.single()
}

