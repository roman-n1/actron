package actron.timetravel.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent

/** Let the editor handle native text shortcuts, then stop unused Alt+arrows reaching history.
 * Linux/Windows editors do not consume all the Option+arrow combinations used on macOS.
 */
internal fun Modifier.historyTextInput(): Modifier =
    onKeyEvent { event ->
        event.isHistoryNavigationModifier() &&
            (event.key == Key.DirectionLeft || event.key == Key.DirectionRight || event.key == Key.DirectionUp || event.key == Key.DirectionDown)
    }

internal fun KeyEvent.isHistoryNavigationModifier(): Boolean = isAltPressed && !isCtrlPressed && !isMetaPressed
