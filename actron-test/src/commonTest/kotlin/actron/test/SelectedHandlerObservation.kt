package actron.test

/** Require the selection promised by the calling test and verify callback cardinality. */
internal fun ActionMatchDiagnostics<*, *>.observedSelectedIndex(): Int = buildList<Int> {
    withSelectedHandler { add(it.index) }
}.single()
