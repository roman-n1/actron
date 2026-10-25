package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun <S : Any> HistoryEventList(
    entries: List<HistoryEntry<S>>,
    visible: List<HistoryEntry<S>>,
    state: HistoryPanelState<S>,
    capacity: Int,
    listState: LazyListState,
    modifier: Modifier,
    onSelect: (HistoryEntry<S>) -> Unit,
) {
    val inspection = state.inspection
    Column(modifier) {
        OutlinedTextField(
            inspection.query,
            { inspection.query = it },
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .historyTextInput()
                .testTag("history-search"),
            label = { Text(historyText(HistoryText.Search)) },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            HistoryMuted("${visible.size} / ${entries.size} ${historyText(HistoryText.Events)} · ${historyText(HistoryText.Capacity)} $capacity", Modifier.weight(1f))
            Checkbox(inspection.bookmarksOnly, { inspection.bookmarksOnly = it }, Modifier.testTag("history-bookmarks-only"))
            Text(historyText(HistoryText.Bookmarks), style = MaterialTheme.typography.labelMedium)
        }
        LazyColumn(Modifier.weight(1f).testTag("history-events"), state = listState) {
            items(visible, key = { it.id }) { entry ->
                EventRow(entry, state.selection.any { it.id == entry.id }, entry.id in inspection.bookmarks, onBookmark = { inspection.toggleBookmark(entry.id) }) {
                    onSelect(entry)
                }
            }
        }
        if (visible.isEmpty()) Text(historyText(if (entries.isEmpty()) HistoryText.Empty else HistoryText.NoMatches), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <S : Any> EventRow(
    entry: HistoryEntry<S>,
    selected: Boolean,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .clickable(onClick = onSelect)
            .testTag("history-entry-${entry.id}")
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) colors.primary else colors.surface))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            HistoryMuted("#${entry.id} · ${entry.machine.name}")
            Text(actionSummary(entry.details.input), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryMuted(entry.details.activeBefore(), Modifier.weight(1f))
                HistoryArrow()
                HistoryMuted(entry.details.activeAfter(), Modifier.weight(1f))
            }
            HistoryMuted(formatHistoryTime(entry.at))
        }
        val bookmarkLabel = historyText(if (bookmarked) HistoryText.RemoveBookmark else HistoryText.AddBookmark)
        IconToggleButton(
            checked = bookmarked,
            onCheckedChange = { onBookmark() },
            modifier =
                Modifier
                    .padding(top = 4.dp, end = 4.dp)
                    .size(40.dp)
                    .testTag("history-entry-bookmark-${entry.id}")
                    .semantics { contentDescription = bookmarkLabel },
        ) {
            val color = colors.onSurfaceVariant
            Canvas(Modifier.size(18.dp)) {
                val outline =
                    Path().apply {
                        moveTo(size.width * 0.2f, size.height * 0.1f)
                        lineTo(size.width * 0.8f, size.height * 0.1f)
                        lineTo(size.width * 0.8f, size.height * 0.9f)
                        lineTo(size.width * 0.5f, size.height * 0.68f)
                        lineTo(size.width * 0.2f, size.height * 0.9f)
                        close()
                    }
                drawPath(outline, color, style = if (bookmarked) Fill else Stroke(1.5.dp.toPx()))
            }
        }
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
}
