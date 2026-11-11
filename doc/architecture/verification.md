# Verification of the MVI/session change

All commands below completed successfully from the repository root with the local Gradle cache.

```sh
./gradlew :actron-statechart:apiDump :actron-decompose:apiDump :actron-decompose-session:apiDump :actron-timetravel-compose:apiDump :actron-decompose-session:analyze :weather:debugTools:analyze :nullability-guard:checkPolicy checkDebugGraph checkComposeNoBooleanLogic --offline
./gradlew :actron-statechart:apiCheck :actron-decompose:apiCheck :actron-decompose-session:apiCheck :actron-timetravel-compose:apiCheck :actron-statechart:jvmTest :actron-decompose:jvmTest :actron-decompose-session:jvmTest :actron-timetravel-compose:jvmTest :weather:shared:jvmTest :weather:debugTools:jvmTest :actron-decompose-session:jsNodeTest :actron-decompose-session:wasmJsNodeTest :weather:desktopApp:compileKotlin ktlintCheck detekt --offline
```

The first attempt at the second command used `:weather:desktopApp:compileKotlinJvm`, which does
not exist for that JVM application. It failed during task selection; the corrected command above
completed successfully. The earlier lifecycle destruction failure in the Weather UI suite was fixed
and covered by `RestorableComponentTest.parentDestructionClosesGenerationOnceAfterRepeatedCaptures`.

Key added tests:

- `MviPresentationTest`: UI payload maps to domain action; display projection reads committed state.
- `ScreenMachineOutputTest`: domain output commits without restarting an entry activation.
- `GroupMemberReleaseTest`: closed member removal and safe re-registration.
- `SessionControllerTest`: historical state restoration, encoded checkpoints, incompatible identity.
- `RestorableComponentTest`: ordinary childStack restoration, continued navigation and parent teardown.
- `HistoryContinuationRunnerTest`: asynchronous failure preserves selection/branch and permits retry.
- `WeatherContinuationTest`: search ↔ weather restoration, request cancellation, new input, encoding.
- `WeatherAppTest.continueFromSearchReplacesWeatherAndAcceptsNewInput`: clicks actual Compose
  continuation button after navigating to weather, returns to search and accepts a new city query.

Existing JVM tests also exercise pure transitions, guards, late command results, group routing,
effect delivery, coverage and imported-recording isolation. The new session tests ran on JVM,
JS Node and Wasm Node. API dump/check compiled affected iOS libraries, but iOS simulator/device
execution and Android device execution were not run. No live server-write recovery, Maven publication
or actual Messenger migration was performed. Builds retain existing compiler/deprecation warnings.
