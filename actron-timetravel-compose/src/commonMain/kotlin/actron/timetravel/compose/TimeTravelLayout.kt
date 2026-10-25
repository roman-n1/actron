@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import actron.observability.StoreInstanceId
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PanelLayout(
    state: InspectorState,
    mode: InspectorMode,
    stores: List<StoreInstanceId>,
    modifier: Modifier,
    navigation: @Composable () -> Unit = {},
    content: @Composable (Boolean, Boolean) -> Unit,
) {
    val expanded = remember(state) { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth().testTag("time-travel-panel"), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints {
            val bounded = constraints.hasBoundedHeight
            val vertical = panelVertical(maxWidth, maxHeight)
            Column {
                PanelHeader(state, stores, mode, expanded)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { navigation() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val body = if (bounded) Modifier.weight(1f, fill = false) else Modifier
                Column(modifier = body.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (problem in panelCompleteness(state)) PanelProblem(problem)
                    content(expanded.value, vertical)
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(
    state: InspectorState,
    stores: List<StoreInstanceId>,
    mode: InspectorMode,
    expanded: androidx.compose.runtime.MutableState<Boolean>,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        PanelMachinePicker(state, stores)
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(vertical = 10.dp)) {
            Text(
                when (mode) {
                    InspectorMode.Live -> "Live"
                    InspectorMode.Inspect -> "History"
                    InspectorMode.Replay -> "Replay"
                    InspectorMode.Branch -> "Experiment"
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp).testTag("panel-mode"),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        TextButton(onClick = { expanded.toggle() }, modifier = Modifier.testTag("panel-details-toggle")) { Text(if (expanded.value) "Hide details" else "Details") }
    }
}

@Composable
internal fun PanelMachinePicker(
    state: InspectorState,
    stores: List<StoreInstanceId>,
) {
    var open by remember(state) { mutableStateOf(false) }
    val filter = state.filter
    Box {
        TextButton(onClick = { open = true }, enabled = stores.isNotEmpty(), modifier = Modifier.testTag("panel-machines")) {
            Text(if (filter is StoreFilter.Single) filter.store.value else "All machines (${stores.size})", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All machines") }, onClick = {
                state.filter = StoreFilter.All
                open = false
            }, modifier = Modifier.testTag("panel-machine-all"))
            for (store in stores) {
                DropdownMenuItem(text = { Text(store.value) }, onClick = {
                    state.filter = StoreFilter.Single(store)
                    open = false
                }, modifier = Modifier.testTag("panel-machine-${store.value}"))
            }
        }
    }
}
