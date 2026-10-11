# Weather: from asynchronous inputs to a deterministic recording

A Compose Multiplatform MVI demo in this repository. Run the desktop application directly on
macOS, without an emulator, or run the same shared machines, presentation Store and Compose
UI in a browser.

```sh
# Live weather, no API key or account
./gradlew :weather-example:run

# Explicit sample data, useful without a network connection
./gradlew :weather-example:run --args='--offline'

# Verify recorded responses, replay and branching without a window
./gradlew :weather-example:run --args='--check'
# The same check against the real API (requires network)
./gradlew :weather-example:run --args='--check-live'

# Browser application
./gradlew :weather-example:jsBrowserDevelopmentRun
```

The live adapter calls [Open-Meteo's forecast API](https://open-meteo.com/en/docs), requesting
current temperature, apparent temperature, wind speed and weather code for one of four
predefined cities. These are model-based current conditions. Units are explicitly Celsius and
km/h. The screen attributes the data to Open-Meteo (CC BY 4.0). API failures are displayed;
the application does not silently replace live results with sample data.
The explicit offline scenario adds 1.2 seconds of synthetic latency, so rapid selections
exercise cancellation even without an internet connection. Replay retains the resulting
inputs and never repeats that delay or runs the sample loader.

## Try the workflow

1. Choose a city. The second screen shows weather and a recommendation. Three independently
   running machines coordinate through typed bridge effects: **Location**, **Weather** and
   **Advice**. Weather has loading, success, failure and timeout states; previous requests are
   cancelled when a newer city replaces them, and a guard rejects answers for a different city.
2. Try **Rapid city changes**. The machines process several selections while requests may be
   in progress. Earlier loading activations are cancelled; the final selection wins. The journal
   and machine recordings were installed at startup, before opening the panel.
3. **Capture history** takes a consistent group cut. The panel and app preview now use the same
   recorded group cursor. Back, Next or a selected input moves the historical screen. Verify
   re-decides every recorded input and checks group causality, without calling the API.
4. Select the Weather machine and find a loading checkpoint, or start an experiment and press
   Refresh. **Experiment here** creates an isolated branch. Answer the awaiting command with a
   recorded response, scripted wind or heat, or a scripted connection failure. Advance the
   virtual clock to the eight-second loading deadline to test a timeout. Advice updates through
   the local branch bridge. No real command handler runs in the branch.
5. **Return to recording** discards the experiment and keeps the original replay cursor.
   **Return to live** shows the independent live Stores again.

Use **Panel: Bottom / Left / Right** to move the same `TimeTravelPanel` component. Drag the
divider to resize the dock. Narrow browser windows fall back to a bottom dock to keep the app
visible. There is no second debug window and no tool-tab navigation.

## MVI and determinism

`WeatherDemoStore` is an Actron presentation Store: the View sends `DemoIntent`s, a pure
`reduceWeatherDemo` creates the next immutable state, and lifecycle-owned effects perform
startup, capture and subscriptions. `WeatherDemoScreen` renders the state and dispatches
intents. Only UI geometry and menu visibility are local Compose state.

`WeatherRuntime` owns the three MachineStores, command handler, journal and group recorder.
Pure definitions in `WeatherMachines` never read the network, wall clock or random values.
An asynchronous response becomes a recorded `MachineInput.CommandResult`. Its data, ordering
and machine time make the run replayable. The original API may change tomorrow; replay still
receives the recorded answers. A missing branch answer stays awaiting, with no network fallback.

The demo keeps recordings in memory for this application session; machine recordings grow with
the run, while weather/error context histories are bounded. They are cleared when the app closes.
For durable files and reopening after process
exit, see the separate [disk-to-inspector checkout demo](../time-travel/README.md).

## Verification

```sh
./gradlew :weather-example:jvmTest :actron-timetravel-compose:jvmTest
./gradlew :weather-example:compileKotlinJs
```

Tests use controlled responses and virtual time, not the public service. They cover request
cancellation, response parsing, replay/branch without further API calls, virtual timeout,
MVI navigation, historical preview isolation and all three dock placements. The demo is
unpublished, excluded from library API dumps and classified as a debug consumer by
`checkDebugGraph`. Shared code currently targets Desktop/JVM and JS browsers; Android/iOS
hosts are not included in this example.
