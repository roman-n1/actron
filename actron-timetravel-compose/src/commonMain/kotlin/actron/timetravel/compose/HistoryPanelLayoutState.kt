package actron.timetravel.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** Retain in the host to keep sizing across panel visibility and navigation changes. */
@Stable
class HistoryPanelLayoutState {
    var widthFraction: Float by mutableFloatStateOf(0.72f)
        private set
    var heightFraction: Float by mutableFloatStateOf(0.65f)
        private set

    fun resizeWidth(fraction: Float) {
        require(fraction.isFinite())
        widthFraction = fraction.coerceIn(0.25f, 0.8f)
    }

    fun resizeHeight(fraction: Float) {
        require(fraction.isFinite())
        heightFraction = fraction.coerceIn(0.3f, 0.8f)
    }
}

internal fun HistoryPanelLayoutState.resize(
    horizontal: Boolean,
    requested: Float,
): Boolean {
    if (!requested.isFinite()) return false
    if (horizontal) resizeWidth(requested) else resizeHeight(requested)
    return true
}

internal fun HistoryPanelLayoutState.drag(
    horizontal: Boolean,
    delta: Float,
    pixels: Float,
) {
    if (pixels > 0f) {
        if (horizontal) resizeWidth(widthFraction - delta / pixels) else resizeHeight(heightFraction - delta / pixels)
    }
}
