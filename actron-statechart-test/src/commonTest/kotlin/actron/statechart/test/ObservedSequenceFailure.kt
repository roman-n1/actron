package actron.statechart.test

internal fun <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> actron.statechart.machine.Machine<C, A, CMD, E>.replaySequence(
    initial: actron.statechart.machine.MachineSnapshot<C>, inputs: List<actron.statechart.machine.MachineInput<A>>,
): SequenceFailure<C, A> {
    val observations = buildList<SequenceFailure<C, A>> {
        kotlin.test.assertTrue(replaySequence(initial, inputs) { add(it) })
    }
    return observations.single()
}
