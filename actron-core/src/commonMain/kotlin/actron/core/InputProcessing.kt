@file:OptIn(InternalActronApi::class)

package actron.core

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal sealed interface InputProcessing {
    val attribution: InputAttribution

    fun commit(): InputId

    fun fail(error: Throwable)

    fun recover(error: Throwable)

    fun ignore()

    fun launchOrigin(): CoroutineContext

    data object Idle : InputProcessing {
        override val attribution: InputAttribution = InputAttribution.Unattributed

        override fun commit(): InputId = error("[Actron] A state was committed outside input processing")

        override fun fail(error: Throwable) = Unit

        override fun recover(error: Throwable) = Unit

        override fun ignore() = Unit

        override fun launchOrigin(): CoroutineContext = EmptyCoroutineContext
    }
}

internal class Processing(
    val input: InputId,
    val ordinal: Long,
) : InputProcessing {
    override val attribution: InputAttribution get() = input
    private var commits = 0
    private var resolution: ProcessingOutcome = ProcessingOutcome.Unchanged

    override fun commit(): InputId {
        commits++
        return input
    }

    override fun fail(error: Throwable) {
        resolution = ProcessingOutcome.Failed(error)
    }

    override fun recover(error: Throwable) {
        if (resolution !is ProcessingOutcome.Failed) resolution = ProcessingOutcome.Recovered(error, commits)
    }

    override fun ignore() {
        resolution = ProcessingOutcome.Ignored
    }

    override fun launchOrigin(): CoroutineContext = InputOrigin(input)

    fun outcome(): ProcessingOutcome =
        when (val result = resolution) {
            is ProcessingOutcome.Recovered -> result.copy(commits = commits)
            ProcessingOutcome.Unchanged -> if (commits == 0) result else ProcessingOutcome.Handled(commits)
            else -> result
        }
}
