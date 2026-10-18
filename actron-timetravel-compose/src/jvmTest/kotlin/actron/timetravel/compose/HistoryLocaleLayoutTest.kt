package actron.timetravel.compose

import actron.timetravel.HistoryTimeline
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HistoryLocaleLayoutTest {
    @Test
    fun russianEnglishAndFallbackFollowDeviceLanguage() {
        val original: Locale = Locale.getDefault()
        try {
            for ((locale, title) in listOf<Pair<Locale, String>>(Locale.forLanguageTag("ru-RU") to "Инспектор Actron", Locale.ENGLISH to "Actron Inspector", Locale.GERMAN to "Actron Inspector")) {
                Locale.setDefault(locale)
                runComposeUiTest {
                    setContent {
                        MaterialTheme { HistoryPanel(HistoryTimeline<String>(), HistoryPanelState(), {}) }
                    }
                    onNodeWithText(title).assertExists()
                    onNodeWithTag("history-live").assertTextEquals(if (locale.language == "ru") "Сейчас" else "Live")
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun draggingDividerChangesWidthAndKeepsUsableBounds() =
        runComposeUiTest {
            val state = HistoryPanelLayoutState()
            setContent {
                MaterialTheme {
                    ResizableHistoryLayout(state, Modifier.size(900.dp, 600.dp), content = { Text("App") }, panel = { Text("Panel") })
                }
            }
            onNodeWithTag("history-resize-handle").performTouchInput {
                swipe(center, center + Offset(80f, 0f))
            }
            runOnIdle {
                assertTrue(state.widthFraction < 0.72f)
                state.resizeWidth(100f)
                assertTrue(state.widthFraction == 0.8f)
            }
        }

    @Test
    fun narrowLayoutCanResizeHeight() =
        runComposeUiTest {
            val state = HistoryPanelLayoutState()
            setContent {
                MaterialTheme {
                    ResizableHistoryLayout(state, Modifier.size(400.dp, 700.dp), content = { Text("App") }, panel = { Text("Panel") })
                }
            }
            onNodeWithTag("history-resize-handle").performTouchInput {
                swipe(center, center + Offset(0f, -80f))
            }
            runOnIdle { assertTrue(state.heightFraction > 0.65f) }
        }
}
