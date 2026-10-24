package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun HistoryMuted(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun HistoryDetails.activeBefore(): String = before.filter { it.path == "active" || it.path == "screen" }.joinToString { it.value }.ifEmpty { "—" }

internal fun HistoryDetails.activeAfter(): String = after.filter { it.path == "active" || it.path == "screen" }.joinToString { it.value }.ifEmpty { "—" }

@Composable
internal fun HistoryModeSwitch(
    history: Boolean,
    historyAvailable: Boolean,
    onHistory: () -> Unit,
    onLive: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(3.dp).selectableGroup()) {
            HistoryModeOption(true, historyModeSelected(history, true), historyAvailable, onHistory)
            HistoryModeOption(false, historyModeSelected(history, false), true, onLive)
        }
    }
}

@Composable
private fun HistoryModeOption(
    isHistory: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (selected) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
    ) {
        Text(
            historyText(if (isHistory) HistoryText.History else HistoryText.Live),
            Modifier
                .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
                .testTag(if (isHistory) "history-mode-history" else "history-live")
                .padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Drawn geometry works even when the platform font lacks arrow glyphs. */
@Composable
internal fun HistoryArrow(
    back: Boolean = false,
    down: Boolean = false,
) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.6.dp.toPx()

        fun point(
            x: Float,
            y: Float,
        ): Offset = if (down) Offset(y * size.width, x * size.height) else Offset((if (back) 1f - x else x) * size.width, y * size.height)
        when (down) {
            false -> drawLine(color, point(0.15f, 0.5f), point(0.8f, 0.5f), stroke)
            true -> Unit
        }
        drawLine(color, point(0.5f, 0.2f), point(0.8f, 0.5f), stroke)
        drawLine(color, point(0.5f, 0.8f), point(0.8f, 0.5f), stroke)
    }
}
