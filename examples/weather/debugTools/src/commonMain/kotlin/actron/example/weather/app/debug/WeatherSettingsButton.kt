package actron.example.weather.app.debug

import actron.example.weather.core.mvi.RequestState
import actron.example.weather.debug.resources.Res
import actron.example.weather.debug.resources.settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun WeatherSettingsButton(openSettings: () -> Unit) {
    val settingsLabel = stringResource(resource = Res.string.settings)
    IconButton(onClick = openSettings, modifier = Modifier.testTag("debug-settings").semantics { contentDescription = settingsLabel }) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.size(24.dp)) {
            val radius = size.minDimension * 0.31f
            val stroke = 2.dp.toPx()
            drawCircle(color, radius, style = Stroke(stroke))
            drawCircle(color, radius * 0.4f, style = Stroke(stroke))
            repeat(8) { tooth ->
                val angle = tooth * kotlin.math.PI.toFloat() / 4f
                val direction = Offset(cos(angle), sin(angle))
                drawLine(color, center + direction * radius, center + direction * (size.minDimension * 0.46f), stroke * 1.6f)
            }
        }
    }
}
