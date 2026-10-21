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

For recording and replay, add [actron-timetravel](../actron-timetravel/README.md) only to the
build that needs debugging. For an application with separate debug and release wiring, see
[Weather](../examples/weather/README.md#debug-and-release-initialization).
