@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun InspectorStores(
    state: InspectorState,
    modifier: Modifier,
) {
    val inspector = state.inspector
    Column(modifier.padding(8.dp)) {
        InspectorHeading("Stores")
        for (reason in inspectorGroupReasons(state)) InspectorReason(reason)
        for (store in inspector.stores) {
            val model = inspectorStore(state, store, MaterialTheme.colorScheme)
            InspectorStoreCard(model) { state.toggleFilter(store.id) }
        }
        if (inspector.stores.isEmpty()) Text("no Stores in this journal", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InspectorStoreCard(
    model: InspectorStorePresentation,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(model.background)
            .clickable(onClick = onClick)
            .padding(6.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("store-${model.id.value}"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.id.value, fontWeight = FontWeight.Bold)
            Text(model.capability, style = MaterialTheme.typography.bodySmall)
            Text(model.completeness, style = MaterialTheme.typography.bodySmall, color = model.completenessColor)
        }
        Text(model.counts, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        for (reason in model.reasons) InspectorReason(reason)
        for (notice in model.replay) Text(notice.text, style = MaterialTheme.typography.bodySmall, color = notice.color)
    }
}
