# actron-persistence

Production checkpoint serialization and restoration for Actron machines and invocations.
This artifact depends on `actron-statechart` and serialization. It has no Compose UI,
recorder, event buffer, inspector, file recorder or replay executor.

Use `machinePersistence`, `invocationPersistence` and `checkpointGroup` to persist the
current executor state without adding a debug history dependency. Saving a checkpoint
does not start recording or retain previous checkpoints.

The existing `Recording`, `RecordedStep`, `RecordingCodec` and wire migrations live here
as shared data/serialization primitives. A checkpoint uses the same frozen representation
as a recording with no steps. These types retain their `actron.timetravel` package for
source and JVM class-name compatibility; their presence does not enable history recording.
`actron-timetravel` re-exports this artifact, so existing debug integrations keep their imports.
The JSON formats and their versions are unchanged.

Internally, `RecordingCodec` owns format selection and the public error contract.
`RecordingWireEncoder` owns canonical ordering; `RecordingWireReader` owns conversion
and validation of wire values. The wire schema and built-in migrations are kept separately.
Part decoders preserve their existing exception messages and omit internal causes;
the two corresponding Detekt baseline entries retain that compatibility contract.

`MachinePersistence` and `InvocationPersistence` capture live executors and create unstarted
stores for resumption. Their internal checkpoint codecs own serialization, definition/version
compatibility and resumability validation. The invocation codec composes the parent and child
machine codecs directly. Saving validates the composition before encoding child and parent.
Restoring checks document identity first, decodes child then parent, and validates the
reconstructed composition. Validation and application-serializer call order are preserved.

Checkpoint groups freeze their registrations when built. A binding validates saved values
and prepares a frame update; all bindings must succeed before `initial` or any frame writer
is called. Valid updates are applied in registration order. This prevents invalid saved data
from partially updating the host frame; it does not roll back a failing host writer.

For recording and replay, add [actron-timetravel](../actron-timetravel/README.md) only to the
build that needs debugging. For an application with separate debug and release wiring, see
[Weather](../examples/weather/README.md#debug-and-release-initialization).
