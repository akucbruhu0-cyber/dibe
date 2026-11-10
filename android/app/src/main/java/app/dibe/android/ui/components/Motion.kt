package app.dibe.android.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True when the system "Remove animations" setting is on. Provided once at the root. */
val LocalReducedMotion = staticCompositionLocalOf { false }

fun Context.prefersReducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** The desktop's strong ease-out: moves at once, so the UI reads as responsive. */
val EaseOutStrong = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** The desktop `live-pulse` dot: breathes while something is live, still under reduced motion. */
@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier, pulsing: Boolean = true, size: Dp = 8.dp) {
    val alpha = if (pulsing && !LocalReducedMotion.current) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
    } else {
        null
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha?.value ?: 1f }
            .background(color, CircleShape),
    )
}
