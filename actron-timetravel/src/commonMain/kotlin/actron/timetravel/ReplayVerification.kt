package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi

/** Result of verifying a replay range without moving its cursor. */
@ExperimentalActronApi
sealed interface ReplayVerification<out C : Any, out A : Action, out CMD : Any, out E : Event> {
    /** Every input in the requested range matched, including an empty range. */
    data object Passed : ReplayVerification<Nothing, Nothing, Nothing, Nothing>

    /** The first divergence; later inputs in the requested range were not decided. */
    data class Diverged<C : Any, A : Action, CMD : Any, E : Event>(
        val mismatch: ReplayMismatch<C, A, CMD, E>,
    ) : ReplayVerification<C, A, CMD, E>
}
