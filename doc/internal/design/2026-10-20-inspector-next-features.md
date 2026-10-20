# Inspector follow-up proposals

Status: discussion only. None of these six proposals is implemented by the temporal-testing/profiler change.
The implemented work is temporal rules, executor schedule exploration, mutation checks and graph profiling.

## 4. Regression test from a recording

Developer flow: select a compatible executable recording and a range, choose the observed failure,
export a Kotlin test fixture and a skeleton assertion. Replay must use scripted results, never real IO.
Reuse codec compatibility, existing replay and shrinking. A display-only import cannot reconstruct
commands or executable context; offer a UI fixture instead and explain the distinction.

The user supplies the oracle: reproducing a recorded bug is not establishing correct behavior.
Start with an API/CLI emitting fixture + test skeleton; the inspector button can follow.
Acceptance: round trip a stale-response bug, produce a failing assertion before its fix and a passing
one after it; reject incompatible definitions and insufficient display recordings.
Open question: generated Kotlin payload builders versus a versioned fixture codec maintained by the app.

## 5. Conditional live breakpoints

Developer flow: click a node/edge, add a typed condition, then inspect a cut and process one queued input.
Reuse checkpoint coordination, but define a distinct debug pause protocol. Network/DB processes do not
pause with the Store. Bound buffered inputs and show overflow; do not silently discard them.

Start with a single executor and break after commit, before subsequent input processing. Do not
promise atomic suspension of multiple stores until a group pause protocol is tested.
Acceptance: commands completing while paused, timer deadlines, cancellation and closing, full buffers,
and leaving debug mode without changing ordering. This is high complexity because pausing changes timing.
Open question: timers continue wall time or freeze logical time; make the mode visible.

## 6. Fault controls for commands

Developer flow: pick a command type and configure delay, failure, or a scripted answer in a debug executor.
Rules must be typed and per-instance; no reflection to construct arbitrary payloads. The normal handler
is wrapped outside pure machine decisions. Clearly show active fault controls and allow reset.

Start with delay and deterministic failure before execution. Do not rerun an external write or pretend
a completed remote operation was cancelled. Test scope exit, matching, bounded delayed buffers and cleanup.
Open question: whether the host opts specific read-only commands into result replacement.

## 7. Behavioral comparison between versions

Developer flow: run a fixed corpus against old/new definitions; show the first differing decision,
context projection, commands or timers. Reuse structural diff, replay and explicit snapshot migrations.

Adapters map renamed actions/context and intentional differences. Compare stable semantics rather than
raw activation counters. No inferred migration of arbitrary Kotlin objects.
Acceptance: guard-body regression missed by structural diff; intended changes allowed explicitly;
unsupported inputs reported as incomplete rather than equal. Start in CI, then add inspector side-by-side.
Open question: normalized observation schema owned by the application versus by library adapters.

## 9. Causal graph across machines

Developer flow: select an event, expand its actual parents/children across machine instances and commands.
Reuse CausalityTracker/CausalTraceHub and journal bridge identities. Timestamp proximity never creates
an edge. Missing ancestors from ring eviction or imported data render as incomplete links.

Start with a read-only swimlane view for one selected causal chain; avoid showing the whole session at once.
Acceptance: multiple instances of one definition, fan-out, cancellation, late replies, missing parents,
and stable selection during recording. Can be built independently of breakpoint coordination.
Open question: export causal metadata with display recordings under an explicit schema bump.

## 10. Typed export redaction

Developer flow: define field projections once, preview the exact export, then save. Reuse existing
application-owned projections and codec validation; add reusable typed transformations and diagnostics.
Support omission, masking and per-export stable pseudonyms. Do not claim automatic detection of secrets.

Display imports and executable replay have different contracts: redaction may destroy inputs needed by
reducers. Mark that loss of replay capability. Avoid storing a global pseudonym dictionary or raw values.
Acceptance: nested payloads, actions and errors as well as snapshots, deterministic equality within a file,
no raw data in metadata, explicit behavior for unmapped fields. Prioritize before real-user bug sharing.
Open question: default allowlist for display projections versus explicit annotations in app DTOs.

## Suggested order after the current implementation

If bug reports already arrive as executable recordings, start with test-fixture export (#4).
If recordings contain real user data, prioritize redaction (#10). Causal visualization (#9) is next
for multi-machine applications. Version comparison (#7) becomes valuable with a maintained corpus.
Fault controls (#6) need explicit handler boundaries; live breakpoints (#5) carry the largest scheduling risk.
