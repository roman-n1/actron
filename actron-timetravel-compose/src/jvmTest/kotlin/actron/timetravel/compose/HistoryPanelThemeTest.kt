@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package actron.timetravel.compose

import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.FinalState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.timetravel.HistoryDetails
import actron.timetravel.HistoryTimeline
import actron.timetravel.StateField
import actron.timetravel.inspect.Inspector
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryPanelThemeTest {
    private val light = lightColorScheme(primary = Color(0xFF76254D), primaryContainer = Color(0xFFFFD8E6), secondaryContainer = Color(0xFFBCEEDF), tertiaryContainer = Color(0xFFD5E5FE), surface = Color(0xFFFAF6FA), surfaceContainerHighest = Color(0xFFE8DFE7))
    private val dark = darkColorScheme(primary = Color(0xFFFFB1D0), primaryContainer = Color(0xFF5F1638), secondaryContainer = Color(0xFF164E42), tertiaryContainer = Color(0xFF243D61), surface = Color(0xFF171216), surfaceContainerHighest = Color(0xFF3E353D))

    @Test
    fun inspectorModeColorsFollowTheHostsChangingColorScheme() =
        runComposeUiTest {
            val state = InspectorState(Inspector.of(emptyList()))
            var colors by mutableStateOf(light)
            setContent { MaterialTheme(colorScheme = colors) { ModeBar(state, Modifier.size(900.dp, 80.dp)) } }
            for (scheme in listOf(light, dark)) {
                for ((mode, background) in listOf(InspectorMode.Live to scheme.primaryContainer, InspectorMode.Inspect to scheme.secondaryContainer, InspectorMode.Replay to scheme.tertiaryContainer, InspectorMode.Branch to scheme.surfaceContainerHighest)) {
                    runOnIdle {
                        colors = scheme
                        state.mode = mode
                    }
                    val image = onNodeWithTag("mode-bar").captureToImage()
                    assertEquals(background.toArgb(), image.toPixelMap()[2, 2].toArgb(), "Mode $mode must inherit the host's container color")
                }
            }
        }

    @Test
    fun historyControlsInheritHostShapesAndRenderInBothThemes() =
        runComposeUiTest {
            val timeline = timeline()
            val state = HistoryPanelState<String>().also { it.select(timeline.entries.value.first()) }
            var colors by mutableStateOf(light)
            val square = RoundedCornerShape(0.dp)
            setContent {
                MaterialTheme(colorScheme = colors, shapes = Shapes(square, square, square, square, square)) {
                    HistoryPanel(timeline, state, {}, Modifier.size(1000.dp, 900.dp))
                }
            }
            for ((name, scheme) in listOf("light" to light, "dark" to dark)) {
                runOnIdle { colors = scheme }
                val selectedMode = onNodeWithTag("history-mode-history").captureToImage().toPixelMap()
                assertEquals(scheme.primary.toArgb(), selectedMode[1, 1].toArgb(), "Square host shapes must reach the mode control")
                val selectedRow = onNodeWithTag("history-entry-1").captureToImage().toPixelMap()
                val action = onNodeWithTag("history-entry-action-1", useUnmergedTree = true).captureToImage().toPixelMap()
                assertEquals(scheme.surfaceContainerHigh.toArgb(), selectedRow[5, 5].toArgb(), "Row selection must use a neutral theme surface")
                assertEquals(scheme.secondaryContainer.toArgb(), action[1, 1].toArgb(), "The action must keep its own container")
                val actionCard = onNodeWithTag("history-entry-action-1-card", useUnmergedTree = true).captureToImage().toPixelMap()
                assertEquals(scheme.secondary.toArgb(), actionCard[actionCard.width / 2, 0].toArgb(), "The action border must follow the host theme")
                assertEquals(scheme.secondaryContainer.toArgb(), actionCard[actionCard.width / 2, 3].toArgb(), "The border must preserve the fill")
                onNodeWithTag("history-panel").captureToImage().savePreview("history-$name")
            }
        }

    @Test
    fun graphWarningsUseTheHostTertiaryContainer() =
        runComposeUiTest {
            val timeline = timeline()
            val state = HistoryPanelState<String>().also { it.select(timeline.entries.value.first()) }
            var colors by mutableStateOf(light)
            val idle = StateId("Idle")
            val done = StateId("Done")
            val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done), AtomicState(StateId("Unreachable"))), listOf(Transition(idle, done, ActionMatcher("Search"))))
            setContent { MaterialTheme(colorScheme = colors) { HistoryPanel(timeline, state, {}, Modifier.size(1100.dp, 900.dp), graph = { HistoryGraph.Machine(chart, setOf(idle)) }) } }
            onNodeWithTag("history-graph-tab").performClick()
            for (scheme in listOf(light, dark)) {
                runOnIdle { colors = scheme }
                val banner = onNodeWithTag("graph-diagnostics").captureToImage().toPixelMap()
                assertEquals(scheme.tertiaryContainer.toArgb(), banner[banner.width / 2, 12].toArgb())
            }
        }

    @Test
    fun graphLabelsFollowHostTypographyWhenItChanges() =
        runComposeUiTest {
            val timeline = timeline()
            val state = HistoryPanelState<String>().also { it.select(timeline.entries.value.first()) }
            val idle = StateId("Idle")
            val done = StateId("Done")
            val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, ActionMatcher("Search"))))
            val original = Typography()
            var typography by mutableStateOf(original)
            setContent { MaterialTheme(typography = typography) { HistoryPanel(timeline, state, {}, Modifier.size(1100.dp, 900.dp), graph = { HistoryGraph.Machine(chart, setOf(idle)) }) } }
            onNodeWithTag("history-graph-tab").performClick()
            val before = onNodeWithTag("machine-graph").captureToImage().pixels()
            runOnIdle { typography = Typography(bodySmall = original.bodySmall.copy(fontSize = 20.sp)) }
            val after = onNodeWithTag("machine-graph").captureToImage().pixels()
            assertTrue(before != after, "Canvas labels must follow the host's type scale")
        }

    private fun timeline(): HistoryTimeline<String> {
        val timeline = HistoryTimeline<String>()
        val machine = timeline.machine("City search")
        repeat(4) { index ->
            timeline.append(machine, 0, HistoryDetails("QueryChanged(query=Oslo, requestId=$index)", "Action", "Handled", listOf(StateField("active", "Idle"), StateField("query", "Paris")), listOf(StateField("active", "Debouncing"), StateField("query", "Oslo"))), "Oslo")
        }
        return timeline
    }

    private fun ImageBitmap.pixels(): List<Int> {
        val pixels = toPixelMap()
        return List(width * height) { index -> pixels[index % width, index / width].toArgb() }
    }

    private fun ImageBitmap.savePreview(name: String) {
        val file = File("build/reports/history-panel/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(toAwtImage(), "png", file)
    }
}
