package actron.timetravel.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

@Composable
internal actual fun nativeGraphZoom(onZoom: (Offset, Float) -> Unit): Modifier = Modifier
