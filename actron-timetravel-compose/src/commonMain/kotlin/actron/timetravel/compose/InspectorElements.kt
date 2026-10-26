@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun InspectorHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
internal fun InspectorReason(text: String) {
    Text("! $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
}

@Composable
internal fun InspectorRule() {
    Spacer(Modifier.width(1.dp).fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant))
}
