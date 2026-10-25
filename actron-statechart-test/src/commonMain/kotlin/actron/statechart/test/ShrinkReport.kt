package actron.statechart.test

import actron.core.Action
import actron.core.Event

/** Replay attempts and the smallest reproducing prefix found within the budget. */
data class ShrinkReport<C : Any, A : Action>(
    val failure: SequenceFailure<C, A>,
    val attempts: Int,
    val truncated: Boolean,
)
