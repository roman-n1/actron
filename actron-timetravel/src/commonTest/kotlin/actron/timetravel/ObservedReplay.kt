package actron.timetravel

internal fun <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> ReplaySession<C, A, CMD, E>.verify(
    from: Int = 0,
    to: Int = length,
): ReplayMismatch<C, A, CMD, E> {
    val observations =
        buildList<ReplayMismatch<C, A, CMD, E>> {
            kotlin.test.assertTrue(verify(from, to) { add(it) })
        }
    return observations.single()
}

internal fun <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> ReplaySession<C, A, CMD, E>.observedStepForward(): ReplayStep<C, A, CMD, E> = stepForward()

internal fun GroupReplaySession.observedStepForward(): GroupReplayStep = stepForward()
