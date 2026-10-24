@file:OptIn(InternalActronApi::class)

package actron.core

import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun <S : State, A : Action, E : Event> Store<S, A, E>.patchForTest(
    block: StorePatchBuilder<S, A, E>.() -> Unit,
): Store<S, A, E> {
    val patch = StorePatchBuilder<S, A, E>().apply(block).build()
    return requireStoreInternalApi().patch(patch)
}

internal suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.dispatchAndAwaitForTest(action: A) {
    requireStoreInternalApi().dispatchAndAwait(action)
}

internal suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.startAndAwaitForTest() {
    requireStoreInternalApi().startAndAwait()
}

internal suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.awaitIdleForTest(timeout: Duration = 10.seconds) {
    assertEquals(StorePendingWork(0, 0), requireStoreInternalApi().awaitIdle(timeout))
}

@Suppress("UNCHECKED_CAST")
private fun <S : State, A : Action, E : Event> Store<S, A, E>.requireStoreInternalApi(): StoreInternalApi<S, A, E> =
    this as? StoreInternalApi<S, A, E>
        ?: error("Expected Actron Store to implement StoreInternalApi")
