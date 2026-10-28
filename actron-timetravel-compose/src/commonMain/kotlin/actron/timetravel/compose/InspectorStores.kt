package actron.timetravel.compose

import actron.timetravel.compose.resources.Res
import actron.timetravel.compose.resources.no_stores
import actron.timetravel.compose.resources.stores_heading
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
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun InspectorStores(
    state: InspectorState,
    modifier: Modifier,
) {
    val inspector = state.inspector
    Column(modifier.padding(8.dp)) {
        InspectorHeading(stringResource(resource = Res.string.stores_heading))
        for (reason in inspectorGroupReasons(state)) InspectorReason(reason)
        for (store in inspector.stores) {
            val model = inspectorStore(state, store, MaterialTheme.colorScheme)
            InspectorStoreCard(model) { state.toggleFilter(store.id) }
        }
        if (inspector.stores.isEmpty()) Text(stringResource(resource = Res.string.no_stores), style = MaterialTheme.typography.bodySmall)
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
            Text(model.capability.resolve(), style = MaterialTheme.typography.bodySmall)
            Text(model.completeness.resolve(), style = MaterialTheme.typography.bodySmall, color = model.completenessColor)
        }
        Text(model.counts.resolve(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        for (reason in model.reasons) InspectorReason(reason)
        for (notice in model.replay) Text(notice.text.resolve(), style = MaterialTheme.typography.bodySmall, color = notice.color)
    }
}
