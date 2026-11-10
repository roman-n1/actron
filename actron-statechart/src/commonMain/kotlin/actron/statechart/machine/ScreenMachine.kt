package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.StateChartDefinition
import kotlin.reflect.KClass
import kotlin.time.Duration

/** A declared graph whose external work runs directly in its activation-owned entry blocks. */
class ScreenMachine<S : Any, A : Action, E : Event> internal constructor(
    id: DefinitionId,
    version: DefinitionVersion,
    chart: StateChartDefinition,
    implementation: MachineBuilder<S, A, Unit, E>,
) : Machine<S, A, Unit, E>(id, version, chart, implementation)

fun <S : Any, A : Action, E : Event> screenMachine(
    id: DefinitionId,
    version: DefinitionVersion,
    initial: S,
    build: ScreenMachineBuilder<S, A, E>.() -> Unit,
): ScreenMachine<S, A, E> = screenMachine(id, version, initial::class, build)

fun <S : Any, A : Action, E : Event> screenMachine(
    id: DefinitionId,
    version: DefinitionVersion,
    initialType: KClass<out S>,
    build: ScreenMachineBuilder<S, A, E>.() -> Unit,
): ScreenMachine<S, A, E> {
    val implementation = MachineBuilder<S, A, Unit, E>()
    val root = TypedStateMachineBuilder<S, S, A, Unit, E>({ it }, { _, state -> state }, implementation)
    ScreenMachineBuilder(root).apply(build).complete()
    return ScreenMachine(id, version, root.definition(initialType), implementation)
}

@ActronStoreDsl
class ScreenMachineBuilder<S : Any, A : Action, E : Event>
    @PublishedApi
    internal constructor(
        @PublishedApi internal val root: TypedStateMachineBuilder<S, S, A, Unit, E>,
    ) {
        @PublishedApi internal val states = mutableListOf<ScreenStateBuilder<S, A, E>>()

        @PublishedApi internal val outputs = mutableListOf<ScreenStateBuilder<S, A, E>.() -> Unit>()

        /** Common MVI output. Local transitions take priority; state and activation are preserved. */
        inline fun <reified T : A> onEvent(noinline produce: (S, T) -> E) {
            outputs += { onEvent(produce) }
        }

        inline fun <reified T : S> state(noinline build: ScreenStateBuilder<S, A, E>.() -> Unit = {}) {
            root.state<T> {
                val screen = ScreenStateBuilder(this)
                this@ScreenMachineBuilder.states += screen
                screen.build()
            }
        }

        internal fun complete() {
            for (state in states) for (output in outputs) state.output()
        }
    }

@ActronStoreDsl
class ScreenStateBuilder<S : Any, A : Action, E : Event>
    @PublishedApi
    internal constructor(
        @PublishedApi internal val rules: TypedMachineStateBuilder<S, S, A, Unit, E>,
    ) {
        /** Emits a domain event after commit, without changing state or restarting its work. */
        inline fun <reified T : A> onEvent(noinline produce: (S, T) -> E) {
            rules.delegate.output(ActionMatcher.of<T>(requireNotNull(T::class.simpleName))) { state, action ->
                require(action is T)
                produce(state, action)
            }
        }

        inline fun <reified T : A> on(noinline transition: TransitionRuleBuilder<S, S, T>.() -> Unit) = rules.on(transition)

        fun always(transition: TransitionRuleBuilder<S, S, Unit>.() -> Unit) = rules.always(transition)

        fun after(
            delay: Duration,
            transition: TransitionRuleBuilder<S, S, Unit>.() -> Unit,
        ) = rules.after(delay, transition)

        /** Runs after commit, cancels on exit, and restarts unfinished entry work on restore. */
        fun onEnter(block: suspend ScreenEntryScope<S, A, E>.() -> Unit) {
            rules.delegate.entryActivity(block)
        }
    }

@ActronStoreDsl
interface ScreenEntryScope<out S : Any, in A : Action, in E : Event> {
    val state: S

    suspend fun dispatch(action: A)

    suspend fun event(event: E)
}

/** For executor adapters that also accept command-based machines. */
fun <A : Action> noCommandHandler(): CommandHandler<Unit, A> = CommandHandler { _, _ -> error("A screen machine cannot register commands") }
