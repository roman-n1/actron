@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withRunningRecomposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class UiStateThreadingJvmTest {
    @Test
    fun backgroundStateUpdatesAreRenderedOnMain() {
        val executor = checkNotNull(Executors.newSingleThreadExecutor { task: Runnable -> Thread(task, "test-ui") })
        executor.asCoroutineDispatcher().use { ui ->
            Dispatchers.setMain(ui)
            try {
                val frames = BroadcastFrameClock()
                runBlocking(ui + frames) {
                    withRunningRecomposer { recomposer ->
                        val uiThread: Thread = Thread.currentThread()
                        val source = MutableStateFlow(0)
                        val rendered = Channel<Pair<Int, Thread>>(Channel.UNLIMITED)
                        val composition = Composition(NoOpApplier(), recomposer)
                        val ticker =
                            launch {
                                var tick = 0L
                                while (true) {
                                    delay(16)
                                    frames.sendFrame(++tick * 16_000_000L)
                                }
                            }
                        try {
                            composition.setContent {
                                val state by source.collectAsUiState()
                                SideEffect {
                                    val thread: Thread = Thread.currentThread()
                                    rendered.trySend(state to thread)
                                }
                            }
                            withTimeout(10_000) {
                                assertEquals(0 to uiThread, rendered.receive())
                                withContext(Dispatchers.Default) { source.value = 1 }
                                var next = rendered.receive()
                                while (next.first != 1) next = rendered.receive()
                                assertEquals(1 to uiThread, next)
                            }
                        } finally {
                            composition.dispose()
                            ticker.cancelAndJoin()
                            rendered.close()
                        }
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(
            index: Int,
            instance: Unit,
        ) {}

        override fun insertBottomUp(
            index: Int,
            instance: Unit,
        ) {}

        override fun move(
            from: Int,
            to: Int,
            count: Int,
        ) {}

        override fun remove(
            index: Int,
            count: Int,
        ) {}

        override fun onClear() {}
    }
}
