@file:OptIn(actron.core.InternalActronApi::class)

package actron.compose

import actron.core.Action
import actron.core.State
import actron.core.StateSaver
import actron.core.Store
import actron.core.createRuntimeStore
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Real Android lifecycle: active -> recreate/stop/remove/key replacement -> active. */
@RunWith(AndroidJUnit4::class)
class ActivityLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    private sealed interface ActivityPhase {
        data object AwaitingLaunch : ActivityPhase

        data class Running(
            val scenario: ActivityScenario<LifecycleTestActivity>,
        ) : ActivityPhase

        data object Closed : ActivityPhase
    }

    private var activity: ActivityPhase = ActivityPhase.AwaitingLaunch
    private val scenario get() = (activity as ActivityPhase.Running).scenario

    @Before fun setUp() {
        LifecycleHarness.fixture = Fixture()
    }

    @After fun tearDown() {
        if (activity is ActivityPhase.Running) scenario.close()
        activity = ActivityPhase.Closed
        LifecycleHarness.fixture.allStores.forEach { it.delegate.close() }
        LifecycleHarness.finish()
    }

    private fun launch(): Fixture {
        val context = checkNotNull(InstrumentationRegistry.getInstrumentation().context)
        activity = ActivityPhase.Running(checkNotNull(ActivityScenario.launch(Intent(context, LifecycleTestActivity::class.java))))
        compose.waitForIdle()
        return LifecycleHarness.fixture
    }

    @Test fun recreationRetainsSaverRestoresSnapshotAndClosesOldCompositionStore() {
        val fixture = launch()
        val originalObservations = mutableListOf<Handle>()
        compose.runOnIdle {
            originalObservations.add(fixture.handles.getValue("left"))
            originalObservations.single().view.dispatch(Increment)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            originalObservations
                .single()
                .store.currentState.value == 1 && originalObservations
                .single()
                .view.state.value == 1
        }

        kotlin.test.assertNotNull(scenario).recreate()
        compose.waitForIdle()
        compose.runOnIdle {
            val restored = fixture.handles.getValue("left")
            assertSame(originalObservations.single().saver, restored.saver)
            assertNotSame(originalObservations.single().store, restored.store)
            assertEquals(Counter(1), restored.view.state)
            assertEquals(
                1,
                originalObservations
                    .single()
                    .store.closes
                    .get(),
            )
            assertEquals(0, restored.store.closes.get())
        }
        kotlin.test.assertNotNull(scenario).close()
        compose.runOnIdle {
            assertEquals(
                1,
                fixture.handles
                    .getValue("left")
                    .store.closes
                    .get(),
            )
        }
    }

    @Test fun removingScreenDiscardsSaverAndReturningCreatesFreshStore() {
        val fixture = launch()
        val originalObservations = mutableListOf<Handle>()
        compose.runOnIdle {
            originalObservations.add(fixture.handles.getValue("left"))
            originalObservations.single().view.dispatch(Increment)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            originalObservations
                .single()
                .view.state.value == 1
        }
        compose.runOnIdle { fixture.tabs.value = emptyList() }
        compose.runOnIdle {
            assertEquals(
                1,
                originalObservations
                    .single()
                    .store.closes
                    .get(),
            )
            fixture.tabs.value = listOf("left")
        }
        compose.runOnIdle {
            val fresh = fixture.handles.getValue("left")
            assertNotSame(originalObservations.single().saver, fresh.saver)
            assertNotSame(originalObservations.single().store, fresh.store)
            assertEquals(Counter(0), fresh.view.state)
        }
    }

    @Test fun keyedInstancesForSameChatKeepStateAcrossReorderAndCloseOnlyRemovedTab() {
        val fixture = LifecycleHarness.fixture
        fixture.tabs.value = listOf("left", "right")
        launch()
        val leftObservations = mutableListOf<Handle>()
        val rightObservations = mutableListOf<Handle>()
        compose.runOnIdle {
            leftObservations.add(fixture.handles.getValue("left"))
            rightObservations.add(fixture.handles.getValue("right"))
            assertNotSame(leftObservations.single().saver, rightObservations.single().saver)
            leftObservations.single().view.dispatch(Increment)
            rightObservations.single().view.dispatch(Increment)
            rightObservations.single().view.dispatch(Increment)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            leftObservations
                .single()
                .view.state.value == 1 && rightObservations
                .single()
                .view.state.value == 2
        }
        compose.runOnIdle { fixture.tabs.value = listOf("right", "left") }
        compose.runOnIdle {
            assertSame(leftObservations.single().store, fixture.handles.getValue("left").store)
            assertSame(rightObservations.single().saver, fixture.handles.getValue("right").saver)
            assertEquals(Counter(1), leftObservations.single().saver.restore(Counter(0)))
            assertEquals(Counter(2), rightObservations.single().saver.restore(Counter(0)))
            fixture.tabs.value = listOf("right")
        }
        compose.runOnIdle {
            assertEquals(
                1,
                leftObservations
                    .single()
                    .store.closes
                    .get(),
            )
            assertEquals(
                0,
                rightObservations
                    .single()
                    .store.closes
                    .get(),
            )
            fixture.tabs.value = listOf("right", "left")
        }
        compose.runOnIdle {
            assertEquals(
                Counter(0),
                fixture.handles
                    .getValue("left")
                    .view.state,
            )
            assertEquals(
                Counter(2),
                fixture.handles
                    .getValue("right")
                    .view.state,
            )
        }
    }

    @Test fun storeKeyReplacementClosesOwnedStoreButKeepsRetainedSnapshot() {
        val fixture = launch()
        val originalObservations = mutableListOf<Handle>()
        compose.runOnIdle {
            originalObservations.add(fixture.handles.getValue("left"))
            originalObservations.single().view.dispatch(Increment)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            originalObservations
                .single()
                .view.state.value == 1
        }
        compose.runOnIdle { fixture.storeKey.value = "replacement" }
        compose.runOnIdle {
            val replacement = fixture.handles.getValue("left")
            assertNotSame(originalObservations.single().store, replacement.store)
            assertSame(originalObservations.single().saver, replacement.saver)
            assertEquals(Counter(1), replacement.view.state)
            assertEquals(
                1,
                originalObservations
                    .single()
                    .store.closes
                    .get(),
            )
        }
    }

    @Test fun backgroundAndRecreationDoNotCloseExternallyOwnedStore() {
        val fixture = LifecycleHarness.fixture
        fixture.ownsStore = false
        launch()
        val originalObservations = mutableListOf<Handle>()
        compose.runOnIdle {
            originalObservations.add(fixture.handles.getValue("left"))
            originalObservations.single().view.dispatch(Increment)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            originalObservations
                .single()
                .view.state.value == 1
        }
        kotlin.test.assertNotNull(scenario).moveToState(Lifecycle.State.CREATED)
        kotlin.test.assertNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
        kotlin.test.assertNotNull(scenario).recreate()
        compose.waitForIdle()
        compose.runOnIdle {
            val current = fixture.handles.getValue("left")
            assertSame(originalObservations.single().store, current.store)
            assertSame(originalObservations.single().saver, current.saver)
            assertEquals(Counter(1), current.view.state)
            assertEquals(
                0,
                originalObservations
                    .single()
                    .store.closes
                    .get(),
            )
        }
        kotlin.test.assertNotNull(scenario).close()
        assertEquals(
            0,
            originalObservations
                .single()
                .store.closes
                .get(),
        )
    }
}

/** Only in the instrumentation APK: an actual Activity with real owners and a Compose root. */
class LifecycleTestActivity : ComponentActivity() {
    init {
        addOnContextAvailableListener { setContent { LifecycleHarness.fixture.Content() } }
    }
}

private object LifecycleHarness {
    private sealed interface Run {
        data object AwaitingTest : Run

        data class Prepared(
            val fixture: Fixture,
        ) : Run
    }

    private var run: Run = Run.AwaitingTest
    var fixture: Fixture
        get() = (run as Run.Prepared).fixture
        set(value) {
            run = Run.Prepared(value)
        }

    fun finish() {
        run = Run.AwaitingTest
    }
}

private data class Counter(
    val value: Int,
) : State

private data object Increment : Action

private data class Handle(
    val saver: StateSaver<Counter>,
    val store: TrackedStore,
    val view: ViewStore<Counter, Increment, Nothing>,
)

private class TrackedStore(
    val delegate: Store<Counter, Increment, Nothing>,
) : Store<Counter, Increment, Nothing> by delegate {
    val closes = AtomicInteger()

    override fun close() {
        closes.incrementAndGet()
        delegate.close()
    }
}

private class Fixture {
    val tabs = mutableStateOf(listOf("left"))
    val storeKey = mutableStateOf("same-chat")
    var ownsStore = true
    val handles = mutableMapOf<String, Handle>()
    val allStores = mutableListOf<TrackedStore>()
    private val external = mutableMapOf<String, TrackedStore>()
    private val adopted = mutableMapOf<String, TrackedStore>()

    private fun newStore(saver: StateSaver<Counter>): TrackedStore =
        TrackedStore(
            createRuntimeStore<Counter, Increment, Nothing>(Counter(0)) {
                stateSaver(saver)
                state<Counter> { action<Increment> { nextState { state.copy(value = state.value + 1) } } }
            },
        ).also { allStores += it }

    @Composable fun Content() {
        for (tab in tabs.value) {
            key(tab) {
                val saver = rememberStateSaver<Counter>()
                val view =
                    if (ownsStore) {
                        rememberViewStore(key = storeKey.value, autoClose = true) {
                            newStore(saver).also { adopted[tab] = it }
                        }
                    } else {
                        val store = external.getOrPut(tab) { newStore(saver) }
                        adopted[tab] = store
                        rememberViewStore(store = store, autoClose = false)
                    }
                SideEffect { handles[tab] = Handle(saver, adopted.getValue(tab), view) }
            }
        }
    }
}
