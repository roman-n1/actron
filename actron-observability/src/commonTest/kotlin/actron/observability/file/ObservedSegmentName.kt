package actron.observability.file

internal fun JournalFileFormat.parseSegmentName(name: String): Pair<actron.observability.RuntimeSessionId, Int> {
    val observations =
        buildList<Pair<actron.observability.RuntimeSessionId, Int>> {
            kotlin.test.assertTrue(parseSegmentName(name) { id, index -> add(id to index) })
        }
    return observations.single()
}
