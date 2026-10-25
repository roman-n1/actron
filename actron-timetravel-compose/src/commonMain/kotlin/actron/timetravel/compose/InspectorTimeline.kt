@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun InspectorTimeline(
    state: InspectorState,
    modifier: Modifier,
) {
    val model = inspectorTimeline(state, MaterialTheme.colorScheme.primaryContainer)
    Column(modifier.padding(8.dp)) {
        InspectorHeading(model.heading)
        LazyColumn(Modifier.fillMaxSize().testTag("timeline")) {
            itemsIndexed(model.rows, key = { _, row -> row.index }) { _, row ->
                Text(
                    row.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = row.color,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(row.background)
                            .clickable { state.select(row.index) }
                            .padding(vertical = 2.dp)
                            .testTag("timeline-${row.index}"),
                )
            }
        }
    }
}
