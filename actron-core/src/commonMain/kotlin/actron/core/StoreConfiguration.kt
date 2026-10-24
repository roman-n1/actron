@file:OptIn(InternalActronApi::class)

package actron.core

import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/** Owns runtime settings and their consumption boundaries. Patches run under the Store mutex. */
internal class StoreConfiguration<S : State, A : Action, E : Event>(
    initialState: S,
    coroutineContext: CoroutineContext,
    settings: List<StoreSetting<S>>,
    val plugins: MutableList<Plugin<S, A, E>>,
    val probes: MutableList<StoreProbe<S, A, E>>,
) {
    var initialState: S = initialState
        private set
    var coroutineContext: CoroutineContext = coroutineContext
        private set
    var stateSaver: StateSaver<S> = StateSaver.Noop()
        private set
    var exceptionHandler: ExceptionHandler = ExceptionHandler.Rethrow
        private set
    var autoStartPolicy: AutoStartPolicy = AutoStartPolicy.OnDispatchOrStateCollection
        private set
    var pendingActionPolicy: PendingActionPolicy = PendingActionPolicy.ClearOnStateExit
        private set
    var pluginExecutionPolicy: PluginExecutionPolicy = PluginExecutionPolicy.Concurrent
        private set

    @Volatile
    private var isStateRead = false

    @Volatile
    private var isContextUsed = false

    @Volatile
    private var isSealed = false

    init {
        applySettings(settings)
    }

    fun stateRead() {
        isStateRead = true
    }

    fun contextUsed() {
        isContextUsed = true
    }

    fun seal() {
        isSealed = true
    }

    fun applyPatch(patch: StorePatch<S, A, E>) {
        validate(patch.settings)
        applySettings(patch.settings)
        applyPlugins(patch.pluginPatches)
        probes.addAll(patch.probes)
    }

    private fun validate(settings: List<StoreSetting<S>>) {
        check(!isSealed) { "[Actron] Store configuration must be applied before startup is requested" }
        if (settings.any { it is StoreSetting.InitialState }) {
            check(!isStateRead) { "[Actron] initialState cannot be patched after the state has been read" }
        }
        if (settings.any { it is StoreSetting.Persistence }) {
            check(!isStateRead) { "[Actron] stateSaver cannot be patched after the state has been read" }
        }
        if (settings.any { it is StoreSetting.ExecutionContext }) {
            check(!isContextUsed) { "[Actron] coroutineContext cannot be patched after the Store has begun running coroutines" }
        }
    }

    private fun applySettings(settings: List<StoreSetting<S>>) {
        for (setting in settings) {
            when (setting) {
                is StoreSetting.InitialState -> initialState = setting.state
                is StoreSetting.ExecutionContext -> coroutineContext = setting.context
                is StoreSetting.Persistence -> stateSaver = setting.saver
                is StoreSetting.FailureHandler -> exceptionHandler = setting.handler
                is StoreSetting.AutoStart -> autoStartPolicy = setting.policy
                is StoreSetting.PendingActions -> pendingActionPolicy = setting.policy
                is StoreSetting.PluginExecution -> pluginExecutionPolicy = setting.policy
            }
        }
    }

    private fun applyPlugins(patches: List<PluginPatch<S, A, E>>) {
        for (patch in patches) {
            when (patch) {
                is PluginPatch.Append -> {
                    plugins.addAll(patch.plugins)
                }

                is PluginPatch.Replace -> {
                    plugins.clear()
                    plugins.addAll(patch.plugins)
                }

                is PluginPatch.Clear -> {
                    plugins.clear()
                }
            }
        }
    }
}
