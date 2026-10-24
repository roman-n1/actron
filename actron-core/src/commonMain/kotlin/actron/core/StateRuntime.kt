package actron.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Owns launches, subscriptions and lanes for one activation of a state variant. */
internal class StateRuntime(
    val scope: CoroutineScope,
    // Schedules cleanup under the Store mutex: completion can arrive on any thread.
    private val cleanup: (() -> Unit) -> Unit,
) {
    private val actionLaunchJobs = mutableMapOf<Any, Job>()
    private val subscriptions = mutableSetOf<Job>()

    fun activeLaunches(): List<Job> =
        scope.coroutineContext[Job]
            ?.children
            ?.filter { it.isActive && it !in subscriptions }
            ?.toList() ?: emptyList()

    fun subscribe(job: Job) {
        subscriptions += job
        job.invokeOnCompletion {
            cleanup { subscriptions.remove(job) }
        }
    }

    fun cancelLaunch(key: Any) {
        actionLaunchJobs.remove(key)?.cancel()
    }

    // Called under the Store mutex. The launch itself keeps the Store's recovery and origin.
    fun launchAction(
        action: Action,
        control: LaunchControl,
        start: () -> Job,
    ) {
        when (control) {
            LaunchControl.Untracked -> {
                start()
            }

            is LaunchControl.CancelPrevious -> {
                val key = control.lane.keyFor(action)
                cancelLaunch(key)
                track(key, start())
            }

            is LaunchControl.DropIfRunning -> {
                val key = control.lane.keyFor(action)
                if (actionLaunchJobs[key]?.isActive == true) return
                track(key, start())
            }
        }
    }

    private fun track(
        key: Any,
        job: Job,
    ) {
        actionLaunchJobs[key] = job
        job.invokeOnCompletion {
            cleanup {
                // Never remove a newer launch which has already replaced the completed one.
                if (actionLaunchJobs[key] === job) actionLaunchJobs.remove(key)
            }
        }
    }
}
