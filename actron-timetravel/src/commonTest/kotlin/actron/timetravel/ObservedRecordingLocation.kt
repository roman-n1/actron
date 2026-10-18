package actron.timetravel

internal val <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> DecodedRecording.Invalid<C, A, CMD, E>.at: String get() {
    val observations =
        buildList<String> {
            location.withPart { add(it) }
        }
    return observations.single()
}
