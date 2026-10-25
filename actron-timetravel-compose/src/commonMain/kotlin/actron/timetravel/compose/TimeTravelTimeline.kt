@file:OptIn(actron.core.ExperimentalActronApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package actron.timetravel.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PanelTimeline(
    indices: List<Int>,
    selected: Int,
    vertical: Boolean,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    val timeline = rememberLazyListState()
    LaunchedEffect(selected, indices) {
        val target = indices.indexOfLast { it <= selected }
        if (target >= 0) timeline.scrollToItem(target)
    }
    if (vertical) {
        LazyColumn(state = timeline, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("panel-history-vertical")) {
            items(indices, key = { it }) { index -> PanelStep(index, label(index), index == selected, Modifier.fillMaxWidth()) { onSelect(index) } }
        }
    } else {
        LazyRow(state = timeline, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("panel-history-horizontal")) {
            items(indices, key = { it }) { index -> PanelStep(index, label(index), index == selected) { onSelect(index) } }
        }
    }
}

@Composable
internal fun PanelStep(
    index: Int,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier =
            modifier
                .widthIn(max = 220.dp)
                .semantics { selected = isSelected }
                .clickable(onClick = onClick)
                .testTag("panel-step-$index"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("#$index", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
