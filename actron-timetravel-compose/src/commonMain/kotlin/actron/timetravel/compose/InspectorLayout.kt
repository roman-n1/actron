@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.core.ExperimentalActronApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalActronApi::class)
@Composable
internal fun InspectorScreenContent(
    state: InspectorState,
    mode: InspectorMode,
    modifier: Modifier,
    navigation: @Composable () -> Unit,
    timeline: @Composable (Modifier) -> Unit,
    widePosition: @Composable (Modifier) -> Unit,
    stackedPosition: @Composable (Modifier) -> Unit,
    afterPosition: @Composable (Modifier) -> Unit = {},
    showTimeline: Boolean = true,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ModeBarContent(state, mode)
            navigation()
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth >= 900.dp) {
                    InspectorWideLayout(state, showTimeline, timeline, widePosition)
                } else {
                    InspectorStackedLayout(state, showTimeline, timeline, stackedPosition, afterPosition, maxHeight)
                }
            }
        }
    }
}

@Composable
private fun InspectorWideLayout(
    state: InspectorState,
    showTimeline: Boolean,
    timeline: @Composable (Modifier) -> Unit,
    widePosition: @Composable (Modifier) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        StoresPanel(state, modifier = Modifier.weight(1f).fillMaxSize())
        if (showTimeline) {
            InspectorRule()
            timeline(Modifier.weight(2f).fillMaxSize())
        }
        InspectorRule()
        widePosition(Modifier.weight(2f).fillMaxSize())
    }
}

@Composable
private fun InspectorStackedLayout(
    state: InspectorState,
    showTimeline: Boolean,
    timeline: @Composable (Modifier) -> Unit,
    stackedPosition: @Composable (Modifier) -> Unit,
    afterPosition: @Composable (Modifier) -> Unit,
    paneHeight: Dp,
) {
    // Each nested scrolling pane needs a finite viewport inside the screen scroll.
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StoresPanel(state, modifier = Modifier.fillMaxWidth())
        if (showTimeline) {
            HorizontalDivider()
            timeline(Modifier.fillMaxWidth().height(360.dp))
            HorizontalDivider()
            stackedPosition(Modifier.fillMaxWidth().heightIn(max = paneHeight))
        }
        afterPosition(Modifier.fillMaxWidth().heightIn(max = paneHeight))
    }
}

@Composable
internal fun ModeBarContent(
    state: InspectorState,
    mode: InspectorMode,
    modifier: Modifier = Modifier,
) {
    val model = inspectorMode(state, mode, MaterialTheme.colorScheme)
    Row(
        modifier
            .fillMaxWidth()
            .background(model.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("mode-bar"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(model.title, fontWeight = FontWeight.Bold, color = model.foreground)
        Text(model.run, color = model.foreground)
        Text(model.completeness, fontWeight = FontWeight.Bold, color = model.foreground, modifier = Modifier.testTag("completeness"))
    }
}
