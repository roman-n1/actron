package actron.timetravel.compose

import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.recording_end
import actron.timetravel.compose.resources.replay_forward_disabled
import actron.timetravel.compose.resources.replay_verified_steps
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource

/** The controls expose facts; native resources supply presentation copy. */
sealed interface ForwardStepAvailability {
    data object Available : ForwardStepAvailability

    data object EndReached : ForwardStepAvailability

    data class Diverged(
        val description: String,
    ) : ForwardStepAvailability
}

@Composable
internal fun ForwardStepMessage(
    availability: ForwardStepAvailability,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.tertiary,
) {
    when (availability) {
        ForwardStepAvailability.Available -> Unit
        ForwardStepAvailability.EndReached -> Text(text = stringResource(resource = Res.string.replay_forward_disabled, stringResource(resource = Res.string.recording_end)), modifier = modifier, color = color, style = MaterialTheme.typography.bodySmall)
        is ForwardStepAvailability.Diverged -> Text(text = stringResource(resource = Res.string.replay_forward_disabled, availability.description), modifier = modifier, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ReplayVerdict(
    verification: ReplayVerification,
    modifier: Modifier = Modifier,
) {
    when (verification) {
        ReplayVerification.Unchecked -> Unit
        is ReplayVerification.Passed -> Text(text = stringResource(resource = Res.string.replay_verified_steps, verification.steps), modifier = modifier, style = MaterialTheme.typography.bodySmall)
        is ReplayVerification.Diverged -> Text(text = verification.description, modifier = modifier, style = MaterialTheme.typography.bodySmall)
    }
}
