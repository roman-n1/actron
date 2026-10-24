package actron.timetravel.compose

import actron.statechart.Transition
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

internal fun DrawScope.drawGraph(
    appearance: GraphAppearance,
    viewport: GraphViewport,
    colors: ColorScheme,
    measurer: TextMeasurer,
    style: TextStyle,
    labels: Map<Transition, TextLayoutResult>,
) {
    clipRect {
        withTransform({
            translate(viewport.offset.x, viewport.offset.y)
            scale(viewport.scale, viewport.scale, Offset.Zero)
        }) {
            appearance.nodes.forEach { drawGraphNode(it, colors, measurer, style) }
            appearance.edges.forEach { drawGraphEdge(it, colors) }
            // Each label has measured, reserved space, independent of viewport zoom.
            appearance.edges.forEach { drawGraphLabel(it, labels) }
        }
    }
}

private fun DrawScope.drawGraphNode(
    appearance: GraphNodeAppearance,
    colors: ColorScheme,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val rect = appearance.node.bounds
    drawRoundRect(appearance.fill, rect.topLeft, rect.size, CornerRadius(12f))
    drawRoundRect(appearance.outline, rect.topLeft, rect.size, CornerRadius(12f), style = Stroke(appearance.stroke))
    val text = measurer.measure(appearance.title, style, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (rect.width - 24f).toInt()))
    drawText(text, topLeft = rect.topLeft + Offset(12f, 18f))
    if (appearance.initial) drawCircle(colors.primary, 4f, rect.topLeft + Offset(5f, 34f))
}

private fun DrawScope.drawGraphEdge(
    appearance: GraphEdgeAppearance,
    colors: ColorScheme,
) {
    val edge = appearance.edge
    val path =
        Path().apply {
            moveTo(edge.points.first().x, edge.points.first().y)
            edge.points.drop(1).forEach { lineTo(it.x, it.y) }
        }
    drawPath(path, colors.surface, style = Stroke(5f))
    val stroke =
        if (appearance.stroke.dashed) {
            Stroke(appearance.stroke.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
        } else {
            Stroke(appearance.stroke.width)
        }
    drawPath(path, appearance.color, style = stroke)
    val end = edge.points.last()
    drawLine(appearance.color, end, end + Offset(8f, -5f), 1.5f)
    drawLine(appearance.color, end, end + Offset(8f, 5f), 1.5f)
}

private fun DrawScope.drawGraphLabel(
    appearance: GraphEdgeAppearance,
    labels: Map<Transition, TextLayoutResult>,
) {
    val rect = appearance.edge.label
    drawRoundRect(appearance.labelFill, rect.topLeft, rect.size, CornerRadius(8f))
    drawRoundRect(appearance.labelOutline, rect.topLeft, rect.size, CornerRadius(8f), style = Stroke(appearance.labelStroke))
    drawText(labels.getValue(appearance.edge.transition), topLeft = rect.topLeft + Offset(12f, 8f))
}
