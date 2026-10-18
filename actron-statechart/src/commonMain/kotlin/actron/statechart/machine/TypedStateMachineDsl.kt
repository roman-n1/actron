package actron.statechart.machine

import actron.core.Action
import actron.core.ActronStoreDsl
import actron.core.Event
import actron.statechart.*
import kotlin.reflect.KClass
import kotlin.time.Duration

/** The selected state value is the source of graph identity and UI/recording data.
 * State class names form persisted graph IDs; renaming a class requires a definition version bump.
 * [readState] and [writeState] must be pure and describe the same property.
 * [initial] selects the starting variant; initialSnapshot/MachineStore context supplies its data.
 */
fun <C : Any, S : Any, A : Action, CMD : Any, E : Event> typedStateMachine(
    id: DefinitionId,
    version: DefinitionVersion,
    initial: S,
    readState: (C) -> S,
    writeState: (C, S) -> C,
    build: TypedStateMachineBuilder<C, S, A, CMD, E>.() -> Unit,
): Machine<C, A, CMD, E> =
    TypedStateMachineBuilder<C, S, A, CMD, E>(readState, writeState)
        .apply(build)
        .build(id, version, initial)

@ActronStoreDsl
class TypedStateMachineBuilder<C : Any, S : Any, A : Action, CMD : Any, E : Event>
    @PublishedApi
    internal constructor(
        private val readState: (C) -> S,
        private val writeState: (C, S) -> C,
        private val implementation: MachineBuilder<C, A, CMD, E> = MachineBuilder(),
        private val transitions: MutableList<Transition> = mutableListOf(),
        private val nodes: MutableList<StateNode> = mutableListOf(),
        private val parent: StateParent = StateParent.Root,
        private val prefix: String = "",
    ) {
        private val states = linkedMapOf<StateId, KClass<out S>>()
        private val references = linkedMapOf<StateId, KClass<out S>>()

        inline fun <reified T : S> state(noinline build: TypedMachineStateBuilder<C, S, A, CMD, E>.() -> Unit = {}) {
            register(T::class, build)
        }

        @PublishedApi
        internal fun reference(type: KClass<out S>): StateId {
            val id = StateId(prefix + requireNotNull(type.simpleName) { "State types must have a stable name" })
            val previous = references.getOrElse(id) { type }
            require(previous == type) { "State type names must be unique: $id" }
            references[id] = type
            return id
        }

        @PublishedApi
        internal fun register(
            type: KClass<out S>,
            build: TypedMachineStateBuilder<C, S, A, CMD, E>.() -> Unit,
        ) {
            val id = reference(type)
            require(id !in states) { "State $id is declared twice" }
            states[id] = type
            nodes += AtomicState(id, parent)
            TypedMachineStateBuilder(this, MachineStateBuilder(id, implementation, transitions), writeState).apply(build)
        }

        internal fun build(
            id: DefinitionId,
            version: DefinitionVersion,
            initial: S,
        ): Machine<C, A, CMD, E> {
            val initialId = finish(initial)
            return Machine(id, version, StateChartDefinition(initialId, nodes.toList(), transitions.toList()), implementation)
        }

        internal fun finish(initial: S): StateId {
            val initialId = reference(initial::class)
            require(references.all { (id, type) -> states[id] == type }) { "Every referenced state type must be declared" }
            val declared = states.toMap()
            implementation.invariant("${prefix}typed-state-matches-graph") { snapshot ->
                val type = readState(snapshot.context)::class
                if (!snapshot.isStarted) {
                    type == initial::class
                } else {
                    val active = snapshot.configuration.active.filter { it in declared }
                    active.size == 1 && declared[active.single()] == type
                }
            }
            implementation.enforceInvariants()
            return initialId
        }
    }

@ActronStoreDsl
class TypedMachineStateBuilder<C : Any, S : Any, A : Action, CMD : Any, E : Event>
    @PublishedApi
    internal constructor(
        @PublishedApi internal val owner: TypedStateMachineBuilder<C, S, A, CMD, E>,
        @PublishedApi internal val delegate: MachineStateBuilder<C, A, CMD, E>,
        @PublishedApi internal val writeState: (C, S) -> C,
    ) {
        /** [reduce] updates shared data; [to] builds the target state from that updated data. */
        inline fun <reified T : A, reified Target : S> on(
            noinline guard: (C, T) -> Boolean = { _, _ -> true },
            noinline reduce: (C, T) -> C = { context, _ -> context },
            noinline to: (C, T) -> Target,
        ) {
            delegate.on<T>(owner.reference(Target::class), guard) { context, action ->
                val updated = reduce(context, action)
                writeState(updated, to(updated, action))
            }
        }

        inline fun <reified Target : S> after(
            delay: Duration,
            noinline to: (C) -> Target,
        ) {
            delegate.after(delay, owner.reference(Target::class)) { context -> writeState(context, to(context)) }
        }

        inline fun <reified T : A> internal(
            noinline guard: (C, T) -> Boolean = { _, _ -> true },
            noinline reduce: (C, T) -> C,
        ) = delegate.internal<T>(guard, reduce)

        fun onEnter(block: MachineEnterScope<C, A, CMD, E>.() -> Unit) = delegate.onEnter(block)

        fun onExit(block: MachineExitScope<C, A, E>.() -> Unit) = delegate.onExit(block)
    }
