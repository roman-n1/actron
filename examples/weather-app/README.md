# Weather production application

Shared screens, machines, network repositories, Decompose components and current-checkpoint
persistence. Depends on `actron-persistence`; does not depend on any `actron-timetravel*` artifact.

`DefaultWeatherRootComponent(...)` initializes a `WeatherSession`. It retains only current
checkpoints, does not request decision explanations or serialize history fields, and adds no
debug controls to the screen. State restoration, request cancellation and timers still work.

The optional host UI slot is empty by default. The debug adapter in `weather-example` extends
the session and supplies the history panel and settings button. Android uses that adapter only
from `src/debug` with `debugImplementation`; `src/release` creates the ordinary root.

See [the complete example](../weather/README.md) for startup configuration and run commands.
