package actron.timetravel.compose

import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.complete_history
import actron.timetravel.compose.resources.partial_history
import actron.timetravel.inspect.Completeness
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A pure presentation mapper selects copy; native resources resolve it for the current locale. */
internal data class PanelText(
    val resource: StringResource,
    val arguments: List<Any> = emptyList(),
)

internal fun panelText(
    resource: StringResource,
    vararg arguments: Any,
): PanelText = PanelText(resource = resource, arguments = arguments.toList())

internal fun completenessText(value: Completeness): PanelText =
    if (value.isComplete) {
        panelText(resource = Res.string.complete_history)
    } else {
        panelText(resource = Res.string.partial_history, value.reasons.size)
    }

@Composable
internal fun PanelText.resolve(): String {
    val values = arguments.map { value -> if (value is PanelText) value.resolve() else value }
    return stringResource(resource = resource, formatArgs = values.toTypedArray())
}
