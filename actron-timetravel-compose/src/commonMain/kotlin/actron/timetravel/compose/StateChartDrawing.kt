package actron.timetravel.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStateChartEdges(
    edges: List<ChartEdgePresentation>,
    highlight: androidx.compose.ui.graphics.Color,
    outline: androidx.compose.ui.graphics.Color,
) {
    fun center(position: Pair<Int, Int>): Offset {
        val (x, y) = position
        return Offset((x * 190 + 97).dp.toPx(), (y * 96 + 48).dp.toPx())
    }
    for (edge in edges) {
        val from = center(edge.from)
        val to = center(edge.to)
        val color = if (edge.highlighted) highlight else outline
        drawStateChartArrow(from, to, color)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStateChartArrow(
    from: Offset,
    to: Offset,
    color: androidx.compose.ui.graphics.Color,
) {
    if (from == to) {
        drawCircle(color, 26.dp.toPx(), from + Offset(55.dp.toPx(), -12.dp.toPx()), style = Stroke(2.dp.toPx()))
    } else {
        val vector = to - from
        val length = vector.getDistance()
        val direction = vector / length
        val xDistance = if (kotlin.math.abs(direction.x) < 0.001f) Float.POSITIVE_INFINITY else 79.dp.toPx() / kotlin.math.abs(direction.x)
        val yDistance = if (kotlin.math.abs(direction.y) < 0.001f) Float.POSITIVE_INFINITY else 30.dp.toPx() / kotlin.math.abs(direction.y)
        val border = minOf(xDistance, yDistance) + 5.dp.toPx()
        val end = to - direction * border
        val start = from + direction * border
        drawLine(color, start, end, strokeWidth = 2.dp.toPx())
        val normal = Offset(-direction.y, direction.x)
        drawLine(color, end, end - direction * 9.dp.toPx() + normal * 5.dp.toPx(), strokeWidth = 2.dp.toPx())
        drawLine(color, end, end - direction * 9.dp.toPx() - normal * 5.dp.toPx(), strokeWidth = 2.dp.toPx())
    }
}
