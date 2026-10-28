package actron.example.weather.core.presentation

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Immutable presentation text; resource lookup follows the current Compose locale. */
data class WeatherText(
    val resource: StringResource,
    val arguments: List<Any> = emptyList(),
)

@Composable
internal fun WeatherText.resolve(): String = stringResource(resource = resource, formatArgs = arguments.toTypedArray())
