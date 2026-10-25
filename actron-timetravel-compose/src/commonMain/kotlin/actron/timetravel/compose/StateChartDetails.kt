package actron.timetravel.compose

import actron.statechart.StateChartDefinition
import actron.statechart.machine.MachineSnapshot
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun StateChartDetails(
    chart: StateChartDefinition,
    snapshot: MachineSnapshot<*>,
    selection: ChartSelection,
    inspection: ChartInspection,
) {
    when (selection) {
        ChartSelection.Overview -> Unit
        is ChartSelection.Node -> ChartDetail(chartNodeDetail(chart, snapshot, selection.id))
        is ChartSelection.Transition -> ChartDetail(chartTransitionDetail(chart, selection.id, inspection))
    }
}

@Composable
private fun ChartDetail(model: ChartDetailPresentation) {
    Text(model.heading, modifier = Modifier.testTag("chart-detail"))
    for (line in model.lines) Text(line)
}
