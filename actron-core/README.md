# actron-core

The execution engine beneath Actron's StateChart machines. Applications create a
[`MachineStore`](../actron-statechart/README.md) or use
[`retainedMachine`](../actron-decompose/README.md) for Decompose components.

The standalone `Store(...)` construction DSL has been removed. `StoreBuilder` and
`createRuntimeStore` are internal module infrastructure guarded by `InternalActronApi`.
There is no supported handwritten reducer replacement. Compatibility aliases are not provided.

## Public runtime contracts

`Store` remains the read/dispatch/lifetime contract implemented by `MachineStore`. Compose,
plugins and test helpers share it without introducing another state writer. `Action`, `Event`,
observation handles, execution policies and failure handlers support the machine runtime.

Create one machine per owner, dispatch typed actions, observe immutable snapshots, and close
it when the owner ends. `closeAndJoin()` also waits for cancellation cleanup. Lifecycle-bound
observation belongs to the current component; it does not own a retained machine's lifetime.

A machine decision is the sole writer of a snapshot. StateChart hooks describe commands;
command handlers perform I/O and send typed result actions. Internal coroutine handlers,
transactions and recovery machinery are implementation details, not alternative application APIs.

## Internal reference

The [engine reference](../doc/guides/store-api.md) documents low-level scheduling, plugin,
recovery and cancellation contracts for library maintainers. Its regression tests remain in
this module. Applications start with the [typed counter](../README.md#1-a-store-for-a-counter)
or the [Weather example](../examples/weather/README.md).

See the [StateChart-only API decision](../doc/internal/adr/2026-10-25-statechart-only-api.md).
