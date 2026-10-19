package actron.statechart.machine

import actron.core.Action
import actron.core.ExperimentalActronApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Applies only to commands that were running at the saved checkpoint, not queued/new work. */
@ExperimentalActronApi
fun interface CommandRecoveryStrategy<CMD : Any, A : Action> {
    fun select(command: CommandEnvelope<CMD>): CommandRecoveryDecision<CMD, A>
}

/** Recovery is application code, never serialized. Restart remains the default (at least once). */
@ExperimentalActronApi
sealed interface CommandRecoveryDecision<out CMD : Any, out A : Action> {
    data object Restart : CommandRecoveryDecision<Nothing, Nothing>

    /** Replaces the original handler, e.g. querying an operation ID before deciding to retry. */
    class Reconcile<CMD : Any, A : Action>(
        val handler: CommandHandler<CMD, A>,
    ) : CommandRecoveryDecision<CMD, A>

    /** Suspends without releasing the command's lane; cancellation also cancels this wait. */
    class AwaitDecision<CMD : Any, A : Action>(
        val decide: suspend (CommandEnvelope<CMD>) -> CommandRecoveryDecision<CMD, A>,
    ) : CommandRecoveryDecision<CMD, A>
}

@OptIn(ExperimentalActronApi::class)
internal suspend fun <CMD : Any, A : Action> executeRecovery(
    strategy: CommandRecoveryStrategy<CMD, A>,
    handler: CommandHandler<CMD, A>,
    command: CommandEnvelope<CMD>,
    results: ResultSink<A>,
) {
    var policy = strategy.select(command)
    while (true) {
        currentCoroutineContext().ensureActive()
        when (val selected = policy) {
            CommandRecoveryDecision.Restart -> {
                handler.execute(command, results)
                return
            }

            is CommandRecoveryDecision.Reconcile -> {
                selected.handler.execute(command, results)
                return
            }

            is CommandRecoveryDecision.AwaitDecision -> {
                policy = selected.decide(command)
            }
        }
    }
}

internal fun MachineClock.rebased(savedTime: MachineTime): MachineClock {
    val base = now()
    val source = this
    return object : MachineClock {
        override fun now(): MachineTime = savedTime + (source.now() - base)

        override suspend fun delayUntil(deadline: MachineTime) = source.delayUntil(base + (deadline - savedTime))
    }
}
