package app.dibe.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Centre bars react hardest so the shape reads as a voice (desktop idle-hero LevelMeter). */
private val BarWeights = floatArrayOf(0.5f, 0.78f, 1f, 0.78f, 0.5f)
private const val SILENCE_LEVEL = 0.03f
private const val SILENCE_HOLD_MS = 1000L

/**
 * Five hairline bars driven by a 0..1 level. Fast attack, slow release; after a second of silence
 * the bars drop to dim stubs. Decorative: hidden from accessibility.
 */
@Composable
fun LevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    barWidth: Dp = 3.dp,
    gap: Dp = 3.dp,
    minHeight: Dp = 6.dp,
    maxHeight: Dp = 22.dp,
) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(level) {
        val target = level.coerceIn(0f, 1f)
        shown.animateTo(target, tween(if (target > shown.value) 60 else 240, easing = LinearOutSlowInEasing))
    }
    var silent by remember { mutableStateOf(true) }
    val quiet = level < SILENCE_LEVEL
    LaunchedEffect(quiet) {
        if (quiet) {
            delay(SILENCE_HOLD_MS)
            silent = true
        } else {
            silent = false
        }
    }
    val alpha by animateFloatAsState(if (silent) 0.4f else 1f, tween(200), label = "meterAlpha")

    Canvas(modifier.size(barWidth * BarWeights.size + gap * (BarWeights.size - 1), maxHeight).clearAndSetSemantics {}) {
        val width = barWidth.toPx()
        val step = width + gap.toPx()
        val min = minHeight.toPx()
        BarWeights.forEachIndexed { index, weight ->
            val height = if (silent) min else min + shown.value * weight * (size.height - min)
            val x = index * step + width / 2
            // Round caps add half a width at each end: inset the line so the bar keeps its height.
            val half = (height - width).coerceAtLeast(0f) / 2
            drawLine(
                color = color.copy(alpha = color.alpha * alpha),
                start = Offset(x, size.height / 2 - half),
                end = Offset(x, size.height / 2 + half),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
    }
}
