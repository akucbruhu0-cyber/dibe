package app.dibe.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dibe.android.ui.theme.LocalDibeColors

/**
 * Pocket's segmented pill: a white floating track, the active segment a dark pill sliding on it
 * (a spring, so a quick second tap redirects it mid-way). Radio semantics for TalkBack.
 */
@Composable
fun <T> PillSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val reduced = LocalReducedMotion.current
    val pill = LocalDibeColors.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(pastelBorder(), CircleShape)
            .padding(3.dp)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        val segment = maxWidth / options.size
        val indicator by animateDpAsState(
            targetValue = segment * options.indexOf(selected).coerceAtLeast(0),
            animationSpec = if (reduced) snap() else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
            label = "segmentIndicator",
        )
        Box(
            Modifier
                .offset(x = indicator)
                .width(segment)
                .fillMaxHeight()
                .background(pill.pillDark, CircleShape),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
            options.forEach { option ->
                val active = option == selected
                val color by animateColorAsState(
                    if (active) pill.onPillDark else MaterialTheme.colorScheme.onPrimaryContainer,
                    label = "segmentText",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(selected = active, enabled = enabled, role = Role.RadioButton) { onSelect(option) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(option), style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
