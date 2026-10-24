package actron.core

import kotlin.time.Duration

/** Companion-module operations share the processor and return the original facade from patch. */
@OptIn(InternalActronApi::class)
internal class StoreCompanionApi<S : State, A : Action, E : Event>(
    private val processor: StoreProcessor<S, A, E>,
) : StoreInternalApi<S, A, E> {
    val store: Store<S, A, E> = StoreImpl(processor, this)

    override suspend fun startAndAwait() {
        processor.execution.checkOutside("startAndAwait")
        processor.launchStartup().join()
    }

    override suspend fun dispatchAndAwait(action: A) {
        processor.execution.checkOutside("dispatchAndAwait")
        processor.launchDispatch(action).join()
    }

    override suspend fun awaitIdle(timeout: Duration): StorePendingWork = processor.idleWaiter.await(timeout)

    override fun dispatchIf(
        action: A,
        isValid: () -> Boolean,
    ) {
        processor.launchDispatch(action, isValid)
    }

    override fun patch(patch: StorePatch<S, A, E>): Store<S, A, E> {
        processor.applyPatch(patch)
        return store
    }

    override fun matchActionHandlers(
        state: S,
        action: A,
    ): List<ActionHandlerMatch> = processor.handlerRegistry.matches(state, action)

    override fun handlerMetadata(): StoreHandlerMetadata = processor.handlerRegistry.metadata()
}
