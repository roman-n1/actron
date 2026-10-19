package actron.timetravel.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/** Desktop trackpads do not produce the touch pointers used by detectTransformGestures. */
@Composable
internal expect fun nativeGraphZoom(onZoom: (Offset, Float) -> Unit): Modifier
