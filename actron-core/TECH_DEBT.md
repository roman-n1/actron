# Core architecture debt

## TD-1: Handler and transaction scope construction — resolved 2026-10-24

StoreStateScopes now owns the activation table and the synchronous handler contexts.
StoreLaunchedScopes keeps the launch action/cancellation identity and builds typed transaction
contexts. Scope-local state proposals stay local; StoreScopeExecution exposes four semantic
operations instead of access to the Store's fields.

At this pass StoreImpl owned the mutex, input accounting boundaries, recovery and commit. Enter and action
transactions share one admission/execution path: validity is checked under the lock, the
snapshot is taken there, and an admitted transaction remains atomic in the root coroutine scope.
A failed exit retains its original runtime. The JVM retention tests follow the moved runtime
table without changing the memory-release assertions or adding production test hooks.

The second pass reduces StoreImpl from 1131 to 894 physical lines. Raw Detekt analysis
confirms LargeClass is resolved; its entry and the obsolete catch finding were removed from
the exact baseline, with no new exceptions. StoreScopeSnapshotTest covers last-proposal wins,
immediate event emission, recovery discarding a failed proposal, fresh transaction state and
the initiating action. Existing lifecycle/cancellation/probe suites remain the contract oracle.

## TD-2: Dispatch scheduling and configuration — resolved 2026-10-24

StoreDispatchQueue owns the dispatch SupervisorJob, active dispatch identity, atomic predecessor
chain and cancellation of queued actions. A predecessor releases the queue after its processing
under the Store mutex, even when its coroutine still owns a child. Clear operations exclude the
current handler and preserve actions dispatched by plugins after a commit. QueuedStoreInput
shares the cancellation-before-processing boundary with transaction and recovery inputs.
StoreInputs remains the only owner of pending accounting and terminal input traces.

StoreConfiguration owns runtime settings, plugin/probe collections and the three consumption
boundaries: state read, root context used, startup requested. Validation checks the entire patch
before applying settings, plugins or probes. The Store retains the mutex around patch operations;
requesting startup seals configuration even if initialization later fails. Builder records only
the last typed command of each kind and passes a snapshot at construction, without keeping
superseded savers/handlers alive. System execution context selection remains in the builder.

StoreQueueConfigurationTest passed before and after extraction. It checks that handler children
do not block successors, clearing leaves the current handler and post-commit dispatch intact,
an invalid mixed patch has no partial configuration effects, and event collection consumes
context without consuming initial state. Existing FIFO/concurrency/startup/patch and leak suites
remain unchanged in contract; reflection follows the moved queue fields.

StoreImpl is reduced from 894 to 745 physical lines. TooManyFunctions remains an existing
structural finding on the Store coordinator; it does not indicate that queue/configuration still
share an owner. Do not extract another unit solely to satisfy the function counter. Further work
should follow an identified lifecycle/transition responsibility and preserve the public API,
atomic processing, cancellation semantics and platform regression tests.

## TD-3: Runtime lifecycle and observation — extracted 2026-10-24; function-count finding remains

StoreExecution owns the lazy root coroutine scope, its completion/failure callbacks and the
per-Store execution marker. It rejects waiting from the same Store's locked work but permits
waiting on another Store. Reading provenance uses the current processing input for locked work
and the coroutine's stable InputOrigin for launches. No scope is created by construction or by
an idle wait.

StoreStartup owns the successful plugin-start and initial-enter phases. Configuration sealing
still happens when startup is requested. Plugin startup can retry without repeating successful
registrations; an unhandled initial-enter failure still marks initialization complete, as before.
StoreIdleWaiter owns draining and bounded timeout reporting under the existing transition mutex.
HandlerRegistry now owns matching/metadata queries; StorePlugins creates its own PluginScope
adapters using dispatch, live-state, root-scope and origin callbacks.

StoreExecutionOwnershipTest passed before and after extraction: cross-Store waiting remains
legal, idle observation consumes neither initial state nor execution context, and plugin launches
read fresh state with their initiating input. Existing cancellation/startup/probe/idle tests
remain the oracle. Raw analysis finds no violations in the newly extracted lifecycle, startup,
idle-waiter or registry code.

StoreImpl shrank from 745 to 603 physical lines and from 40 to 32 declared methods (confirmed by
Detekt). TooManyFunctions is deliberately not marked resolved: the existing limit is 11, while
Store and StoreInternalApi together require 12 implemented methods, before orchestration.
No threshold, suppression, exclusion or baseline exception was added.

At the end of this pass, remaining meaningful responsibilities were transition/recovery orchestration, state/event
publication and failure reporting. Any further split should separate the public facade and
companion API from the processing engine with explicit ownership of these operations; simply
moving methods to inheritance, lambda properties or utility files to avoid counting is not a
solution. Preserve startup ordering, state commit atomicity and cancellation/fatal-error policy.


## TD-4: Store facade and transition/recovery engine — resolved 2026-10-24

StoreImpl is now the Store facade: five public operations and a caller-collected StateFlow
adapter preserving lazy startup. StoreCompanionApi implements the seven companion operations
against the same processor, creates the facade once and returns that exact facade from patch.
The adapter represents the existing companion contract; no public API, dumps or signatures changed.

StoreProcessor owns input admission, the mutex, queue/startup coordination and state/event
publication. StoreTransitions owns exit/commit/enter ordering, enter-chain depth, recovery and
recovery validation. It runs under the processor's mutex and uses a narrow effects port for
current committed state, pre-action hooks, state publication and queue clearing. It cannot
schedule inputs or manipulate the lock. StoreHandlers carries the executable DSL callbacks.
StoreReporting isolates probes and preserves once-only failure reporting; attribution is explicit.
Root/state coroutine ownership, cancellation checks, recovery input identity, startup ordering
and post-commit hook failure handling are preserved.

StoreImpl shrank from 603 to 62 physical lines. Detekt confirms TooManyFunctions is resolved:
facade 5 methods, companion adapter 7, processor 10, transition engine 5 and reporter 4.
No new size/complexity finding or threshold/suppression/exclusion was introduced.
With explicit user authorization, the existing broad-catch debt was relocated to its new owners:
StoreImpl's Throwable entry becomes StoreProcessor and StoreTransitions entries, and its
Exception/handlerError entries move to StoreReporting. All catch declarations are preserved:
11 Throwable, 2 InternalError, 1 handlerError: Throwable, 1 Exception. The core baseline remains
37 distinct IDs after removing TooManyFunctions. This is a one-time relocation, not a relaxation
of the exact-baseline ratchet or permission to accept new catch debt.

StoreTransitionBoundaryTest checks the exact saver/report/plugin/enter/recovery order, a
three-commit recovered input, validator rejection before commit, original-error suppression and
patch facade identity through production entry points. Existing lifecycle/cancellation/trace,
queue, retention and soak assertions remain in force; JVM reflection follows the moved private
processor without exposing new production test hooks. JVM/JS/Wasm/iOS and companion suites,
API/ABI, nullability, Detekt and ktlint validate this extraction.

At the end of this pass the processor still coordinated publication, launched work and transactions. Further extraction
should require a concrete independent invariant or reason to change, not a target line count;
these operations currently share the same atomic input/transition boundary.


## TD-5: Publication separated from input processing — resolved 2026-10-24

StorePublication owns the mutable state/event flows, lazy restore and ordered publication effects.
StoreProcessor keeps admission, the transition mutex and root input jobs; its state/event getters
only forward to the publication owner. No state snapshot, input identity or revision is copied.
A state commit still runs under the processor's mutex through StoreInputs.commit: publish the
snapshot, account/trace its revision, save it, invoke onState. Restore remains lazy and marks
configuration state consumption at the same boundary. Events retain unbuffered delivery before
EventEmitted and onEvent; a launched event does not acquire the transition mutex. Attribution
and the Store execution marker are preserved. Recoverable post-publication saver/hook failures
are reported without aborting the remaining transition/handler, as before.

Two callbacks connect state/event hooks to the existing plugin processing path. This costs a
small explicit dependency boundary but avoids a second plugin scope, serializer, failure policy
or new interface. StoreInputs still owns commit accounting and outcomes; StorePlugins still
owns serialization of action/start/state/event rounds. Separate state and event publisher classes
would add wiring without introducing another independent invariant, so they remain together.

StoreProcessor is reduced from 376 to 321 physical lines (10 to 8 methods); StorePublication has
88 lines and two methods. Following the user's authorization to relocate only existing catch
debt, the two moved Throwable catches share a new exact StorePublication baseline ID while the
processor ID still covers its remaining catches. Core has 38 IDs rather than 37 solely because
one ID formerly grouped catches now owned by two classes. The combined catch declarations are
unchanged: seven Throwable and two InternalError. No new catch or size/complexity debt, rule
change, suppression, exclusion or ratchet relaxation was introduced.

StorePublicationBoundaryTest passed on the previous implementation before extraction. It checks
visibility of committed state while onState is suspended, commit/trace/save/hook ordering, the
next input waiting for that hook, event backpressure before trace/hooks, hook failure reporting
without recovery, and event/input attribution. Existing restoration, configuration, cancellation,
launch provenance, self-await, lifecycle, trace and retention tests remain the behavioral oracle.

Validation: 1695 tests passed (core JVM 232, JS/Wasm/iOS 221 each; statechart 564,
actron-test 45, observability 57, timetravel 134), with no failures or skips. Core API/ABI,
nullability guard, aggregate Detekt, standalone ktlint and baseline-ratchet contract tests passed.


## TD-6: State launches and root input execution — resolved 2026-10-24

StoreLaunchedWork owns the bridge from a state-owned launch to root-owned transaction/recovery
inputs. It starts the launch with its original input context, preserves the build-scope boundary
before the recovery catch, and queues recoverable launch failures as distinct inputs. Under the
same processor mutex, recovery checks the activation and cancelled-request identity again; a
stale failure is reported with its original error and recovery input rather than applied to the
new state. Fatal errors and cancellation retain their existing classification.

Transactions check self-await before accepting an input, preserve their launch origin, validate
eligibility under the lock, read the fresh snapshot there and keep block/recovery/commit atomic
inside the root job. Once admitted, cancelling the originating launch does not split the
transaction. Closing the Store still cancels that root job. QueuedStoreInput and StoreInputs
keep cancellation-before-lock accounting and exactly one terminal input trace.

The processor constructs this collaborator passively after its dependencies, forwarding the
existing StoreScopeExecution launch/transaction operations. It retains the single mutex,
startup/dispatch admission and runtime assembly. StateRuntime still owns lanes/subscriptions,
StoreExecution owns root lifetime, StoreLaunchedScopes owns the typed DSL and eligibility
predicate, and StoreTransitions owns transition/recovery policy. No new interface, factory,
Job, lock, mutable lifecycle flag or copy of Store state was added. Keeping launch failure and
transaction scheduling together makes their shared origin/cancellation/admission rules explicit;
separate transaction/recovery schedulers would duplicate this boundary without a current need.

StoreProcessor shrank from 321 to 241 physical lines (eight to five methods); StoreLaunchedWork
has 114 lines and three methods. Under the existing user authorization to relocate catch debt,
the two moved Throwable catches now have one exact StoreLaunchedWork baseline ID. The processor
ID remains for its other three catches, making 39 core IDs instead of 38; the combined five
catch declarations and their policies are unchanged. No new catch/complexity debt, rule change,
suppression, exclusion or baseline-ratchet relaxation was introduced.

StoreLaunchedWorkBoundaryTest passed before extraction. It proves that an admitted transaction
survives its launch's cancellation while retaining the lock through commit, that the successor
reads the committed result, and that the transaction preserves its origin and terminal outcome.
It also queues recovery while an exit holds the lock, then verifies that exiting that activation
prevents recovery, reports the original error with its recovery input and finishes the input as
Failed. Existing stale/cancelled launch, nested transaction, fresh-snapshot, timeout/fatal-error,
trace, idle, retention and soak tests remain the contract oracle.

Validation: 1703 tests passed (core JVM 234, JS/Wasm/iOS 223 each; statechart 564,
actron-test 45, observability 57, timetravel 134), with no failures or skips. Core API/ABI,
nullability guard, aggregate Detekt and standalone ktlint passed.


## TD-7: Builder ownership, test synchronization, failure protocol and KDoc — resolved 2026-10-24

The user explicitly selected a frozen definition at build: changes through a retained builder
no longer affect an already-created Store. HandlerRegistry now owns copied executable registrations
and recovery validators, implements first-match enter/action/exit/recovery invocation and derives
routing introspection from those same entries. It creates the StoreHandlers adapter consumed by
the existing runtime. The DSL's StateHandler/ThreadedHandler and registration fields remain in
StoreBuilder with their unchanged PublishedApi signatures. Runtime plugin/probe collections are
separate mutable copies, so normal before-start patches continue to reach the existing plugin and
observation owners. This is a shallow definition snapshot; user callback/plugin objects and their
captured data are not cloned. StoreBuilder no longer supplies runtime execution closures that
retain its mutable registration/validator lists.

StoreBuilderBoundaryTest first failed on the previous implementation for both escaped-handler
mutation and shared plugin/probe/validator mutation. It then passed with frozen definitions,
matching metadata, ignored unregistered inputs, original recovery validation and working runtime
plugin/probe patches. The public Kotlin/JVM/klib API, including PublishedApi inline dependencies,
is unchanged. This deliberately changes only the explicitly agreed late-builder mutation behavior.

StoreActionCoroutineScopeTest, StoreStateCoroutineScopeTest and StoreConcurrentTest now use a
managed test dispatcher and explicit completion/idle/cancellation observations rather than a
fixed count of yields. The concurrent state-exit scenario now checks the exact result and event
list, replacing an unreachable conditional assertion and the incorrect claim that a sleeping
launch holds the transition mutex. Cancellation setup uses awaitCancellation/finally instead of
catching arbitrary exceptions. StoreMultiThreadedTest retains real Dispatchers.Default and the
intentional yield that provokes concurrent hook overlap; it joins finite work before reading
plain plugin state, eliminating polling and the arbitrary post-wait delay. StoreRuntimeInspection
centralizes JVM private-field traversal. Lane/map/queue-child bounds and weak-reference assertions
still inspect the real structures, with no production test hook or weakened retention assertion.
GC sampling and deliberate load delays in soak tests are not coroutine completion signals.

InternalError is renamed StoreBoundaryFailure and reported becomes handlerFailed, describing
what the flag actually means. boundaryCause consistently unwraps one Store boundary layer only.
Classification of active CancellationException, coroutine cancellation, fatal user Throwable,
plugin failures and recovery failures is preserved. StoreFailureProtocolTest verifies that user
cause identities survive recovery/reporting, with the original error suppressed on failed recovery.
The existing isolated JVM process also exercises throwing exception handlers after saver and
onState failures: exactly one handler call/report, committed state retained, no DSL recovery,
handler failure escapes and the input finishes Failed with that error.

All 11 obsolete @deprecated KDoc block tags were replaced with compatibility prose; Deprecated
annotations, replacements and callable signatures remain unchanged. Detekt confirms no new finding;
the exact core baseline shrinks from 39 to 26 IDs: 11 documentation findings, one swallowed
exception and one complex coroutine fixture are resolved. No suppression, exclusion, threshold
or baseline expansion was introduced. TooManyFunctions on the two cohesive public DSL builders
remains documented existing debt; their surfaces were not split solely to satisfy the counter.

Validation: 1715 tests passed (core JVM 237, JS/Wasm/iOS 226 each; statechart 564,
actron-test 45, observability 57, timetravel 134), with no failures or skips. Core API/ABI,
including PublishedApi declarations, nullability guard, aggregate Detekt and standalone ktlint
passed. API dumps were not changed. The core baseline is a strict subset of the previous 39 IDs.


## TD-8: Detekt baseline cleanup in two passes — 2026-10-24

The agreed plan targeted 15 of the remaining 26 baseline IDs while preserving the other 11.
The real Detekt engine exposed a planning error: TooGenericExceptionCaught flags both Throwable
and Exception. Narrowing the two StoreLaunchedWork catches passed JVM/API checks but merely
replaced its Throwable ID with an Exception ID. That change was reverted; the original boundary
and ID remain. No runCatching, ignored exception name, new wrapper boundary, baseline migration,
suppression, exclusion or rule change was used to hide the finding. The honest result is 14
resolved IDs and a strict subset of 12 retained IDs, rather than a claimed 26-to-11 reduction.

Pass 1 resolves seven local findings. Forced counter failures and repository-operation failures
use fixture-specific exception types with the original messages. The base RuntimeException
compatibility fixture is unchanged. The event-hook self-await test checks the actual guard
message, instead of accepting any IllegalStateException. The close test retains its CPU-bound,
non-suspending tail with Thread.onSpinWait, removes its empty-body compiler suppression and waits
for pending work to terminate instead of sleeping; state and persistence assertions are retained.

Pass 2 resolves seven complexity findings in six places. A per-definition RoutingGenerator owns
the seeded RNG and allocated IDs, separating blocks, inner handlers and declarations without
changing RNG consumption, including choices discarded by legacy branches. Pre-refactor
fingerprints for 256 seeds in each legacy mode are fixed in RoutingGenerationSnapshotTest and
match after extraction. The independent RoutingProgram.expect oracle is unchanged. JVM probe
failure injection is separated from resource lifecycle and assertions; the isolated subprocess
and all failure/accounting checks remain. Todo registration is grouped by Loaded/Editing state,
WebSocket connection/message stream consumers retain their original state-owned launch and
transaction scopes. Stress tests separate workload generation, trace observation and invariant
assertions. Observation still waits for all accepted dispatch terminals, including discards after
StoreClosed; duplicate-end, missing-end, discard-reason, dense-ordinal and post-close assertions
are unchanged. Soak pacing and closed-state stability windows remain; the producer checks its
current coroutine Job, preserving cancellation identity after extraction from the launch body.

The remaining 12 IDs are intentional, reviewable boundaries: eight broad-catch IDs (including
StoreLaunchedWork), two cohesive public DSL function-count findings, the independent expectation
oracle's complexity, and the base RuntimeException compatibility fixture. They are not a blanket
permission to accept new findings. Every removed ID is verified against the real engine, and no
new ID is accepted. Public API dumps and runtime implementation remain unchanged by this pass.

Validation: 1719 tests passed (core JVM 238, JS/Wasm/iOS 227 each; statechart 564,
actron-test 45, observability 57, timetravel 134), with no failures or skips. Core API/ABI,
nullability guard, aggregate Detekt and standalone ktlint passed. The 12 remaining IDs are all
members of the original 26-ID baseline. StoreLaunchedWork is byte-for-byte unchanged after
reverting the ineffective catch narrowing; API dumps are unchanged.

## TD-9: Final review lifecycle and attribution fixes

Three existing defects found during the final ownership review are fixed without changing public
signatures or API dumps. StateRuntime removes completed subscriptions through the same mutex-owned
cleanup path used for launch lanes. Registration precedes the completion callback, so an already
completed subscription is removed too. Live subscriptions remain excluded from ordinary idle.
StorePublication reports event-hook errors with the attribution captured before event emission,
instead of borrowing a later processing input or losing origin after the initiating handler ends.

StoreExecution exposes the existing cancelled-but-incomplete root Job as a completion barrier.
StoreIdleWaiter joins it on close or parent cancellation, including state, subscription and plugin
cleanup. No separate close flag or eager scope creation is introduced. Timeout reports one pending
root barrier through the existing launches count; this closing-state meaning is documented in
StoreInternalApi and StorePendingWork. Ordinary idle and self-await guards retain their contracts.

Seven regression tests failed before the fixes for the expected reasons: 101 retained subscriptions
instead of one live subscription, event-hook failures attributed to Unattributed or unrelated input
#3 instead of #2, and early idle during blocked cleanup in four shutdown scenarios. They now pass.
StoreSubscriptionRetentionJvmTest exercises scheduled and immediate completion on Standard and
Unconfined test dispatchers, including a live enter subscription. StoreEventOriginTest exercises
both idle and concurrent-handler emission. StoreCloseCompletionTest verifies timeout, suspended
waiting, released cleanup, and exactly one StoreClosed trace for launch/subscribe/plugin work and
parent cancellation. Existing passive-idle coverage still passes.

Validation: 1744 tests passed with no failures or skips: core JVM 245, JS/Wasm/iOS 233 each,
statechart JVM 564, actron-test JVM 45, observability JVM 57 and timetravel JVM 134. Core API/ABI,
nullability guard and aggregate Detekt passed. The 12-ID core baseline and analysis rules are
unchanged. Standalone ktlint passed after platform compilation, avoiding concurrent build-tree
traversal. No public API or format version changes, commits or pushes are part of these fixes.

## TD-10: Additive observation and completion API

StoreLifecycle<S, E> and three core extensions add observeState/observeEvent returning AutoCloseable,
and suspend closeAndJoin. Store's existing interface, JVM/klib signatures and old callback APIs are
unchanged. Existing callback entry points share the new observer implementation. Unsupported custom
Store implementations remain source/binary compatible and fail explicitly before side effects;
wrappers can implement or delegate the capability. Both machine adapters provide the same capability,
retaining their original collection contexts and event sources. API dumps add only the new public
interface and extensions; the statechart dumps do not change.

An observer handle requests cancellation of its own Job, repeatedly and without waiting; callbacks
already executing may finish. State registration remains asynchronous and conflates; event replay,
startup policies and callback failure policy are unchanged. TD-11 strengthens the new observeEvent
registration boundary while retaining the legacy collectEvent schedule. A root
ownership marker distinct from the transition/self-await marker rejects closeAndJoin from ordinary
launches as well as locked processing, including NonCancellable cleanup, before cancelling anything.
A peer Store can be awaited. The wait is cancellable but does not reverse the Store's cancellation.
Machine adapter ownership survives processing-context overrides in DSL and patches.

Ten core tests cover independent observer cancellation, early cancellation, closed Store observation,
startup policies, actual dispatcher scheduling, self-cancellation, callback failure isolation, legacy
custom implementations, cleanup completion/repeated close, cancelled waiting, own launch/transaction
and NonCancellable self-join, peer closing, and passive shutdown. The initial negative control ran nine
tests: eight failed on missing implementations, while legacy compatibility passed. Three adapter
scenarios cover machine and invocation observation/cancellation, completion of command cleanup,
self-join rejection and configured/patched contexts. Existing full suites are also checked.

The user explicitly authorized migration of exactly two existing statechart TooManyFunctions IDs
whose class signatures now include StoreLifecycle. The baseline remains 201 entries, with the same
classes/rules; no thresholds, suppressions or exclusions change. Core's 12-ID baseline is unchanged.

Validation for TD-10: 3425 tests passed, no failures or skips. Core JVM 255 and JS/Wasm/iOS
243 each; statechart JVM 567 and JS/Wasm/iOS 537 each; Compose JVM 27, actron-test JVM 45,
observability JVM 57, timetravel JVM 134. Core/statechart JVM and klib apiCheck, nullability,
aggregate Detekt and ktlint passed. Only the two explicitly authorized baseline IDs were migrated.


## TD-11: Decompose consumer integration

The consuming KMP project's current ComponentContext/InstanceKeeper/StateFlow/typed Bridge patterns
were inspected read-only. A separate actron-decompose adapter targets Decompose 3.5.0, the version
used by that project and the existing weather example. It provides lifecycle-bound observeState
and observeEvent overloads. A destroyed component cancels its own observers, manual close releases
the lifecycle callback, and an already destroyed/reentrantly destroyed lifecycle cannot leave an
observer registered. The retained Store is independently owned by InstanceKeeper through Essenty's
existing getOrCreateCloseable; no second retention framework or mutable state copy is introduced.
No Messenger feature or user-owned weather build changes are part of this integration.

A distinguishing core test found a real registration gap: an emitter queued before observeEvent
could run after the handle returned yet before the collector was registered, dropping the event.
It failed with expected [Changed], actual []. The new observeEvent starts collection UNDISPATCHED
up to its first suspension, making registration ready on return; future callbacks retain Store
execution context. Legacy collectEvent keeps its original asynchronous launch. Both machine adapters
respect the same boundary. This is an additive, previously unreleased API contract, not a change to
legacy collection semantics. State observation remains asynchronous/conflated.

Four common adapter tests exercise real DefaultComponentContexts sharing a keeper, endpoint-observer
replacement on recreation, keeper shutdown, independent manual/lifecycle cancellation, already-dead
lifecycle and destruction during an immediate initial-state callback. README wiring shows retained
factory creation, direct read-only state, current endpoint mapping and separate input subscription
cancellation. StateKeeper/process-death schemas and navigation remain the application's concern.

Validation for TD-11: 3445 tests passed without failures or skips. Core JVM 256 and JS/Wasm/iOS
244 each; statechart JVM 567 and JS/Wasm/iOS 537 each; Decompose adapter 4 on each of JVM, JS,
Wasm and iOS; Compose JVM 27, actron-test JVM 45, observability JVM 57, timetravel JVM 134.
API/ABI for core, statechart and the new adapter, debug dependency graph, nullability and aggregate
Detekt passed. The adapter has zero baseline debt; no checks were weakened. The new transitive
Decompose iOS artifact missing from offline cache was fetched from Maven without version changes.
