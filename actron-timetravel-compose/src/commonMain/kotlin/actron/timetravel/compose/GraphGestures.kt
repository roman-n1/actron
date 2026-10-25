package actron.timetravel.compose

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

internal fun Modifier.graphGestures(
    layout: GraphLayout,
    viewport: GraphViewport,
    selection: GraphInteraction,
    surface: GraphSurface,
): Modifier =
    onSizeChanged { surface.resize(Size(it.width.toFloat(), it.height.toFloat()), viewport, layout.size) }
        .pointerInput(viewport, layout, selection) { detectTapGestures { selection.select(it, layout, viewport) } }
        .pointerInput(viewport) { detectTransformGestures { centroid, pan, zoom, _ -> viewport.transform(centroid, pan, zoom) } }
        .pointerInput(viewport) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll) {
                        event.changes.forEach { change ->
                            viewport.scroll(change.position, change.scrollDelta, event.keyboardModifiers)
                            change.consume()
                        }
                    }
                }
            }
        }
