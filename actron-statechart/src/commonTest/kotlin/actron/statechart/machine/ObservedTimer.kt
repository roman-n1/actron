package actron.statechart.machine

internal fun <C : Any, CMD : Any> actron.statechart.machine.ExecutorCheckpoint<C, CMD>.remaining(id: actron.statechart.machine.TimerId): kotlin.time.Duration {
    val observations =
        buildList<kotlin.time.Duration> {
            kotlin.test.assertTrue(remaining(id) { add(it) })
        }
    return observations.single()
}
