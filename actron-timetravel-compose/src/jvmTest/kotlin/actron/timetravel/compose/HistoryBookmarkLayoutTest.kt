package actron.timetravel.compose

import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryTimeline
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HistoryBookmarkLayoutTest {
    @Test
    fun bookmarkStaysAtTheHeadersRightAndTheDiagramUsesTheWholeRow() =
        runComposeUiTest {
            val timeline = timeline()
            val state = HistoryPanelState<String>()
            setContent { MaterialTheme { HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 900.dp)) } }
            val bookmark = onNodeWithTag("history-entry-bookmark-1").fetchSemanticsNode().boundsInRoot
            val metadata = onNodeWithText("#1 · Search", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val action = onNodeWithTag("history-entry-action-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val before = onNodeWithTag("history-entry-before-1-card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val after = onNodeWithTag("history-entry-after-1-card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val row = onNodeWithTag("history-entry-1").fetchSemanticsNode().boundsInRoot
            assertTrue(abs(metadata.center.y - bookmark.center.y) < 1f, "Bookmark belongs to the metadata line")
            assertTrue(bookmark.bottom <= action.top, "The diagram must start below the bookmark")
            assertTrue(abs(bookmark.right - after.right) < 1f, "Bookmark stays at the right edge")
            assertTrue(abs(action.center.x - row.center.x) <= 3f, "Only the 3 dp selection strip and pixel rounding may offset the diagram")
            assertTrue(abs((before.left - row.left) - (row.right - after.right)) < 4f, "State cards use the row symmetrically")
            onNodeWithTag("history-entry-bookmark-1").assertIsOff().performClick().assertIsOn()
            runOnIdle {
                assertEquals(setOf(1L), state.inspection.bookmarks)
                assertTrue(state.selection.isEmpty())
                assertTrue(state.preview.isEmpty())
            }
        }

    @Test
    fun unbookmarkedIconIsQuieterThanTheFilledIcon() =
        runComposeUiTest {
            val timeline = timeline()
            val colors = lightColorScheme(surface = Color.White, primary = Color.Black, onSurfaceVariant = Color.Black)
            setContent { MaterialTheme(colorScheme = colors) { HistoryPanel(timeline, HistoryPanelState(), {}, Modifier.size(1000.dp, 900.dp)) } }
            val bookmark = onNodeWithTag("history-entry-bookmark-1")
            val unmarked = bookmark.captureToImage().toPixelMap()
            val unmarkedDarkest = (0 until unmarked.height).minOf { y -> (0 until unmarked.width).minOf { x -> unmarked[x, y].red } }
            bookmark.performClick()
            val marked = bookmark.captureToImage().toPixelMap()
            val markedDarkest = (0 until marked.height).minOf { y -> (0 until marked.width).minOf { x -> marked[x, y].red } }
            assertTrue(unmarkedDarkest > markedDarkest + 0.2f, "The unchecked icon must have visibly lower contrast")
        }

    private fun timeline(): HistoryTimeline<String> =
        HistoryTimeline<String>().also { timeline ->
            timeline.append(timeline.machine("Search"), 0, HistoryDetails("Refresh", "Action", "Handled", emptyList(), emptyList()), "snapshot")
        }
}
