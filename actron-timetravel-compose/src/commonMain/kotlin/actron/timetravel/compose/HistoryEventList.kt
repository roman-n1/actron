package actron.timetravel.compose

import actron.timetravel.HistoryEntry
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.intl.Locale
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
    val counter = historyEventCounter(state, entries, capacity, Locale.current.language)
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
            HistoryMuted(counter, Modifier.weight(1f).testTag("history-event-counter"))
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
    val summary = historyEventSummary(entry.details, Locale.current.language)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.surfaceContainerHigh else colors.surface)
            .clickable(onClick = onSelect)
            .testTag("history-entry-${entry.id}")
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) colors.primary else colors.surface))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${entry.id} · ${entry.machine.name}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HistoryBookmarkButton(entry.id, bookmarked, onBookmark)
            }
            HistoryEventPreview(summary, entry.id)
            HistoryMuted(formatHistoryTime(entry.at))
        }
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun HistoryBookmarkButton(
    id: Long,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
) {
    val bookmarkLabel = historyText(if (bookmarked) HistoryText.RemoveBookmark else HistoryText.AddBookmark)
    IconToggleButton(
        checked = bookmarked,
        onCheckedChange = { onBookmark() },
        modifier =
            Modifier
                .size(40.dp)
                .testTag("history-entry-bookmark-$id")
                .semantics { contentDescription = bookmarkLabel },
    ) {
        val color = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        Canvas(Modifier.size(14.dp)) {
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

/** Renders the prepared, bounded transition preview independently of row interactions. */
@Composable
private fun HistoryEventPreview(
    summary: HistoryEventSummary,
    id: Long,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(Modifier.fillMaxWidth(0.9f).testTag("history-entry-action-$id-card"), shape = MaterialTheme.shapes.small, color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, border = BorderStroke(1.dp, colors.secondary)) {
            Text(summary.action, Modifier.padding(horizontal = 10.dp, vertical = 6.dp).testTag("history-entry-action-$id"), style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HistoryTransitionArrows(Modifier.fillMaxWidth().height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryPreviewState(historyText(HistoryText.Before), summary.before, "history-entry-before-$id", Modifier.weight(1f))
            HistoryPreviewState(historyText(HistoryText.After), summary.after, "history-entry-after-$id", Modifier.weight(1f))
        }
    }
    Text(summary.changes, Modifier.testTag("history-entry-changes-$id"), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun HistoryPreviewState(
    label: String,
    value: String,
    tag: String,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.testTag("$tag-card"), shape = MaterialTheme.shapes.small, color = colors.surfaceContainerLowest, contentColor = colors.onSurface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            HistoryMuted(label)
            Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
