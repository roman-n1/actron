package actron.timetravel.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** One clipped state layer for the whole control, including its label and padding. */
@Composable
internal fun HistoryChoice(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    val presentation = historyChoiceColors(pressed, selected, hovered, focused, colors)
    val fill by animateColorAsState(presentation.fill)
    val outline by animateColorAsState(presentation.outline)
    val shape = MaterialTheme.shapes.small
    Box(
        modifier
            .padding(vertical = 4.dp)
            .defaultMinSize(minHeight = 40.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, outline, shape)
            .selectable(selected, interactionSource = interactions, indication = ChoiceIndication, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge, content = label)
        }
    }
}

// Interaction feedback is drawn by HistoryChoice across the entire clipped surface.
private data object ChoiceIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
}

private data class HistoryChoiceColors(
    val fill: androidx.compose.ui.graphics.Color,
    val outline: androidx.compose.ui.graphics.Color,
)

private fun historyChoiceColors(
    pressed: Boolean,
    selected: Boolean,
    hovered: Boolean,
    focused: Boolean,
    colors: androidx.compose.material3.ColorScheme,
): HistoryChoiceColors =
    HistoryChoiceColors(
        when {
            pressed -> colors.secondaryContainer
            selected -> colors.secondaryContainer
            hovered || focused -> colors.surfaceContainerHigh
            else -> colors.surface
        },
        when {
            focused -> colors.primary
            hovered -> colors.outline
            selected -> colors.secondaryContainer
            else -> colors.outlineVariant
        },
    )
