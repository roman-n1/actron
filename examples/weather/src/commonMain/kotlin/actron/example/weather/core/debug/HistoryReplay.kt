@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.weather.core.debug

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.Machine
import actron.timetravel.RecordingWindow
import actron.timetravel.ReplaySession
import actron.timetravel.ReplayStep

/** The UI keeps one frozen library recording while the live machines continue independently. */
data class ReplayFrame(
    val position: Int,
    val length: Int,
    val details: String,
    val status: String,
)

interface HistoryReplay {
    fun frame(): ReplayFrame

    fun backward(): ReplayFrame

    fun forward(): ReplayFrame

    fun verify(): ReplayFrame
}

class RecordedHistoryReplay<C : Any, A : Action, CMD : Any, E : Event>(
    machine: Machine<C, A, CMD, E>,
    window: RecordingWindow<C, A, CMD, E>,
    index: Long,
) : HistoryReplay {
    private val replay = ReplaySession(machine, window.recording)
    private var status = "Frozen replay; live app is unchanged"
    private val unavailable = window.origin.withProblem { status = it }

    init {
        replay.seek((index - window.firstStep + 1).toInt().coerceIn(0, replay.length))
    }

    override fun frame() = ReplayFrame(replay.position, replay.length, "Snapshot: ${replay.snapshot}\n\nCheckpoint: ${replay.checkpoint}", status)

    override fun backward(): ReplayFrame {
        replay.stepBackward()
        return frame()
    }

    override fun forward(): ReplayFrame {
        if (!unavailable) {
            when (val step = replay.stepForward()) {
                is ReplayStep.Diverged -> status = step.mismatch.describe()
                is ReplayStep.Matched -> status = "Step matched"
                is ReplayStep.Finished -> status = "End of recording"
            }
        }
        return frame()
    }

    override fun verify(): ReplayFrame {
        if (!unavailable && !replay.verify { status = it.describe() }) status = "Replay verified"
        return frame()
    }
}
