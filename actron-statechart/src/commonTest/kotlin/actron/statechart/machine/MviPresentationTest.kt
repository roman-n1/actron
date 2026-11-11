package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.test.startAndAwait
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MviPresentationTest {
    private data class DomainAmount(
        val units: Int,
    )

    private data class SetAmount(
        val amount: DomainAmount,
    ) : Action

    private data object Output : Event

    private data class TextEdited(
        val text: String,
    )

    private data class Display(
        val label: String,
    )

    @Test
    fun mapsUiPayloadToDomainActionAndProjectsTheResultWithoutAnotherStateOwner() =
        runTest {
            val machine =
                screenMachine<DomainAmount, SetAmount, Output>(DefinitionId("amount"), DefinitionVersion("1"), DomainAmount(1)) {
                    state<DomainAmount> { on<SetAmount> { target { it.amount } } }
                }
            val store = MachineStore(machine, DomainAmount(1), scope = backgroundScope, coroutineContext = coroutineContext)
            val presentation =
                store.present(
                    stateMapper = { Display("${it.context.units} units") },
                    actionMapper =
                        UiActionMapper<TextEdited, DomainAmount, SetAmount> { event, _, dispatch ->
                            if (event.text.all(Char::isDigit) && event.text.isNotEmpty()) dispatch(SetAmount(DomainAmount(event.text.toInt())))
                        },
                )
            try {
                store.startAndAwait()
                presentation.onUiEvent(TextEdited("12"))
                testScheduler.runCurrent()
                assertEquals(DomainAmount(12), store.currentState.context)
                assertEquals(Display("12 units"), presentation.state.value)
                presentation.onUiEvent(TextEdited("not an amount"))
                testScheduler.runCurrent()
                assertEquals(Display("12 units"), presentation.state.value)
            } finally {
                store.close()
            }
        }
}
