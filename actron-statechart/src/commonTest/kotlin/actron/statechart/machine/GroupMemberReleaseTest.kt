package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.observability.StoreInstanceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GroupMemberReleaseTest {
    private data object Input : Action

    private data object Output : Event

    @Test
    fun releasesClosedLifetimesButNeverAnOpenExecutor() =
        runTest {
            val group = MachineGroup()
            val id = StoreInstanceId("screen")
            val member = group.member<Int, Input, Unit, Output>(id)
            val machine = screenMachine<Int, Input, Output>(DefinitionId("screen"), DefinitionVersion("1"), 0) { state<Int>() }
            val store = MachineStore(machine, 0, scope = backgroundScope, coroutineContext = coroutineContext, observers = listOf(member))
            member.attach(store)
            assertFailsWith<IllegalStateException> { member.release() }
            store.closeAndJoin()
            member.release()
            member.release()
            assertEquals(emptyList(), group.memberIds)
            assertFailsWith<IllegalStateException> { member.attach(store) }
            group.member<Int, Input, Unit, Output>(id)
            assertEquals(listOf(id), group.memberIds)
        }
}
