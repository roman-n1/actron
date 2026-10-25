# StateChart is the application reducer

- Date: 2026-10-25
- Status: accepted by the project owner; supersedes standalone Store/legacy adapter guidance

## Decision

Actron applications use our concrete `Machine` implementation built by the StateChart DSL,
and execute it with `MachineStore`. `retainedMachine` integrates that same path with Decompose.
We do not introduce a pluggable reducer interface or a handwritten `when` replacement.
Ordinary Kotlin expressions remain available inside StateChart guards, context updates and hooks.

Remove the public `Store(...)` factories. Rename the cross-module engine bridge to
`createRuntimeStore` and guard it and `StoreBuilder` with error-level `InternalActronApi`.
The old coroutine `StateChartStore` adapter and its scopes are test fixtures, excluded from production artifacts. No deprecated aliases
or compatibility path are retained. `Store` remains the observation/dispatch/lifetime contract
implemented by the machine runtime; it is not an application reducer extension point.

The generic engine stays below StateChart to avoid dependency cycles with observability and
adapters. Module boundaries describe implementation ownership, not alternative user products.
Its scheduling/cancellation tests and the internal legacy adapter regression tests remain.

`MachineConfiguration<C, A, E>` exposes plugins using normal feature actions. Configured plugins
share the existing admission/event adapter used by runtime test patches; scheduler inputs stay
internal. The generic settings delegate is internal and holds immutable constructor settings.

Message sending is a suspending top-level operation callable by command handlers, without
requiring a fake scope from the removed construction DSL. Messages remain transient and are
not part of deterministic replay; recorded machine interactions use MachineGroup routes.

## Consequences and verification

One supported decision/execution path reduces setup choices and keeps recording, restoration,
commands and timers under the same contracts. Applications must migrate from standalone Store
handlers and coroutine chart activities to StateChart transitions plus command handlers.
This is an intentional source/ABI change. Published-consumer compile contracts must reject the
removed factories and internal bridge while a real machine/logger/message smoke still passes.
API dumps track the reduced surface. Runtime semantics and serialized formats do not change.

Reconsider a reducer extension point only for a concrete requirement the StateChart DSL cannot
express, with a working second implementation and tests for the full command/timer protocol.
