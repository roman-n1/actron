package actron.timetravel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/** Right-hand inspector on wide windows, bottom inspector on narrow windows. */
@Composable
fun ResizableHistoryLayout(
    state: HistoryPanelLayoutState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    panel: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val horizontal = maxWidth >= 700.dp
        val density = LocalDensity.current
        val available = if (horizontal) maxWidth else maxHeight
        val pixels = with(density) { available.toPx() }
        val fraction = if (horizontal) state.widthFraction else state.heightFraction
        val panelSize = available * fraction
        val label = historyText(HistoryText.Resize)
        val handle: @Composable () -> Unit = { HistoryResizeHandle(state, horizontal, pixels, fraction, label) }
        if (horizontal) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                handle()
                Box(Modifier.width(panelSize).fillMaxHeight()) { panel() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                handle()
                Box(Modifier.height(panelSize).fillMaxWidth()) { panel() }
            }
        }
    }
}

@Composable
private fun HistoryResizeHandle(
    state: HistoryPanelLayoutState,
    horizontal: Boolean,
    pixels: Float,
    fraction: Float,
    label: String,
) {
    Box(
        (if (horizontal) Modifier.width(12.dp).fillMaxHeight() else Modifier.height(12.dp).fillMaxWidth())
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag("history-resize-handle")
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, if (horizontal) 0.25f..0.8f else 0.3f..0.8f)
                setProgress { requested -> state.resize(horizontal, requested) }
            }.draggable(
                orientation = if (horizontal) Orientation.Horizontal else Orientation.Vertical,
                state =
                    rememberDraggableState { delta -> state.drag(horizontal, delta, pixels) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            (if (horizontal) Modifier.width(3.dp).height(40.dp) else Modifier.height(3.dp).width(40.dp))
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
    }
}
