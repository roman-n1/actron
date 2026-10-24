package actron.timetravel.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

internal class GraphViewport {
    var initialized = false
        private set
    var scale by mutableStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    fun transform(
        anchor: Offset,
        pan: Offset,
        zoom: Float,
    ) {
        if (!zoom.isFinite() || zoom <= 0f) return
        val next = (scale * zoom).coerceIn(0.1f, 4f)
        offset = anchor - (anchor - offset) * (next / scale) + pan
        scale = next
    }

    fun fit(
        view: Size,
        graph: Size,
    ) {
        if (view.width <= 0f || view.height <= 0f) return
        initialized = true
        scale = minOf(view.width / graph.width, view.height / graph.height).coerceIn(0.1f, 1.5f)
        offset = Offset((view.width - graph.width * scale) / 2f, (view.height - graph.height * scale) / 2f)
    }
}
