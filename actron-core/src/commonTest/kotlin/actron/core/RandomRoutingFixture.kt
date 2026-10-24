package actron.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.reflect.KClass

/*
 * Random Store definitions over a fixed set of types, for property-based handler registry tests.
 *
 * Types cannot be created at runtime, so the fixture fixes a small sealed hierarchy with parent and
 * child types and lets a seeded Random pick the registration order, the handler kinds, the declared
 * types and a few legacy (metadata-less) handlers. Each generated handler knows its own ground-truth
 * predicate, so tests can check the registry and the running Store against an independent model.
 */

internal sealed interface RtState : State {
    data class A(
        val n: Int,
    ) : RtState

    data object B : RtState

    sealed interface Sub : RtState {
        data class X(
            val n: Int,
        ) : Sub

        data object Y : Sub
    }
}

internal sealed interface RoutingEffect {
    fun execute(move: (RtState) -> Unit)

    data object Stay : RoutingEffect {
        override fun execute(move: (RtState) -> Unit) = Unit
    }

    data class Move(
        val target: RtState,
    ) : RoutingEffect {
        override fun execute(move: (RtState) -> Unit) = move(target)
    }

    data class Fail(
        val error: Exception,
    ) : RoutingEffect {
        override fun execute(move: (RtState) -> Unit): Unit = throw error
    }
}

internal sealed interface RtAction : Action {
    val effect: RoutingEffect

    data class P(
        override val effect: RoutingEffect = RoutingEffect.Stay,
    ) : RtAction

    data class Q(
        override val effect: RoutingEffect = RoutingEffect.Stay,
    ) : RtAction

    sealed interface G : RtAction {
        data class R(
            override val effect: RoutingEffect = RoutingEffect.Stay,
        ) : G

        data class T(
            override val effect: RoutingEffect = RoutingEffect.Stay,
        ) : G
    }
}

internal sealed interface RtEvent : Event

internal class CustomBoom : IllegalStateException("custom")

internal enum class HandlerKind { ENTER, ACTION, EXIT, RECOVER }

internal typealias RtBuilder = StoreBuilder<RtState, RtAction, RtEvent>
internal typealias RtConfig<S2> = StoreBuilder.StateHandlerConfig<RtState, RtAction, RtEvent, S2>

internal val statePool: List<RtState> =
    listOf(
        RtState.A(0),
        RtState.A(1),
        RtState.B,
        RtState.Sub.X(0),
        RtState.Sub.X(1),
        RtState.Sub.Y,
    )

internal fun exceptionPool(): List<Exception> =
    listOf(
        IllegalStateException("ise"),
        IllegalArgumentException("iae"),
        CustomBoom(),
        UnsupportedOperationException("uoe"),
        Exception("plain"),
    )

internal fun actionOf(
    type: Int,
    effect: RoutingEffect = RoutingEffect.Stay,
): RtAction =
    when (type) {
        0 -> RtAction.P(effect)
        1 -> RtAction.Q(effect)
        2 -> RtAction.G.R(effect)
        else -> RtAction.G.T(effect)
    }

internal val actionPool: List<RtAction> = (0..3).map { actionOf(it) }

internal interface StateKind {
    val type: KClass<out RtState>

    fun register(
        builder: RtBuilder,
        block: (RtConfigSink) -> Unit,
    )
}

/** Lets non-generic code register handlers inside a `state<S2> {}` block whose S2 is unknown. */
internal interface RtConfigSink {
    fun <R : Any> with(body: ConfigBody<R>): R
}

internal interface ConfigBody<R : Any> {
    fun <S2 : RtState> run(cfg: RtConfig<S2>): R
}

internal inline fun <reified S2 : RtState> stateKind(): StateKind =
    object : StateKind {
        override val type: KClass<out RtState> = S2::class

        override fun register(
            builder: RtBuilder,
            block: (RtConfigSink) -> Unit,
        ) {
            builder.state<S2> {
                val cfg = this
                block(
                    object : RtConfigSink {
                        override fun <R : Any> with(body: ConfigBody<R>): R = body.run(cfg)
                    },
                )
            }
        }
    }

internal interface ActionKind {
    val type: KClass<out RtAction>

    fun <S2 : RtState> add(
        cfg: RtConfig<S2>,
        dispatcher: CoroutineContext,
        id: Int,
        log: MutableList<Int>,
    )
}

internal inline fun <reified A2 : RtAction> actionKind(): ActionKind =
    object : ActionKind {
        override val type: KClass<out RtAction> = A2::class

        override fun <S2 : RtState> add(
            cfg: RtConfig<S2>,
            dispatcher: CoroutineContext,
            id: Int,
            log: MutableList<Int>,
        ) {
            cfg.action<A2>(dispatcher) {
                log += id
                action.effect.execute { target -> nextState { target } }
            }
        }
    }

internal interface RecoverKind {
    val type: KClass<out Exception>

    fun <S2 : RtState> add(
        cfg: RtConfig<S2>,
        deprecatedAlias: Boolean,
        id: Int,
        log: MutableList<Int>,
    )
}

internal inline fun <reified T : Exception> recoverKind(): RecoverKind =
    object : RecoverKind {
        override val type: KClass<out Exception> = T::class

        override fun <S2 : RtState> add(
            cfg: RtConfig<S2>,
            deprecatedAlias: Boolean,
            id: Int,
            log: MutableList<Int>,
        ) {
            if (deprecatedAlias) {
                @Suppress("DEPRECATION")
                cfg.error<T> { log += id }
            } else {
                cfg.recover<T> { log += id }
            }
        }
    }

internal val stateKinds: List<StateKind> =
    listOf(
        stateKind<RtState>(),
        stateKind<RtState.A>(),
        stateKind<RtState.B>(),
        stateKind<RtState.Sub>(),
        stateKind<RtState.Sub.X>(),
        stateKind<RtState.Sub.Y>(),
    )

internal val actionKinds: List<ActionKind> =
    listOf(
        actionKind<RtAction>(),
        actionKind<RtAction.P>(),
        actionKind<RtAction.Q>(),
        actionKind<RtAction.G>(),
        actionKind<RtAction.G.R>(),
        actionKind<RtAction.G.T>(),
    )

internal val recoverKinds: List<RecoverKind> =
    listOf(
        recoverKind<Exception>(),
        recoverKind<RuntimeException>(),
        recoverKind<IllegalStateException>(),
        recoverKind<IllegalArgumentException>(),
        recoverKind<CustomBoom>(),
    )

/** Predicates that no single type can express, used by legacy handlers without metadata. */
internal class LegacyPredicate(
    val name: String,
    val onState: (RtState) -> Boolean,
    val onInput: (Any) -> Boolean,
)

internal val legacyPredicates: List<LegacyPredicate> =
    listOf(
        LegacyPredicate("even", { (it is RtState.A && it.n % 2 == 0) || (it is RtState.Sub.X && it.n % 2 == 0) }, { it is RtAction.P || it is RtAction.G.T }),
        LegacyPredicate("odd", { (it is RtState.A && it.n % 2 == 1) || (it is RtState.Sub.X && it.n % 2 == 1) }, { it is IllegalArgumentException || it is CustomBoom }),
        LegacyPredicate("objects", { it is RtState.B || it is RtState.Sub.Y }, { it is RtAction.Q || it is UnsupportedOperationException }),
        LegacyPredicate("always", { true }, { true }),
        LegacyPredicate("never", { false }, { false }),
    )

/**
 * One handler as the generator planned it: its kind, the matcher the registry should report for it,
 * and its ground-truth predicate over (state, input). `input` is the action or the exception, and
 * is ignored for enter and exit handlers.
 */
internal class PlannedHandler(
    val id: Int,
    val kind: HandlerKind,
    val expectedMatcher: HandlerMatcher,
    val matches: (RtState, Any) -> Boolean,
)

internal sealed interface Block {
    /** A `state<S2> {}` block holding handlers of mixed kinds, in the order they are written. */
    class StateBlock(
        val stateKind: StateKind,
        val inner: List<Inner>,
    ) : Block

    /** A handler added the way inline code compiled against an older Actron did: no matcher at all. */
    class LegacyTop(
        val kind: HandlerKind,
        val id: Int,
        val predicate: LegacyPredicate,
    ) : Block
}

internal sealed interface HandlerDeclaration {
    val kind: HandlerKind
    val inputType: KClass<*>

    fun matches(input: Any): Boolean

    data class Lifecycle(
        override val kind: HandlerKind,
    ) : HandlerDeclaration {
        init {
            require(kind == HandlerKind.ENTER || kind == HandlerKind.EXIT)
        }

        override val inputType: KClass<*> = Unit::class

        override fun matches(input: Any): Boolean = input == Unit
    }

    data class Action(
        val action: ActionKind,
    ) : HandlerDeclaration {
        override val kind = HandlerKind.ACTION
        override val inputType get() = action.type

        override fun matches(input: Any) = inputType.isInstance(input)
    }

    data class Recovery(
        val recovery: RecoverKind,
    ) : HandlerDeclaration {
        override val kind = HandlerKind.RECOVER
        override val inputType get() = recovery.type

        override fun matches(input: Any) = inputType.isInstance(input)
    }

    data class Predicate(
        override val kind: HandlerKind,
        val predicate: LegacyPredicate,
    ) : HandlerDeclaration {
        init {
            require(kind == HandlerKind.ACTION || kind == HandlerKind.RECOVER)
        }

        override val inputType: KClass<*> get() = if (kind == HandlerKind.ACTION) RtAction::class else Exception::class

        override fun matches(input: Any) = predicate.onInput(input)
    }
}

internal class Inner(
    val id: Int,
    val declaration: HandlerDeclaration,
    val deprecatedAlias: Boolean = false,
    val dispatcher: CoroutineContext = EmptyCoroutineContext,
) {
    val kind get() = declaration.kind
}

internal class RoutingProgram(
    val blocks: List<Block>,
) {
    /** Handlers of [kind] in the order the Store must try them. */
    fun planned(kind: HandlerKind): List<PlannedHandler> =
        blocks.flatMap { block ->
            when (block) {
                is Block.LegacyTop -> {
                    if (block.kind == kind) {
                        listOf(
                            PlannedHandler(
                                block.id,
                                kind,
                                HandlerMatcher(
                                    RtState::class,
                                    when (kind) {
                                        HandlerKind.ACTION -> RtAction::class
                                        HandlerKind.RECOVER -> Exception::class
                                        else -> Unit::class
                                    },
                                ),
                            ) { s, input ->
                                block.predicate.onState(s) && (kind == HandlerKind.ENTER || kind == HandlerKind.EXIT || block.predicate.onInput(input))
                            },
                        )
                    } else {
                        emptyList()
                    }
                }

                is Block.StateBlock -> {
                    block.inner.filter { it.kind == kind }.map { inner ->
                        val stateType = block.stateKind.type
                        val inputType = inner.effectiveInputType()
                        PlannedHandler(inner.id, kind, HandlerMatcher(stateType, inputType)) { s, input ->
                            stateType.isInstance(s) && inner.declaration.matches(input)
                        }
                    }
                }
            }
        }

    fun install(
        builder: RtBuilder,
        log: MutableList<Int>,
    ) {
        for (block in blocks) {
            when (block) {
                is Block.LegacyTop -> {
                    installLegacyTop(builder, block, log)
                }

                is Block.StateBlock -> {
                    block.stateKind.register(builder) { sink ->
                        for (inner in block.inner) sink.with(InnerBody(inner, log))
                    }
                }
            }
        }
    }

    private class InnerBody(
        val inner: Inner,
        val log: MutableList<Int>,
    ) : ConfigBody<Unit> {
        override fun <S2 : RtState> run(cfg: RtConfig<S2>) {
            val id = inner.id
            when (val declaration = inner.declaration) {
                is HandlerDeclaration.Lifecycle -> {
                    when (declaration.kind) {
                        HandlerKind.ENTER -> cfg.enter(inner.dispatcher) { log += id }
                        HandlerKind.EXIT -> cfg.exit(inner.dispatcher) { log += id }
                        else -> error("Invalid lifecycle handler")
                    }
                }

                is HandlerDeclaration.Action -> {
                    declaration.action.add(cfg, inner.dispatcher, id, log)
                }

                is HandlerDeclaration.Recovery -> {
                    declaration.recovery.add(cfg, inner.deprecatedAlias, id, log)
                }

                is HandlerDeclaration.Predicate -> {
                    when (declaration.kind) {
                        HandlerKind.ACTION -> {
                            cfg.stateActionHandlers.add(
                                StoreBuilder.StateHandlerConfig.ThreadedHandler(
                                    dispatcher = inner.dispatcher,
                                    predicate = { action: RtAction -> declaration.predicate.onInput(action) },
                                    inputType = RtAction::class,
                                    handler = {
                                        log += id
                                        action.effect.execute { target -> nextState { target } }
                                    },
                                ),
                            )
                        }

                        HandlerKind.RECOVER -> {
                            cfg.stateErrorHandlers.add(
                                StoreBuilder.StateHandlerConfig.ThreadedHandler(
                                    dispatcher = inner.dispatcher,
                                    predicate = { error: Exception -> declaration.predicate.onInput(error) },
                                    inputType = Exception::class,
                                    handler = { log += id },
                                ),
                            )
                        }

                        else -> {
                            error("Invalid predicate handler")
                        }
                    }
                }
            }
        }
    }

    private fun installLegacyTop(
        builder: RtBuilder,
        block: Block.LegacyTop,
        log: MutableList<Int>,
    ) {
        val id = block.id
        val p = block.predicate
        when (block.kind) {
            HandlerKind.ENTER -> {
                builder.registeredEnterHandlers.add(StoreBuilder.StateHandler(predicate = { s: RtState -> p.onState(s) }, handler = { log += id }, matcher = HandlerMatcher(RtState::class)))
            }

            HandlerKind.EXIT -> {
                builder.registeredExitHandlers.add(StoreBuilder.StateHandler(predicate = { s: RtState -> p.onState(s) }, handler = { log += id }, matcher = HandlerMatcher(RtState::class)))
            }

            HandlerKind.ACTION -> {
                builder.registeredActionHandlers.add(
                    StoreBuilder.StateHandler(
                        predicate = { s: RtState, a: RtAction -> p.onState(s) && p.onInput(a) },
                        matcher = HandlerMatcher(RtState::class, RtAction::class),
                        handler = {
                            log += id
                            action.effect.execute { target -> nextState { target } }
                        },
                    ),
                )
            }

            HandlerKind.RECOVER -> {
                builder.registeredErrorHandlers.add(
                    StoreBuilder.StateHandler(predicate = { s: RtState, e: Exception -> p.onState(s) && p.onInput(e) }, handler = { log += id }, matcher = HandlerMatcher(RtState::class, Exception::class)),
                )
            }
        }
    }

    companion object {
        fun random(
            random: Random,
            maxBlocks: Int = 8,
            legacy: Boolean = true,
        ): RoutingProgram = RoutingGenerator(random, legacy).generate(maxBlocks)
    }
}

/** Owns the RNG stream and monotonically allocated IDs for one generated definition. */
private class RoutingGenerator(
    private val random: Random,
    private val legacy: Boolean,
) {
    private var nextId = 0

    fun generate(maxBlocks: Int): RoutingProgram = RoutingProgram(List(random.nextInt(0, maxBlocks + 1)) { block() })

    private fun block(): Block {
        if (legacy && random.nextInt(6) == 0) {
            return Block.LegacyTop(HandlerKind.entries.random(random), nextId++, legacyPredicates.random(random))
        }
        val inner = List(random.nextInt(0, 5)) { inner() }
        return Block.StateBlock(stateKinds.random(random), inner)
    }

    private fun inner(): Inner {
        val kind = HandlerKind.entries.random(random)
        val dispatcher = if (random.nextInt(4) == 0) Dispatchers.Unconfined else EmptyCoroutineContext
        val isLegacy = legacy && random.nextInt(8) == 0
        val declaration = declaration(kind, isLegacy)
        return Inner(
            nextId++,
            declaration,
            deprecatedAlias = kind == HandlerKind.RECOVER && random.nextBoolean(),
            dispatcher = if (kind == HandlerKind.RECOVER) EmptyCoroutineContext else dispatcher,
        )
    }

    private fun declaration(
        kind: HandlerKind,
        isLegacy: Boolean,
    ): HandlerDeclaration =
        when (kind) {
            HandlerKind.ACTION -> {
                val action = actionKinds.random(random)
                if (isLegacy) HandlerDeclaration.Predicate(kind, legacyPredicates.random(random)) else HandlerDeclaration.Action(action)
            }

            HandlerKind.RECOVER -> {
                val recovery = recoverKinds.random(random)
                if (isLegacy) HandlerDeclaration.Predicate(kind, legacyPredicates.random(random)) else HandlerDeclaration.Recovery(recovery)
            }

            else -> {
                HandlerDeclaration.Lifecycle(kind)
            }
        }
}

internal fun Inner.effectiveInputType(): KClass<*> = declaration.inputType

internal fun randomAction(random: Random): RtAction {
    val effect =
        if (random.nextInt(3) == 0) {
            RoutingEffect.Fail(exceptionPool().random(random))
        } else if (random.nextBoolean()) {
            RoutingEffect.Move(statePool.random(random))
        } else {
            RoutingEffect.Stay
        }
    return actionOf(random.nextInt(4), effect)
}

internal fun buildRtStore(
    program: RoutingProgram,
    initialState: RtState,
    log: MutableList<Int>,
    configure: RtBuilder.() -> Unit = {},
): Store<RtState, RtAction, RtEvent> =
    Store(initialState) {
        configure()
        program.install(this, log)
    }

/** What the Store should do when started in [initial] and sent one action, per the ground-truth predicates. */
internal class Expectation(
    val log: List<Int>,
    val finalState: RtState,
    val unhandled: List<Exception>,
)

internal fun RoutingProgram.expect(
    initial: RtState,
    action: RtAction,
): Expectation {
    fun visitFirst(
        kind: HandlerKind,
        state: RtState,
        input: Any,
        accept: (Int) -> Unit,
    ): Boolean {
        for (handler in planned(kind)) {
            if (handler.matches(state, input)) {
                accept(handler.id)
                return true
            }
        }
        return false
    }
    val log = mutableListOf<Int>()
    val unhandled = mutableListOf<Exception>()
    var state = initial
    visitFirst(HandlerKind.ENTER, initial, Unit, log::add)
    if (visitFirst(HandlerKind.ACTION, initial, action, log::add)) {
        when (val effect = action.effect) {
            RoutingEffect.Stay -> {
                Unit
            }

            is RoutingEffect.Fail -> {
                if (!visitFirst(HandlerKind.RECOVER, initial, effect.error, log::add)) unhandled += effect.error
            }

            is RoutingEffect.Move -> {
                if (effect.target != initial) {
                    if (effect.target::class != initial::class) {
                        visitFirst(HandlerKind.EXIT, initial, Unit, log::add)
                        visitFirst(HandlerKind.ENTER, effect.target, Unit, log::add)
                    }
                    state = effect.target
                }
            }
        }
    }
    return Expectation(log, state, unhandled)
}
